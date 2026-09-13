#!/usr/bin/python3
"""Create bounded blank native notebooks; patch metadata without rebuilding content."""
import json
import os
from pathlib import Path
import re
import struct
import sys
import uuid
from hashlib import sha256

from note_editor import empty_zip, field, json_bytes, patch, replace_note_record, rewrite_zip
from note_reader import archive, inspect_bytes, note_record, protobuf, require, single, text

MAX_PAYLOAD = 4 * 1024 * 1024


def metadata(plan):
    title, parent, stamp = plan["title"], plan["parent"], plan["edited_at_ms"]
    require(isinstance(title, str) and "\0" not in title and
            0 < len(title.encode("utf-16-le")) // 2 <= 1000, "Invalid notebook title")
    require(parent is None or isinstance(parent, str) and
            re.fullmatch(r"[A-Za-z0-9_-]{1,100}", parent), "Invalid parent folder")
    require(type(stamp) == int and 0 < stamp < (1 << 53), "Invalid metadata timestamp")
    return title, parent, stamp


def create_bytes(plan):
    require(set(plan) == {"operation", "document_id", "page_ids", "title", "parent",
                         "width", "height", "edited_at_ms"} and plan["operation"] == "create",
            "Unknown/missing notebook creation fields")
    title, parent, stamp = metadata(plan)
    document_id, pages = plan["document_id"], plan["page_ids"]
    require(isinstance(pages, list) and 1 <= len(pages) <= 32 and
            all(isinstance(v, str) and re.fullmatch(r"[0-9a-f]{32}", v) for v in [document_id] + pages) and
            len(set([document_id] + pages)) == len(pages) + 1, "Creation requires fresh distinct 32-character IDs")
    require(parent != document_id, "Notebook cannot parent itself")
    width, height = plan["width"], plan["height"]
    require(all(type(n) == int and 100 <= n <= 10000 for n in (width, height)), "Invalid blank page dimensions")
    rect = dict(bottom=height, empty=False, left=0, right=width, stability=0, top=0)
    page_info = dict(canvasExpandType="DEFAULT", coverPageId="", defaultPageRect=dict(rect, valid=True),
                     pageInfoMap={p: dict(currentLayerId=0, width=width, height=height,
                                          lastModifyTime=stamp,
                                          layerList=[dict(id=0, lock=False, show=True)]) for p in pages})
    background = dict(bkGroundConfig=dict(applyAllPage=False, asDefault=True, canvasAutoExpand=False, scaleType=1),
                      docBKGround=dict(bkGroundResRectF=rect, cloud=False, height=height,
                                       resId="0", resIndex=0, title="Blank", type=0, value="0",
                                       visible=True, width=width, **{"global": True}),
                      pageBKGroundMap={}, useDocBKGround=True)
    # NoteInfoProto / NoteInfoPBDocument for Notes 45326. Owner, digest, commit,
    # virtual resources and ONYX account fields are deliberately absent in a new note.
    values = {1: document_id, 2: stamp, 3: stamp, 6: title, 8: 1,
              11: b"{}", 12: json_bytes(page_info), 13: json_bytes(background),
              15: -16777216, 16: 2, 20: json_bytes(dict(pageNameList=pages)),
              21: b'{"pageNameList":[]}', 24: "Local", 31: 1, 37: 1, 40: 1,
              44: b'{"pageNameList":[]}'}
    if parent:
        values[4] = parent
    raw = b"".join(field(k, v) for k, v in sorted(values.items()))
    raw += field(9, struct.pack("<f", 4), 5) + field(10, struct.pack("<f", 15), 5)
    raw += field(22, struct.pack("<f", width), 5) + field(23, struct.pack("<f", height), 5)
    root = document_id + "/"
    members = {root: b"", root + "note/pb/note_info": field(1, raw),
               root + "shape/": b"", root + "point/": b""}
    # Empty directories are intentional: native PagePointLoader receives an
    # existing page directory with no records. No synthetic pen is invented.
    members.update({root + "point/" + p + "/": b"" for p in pages})
    result = rewrite_zip(empty_zip(), {}, members)
    note = inspect_bytes(result)
    require(note["document_id"] == document_id and len(note["pages"]) == len(pages), "Blank readback failed")
    return result


def change_bytes(data, plan):
    require(set(plan) == {"operation", "document_id", "base_sha256", "title", "parent", "edited_at_ms"} and
            plan["operation"] == "metadata", "Unknown/missing notebook metadata fields")
    title, parent, stamp = metadata(plan)
    require(0 < len(data) <= MAX_PAYLOAD and sha256(data).hexdigest() == plan["base_sha256"],
            "Metadata base checksum/size mismatch")
    note = inspect_bytes(data)
    require(note["document_id"] == plan["document_id"] and parent != note["document_id"],
            "Metadata notebook identity mismatch")
    with archive(data, [0]) as z:
        path = note["document_id"] + "/note/pb/note_info"
        require(all(n.startswith(note["document_id"] + "/") for n in z.namelist()),
                "Archive contains files outside the native notebook root")
        outer = z.read(path)
        raw, _ = note_record(outer, note["document_id"])
        fields = protobuf(raw)
        require(single(fields, 8, 0) == 1 and single(fields, 27, 0) == 0 and single(fields, 30, 0) == 0,
                "Only ordinary unlocked native notebooks support metadata changes")
        current_parent = text(single(fields, 4, b"")) or None
        if title == note["title"] and parent == current_parent and single(fields, 31, 0) == 1:
            return data
        raw = patch(raw, {3: field(3, max(stamp, single(fields, 3, 0) + 1)),
                          4: field(4, parent) if parent else b"", 6: field(6, title), 31: field(31, 1)})
        result = rewrite_zip(data, {path: (path, replace_note_record(outer, note["document_id"], raw))})
    require(len(result) <= MAX_PAYLOAD, "Metadata snapshot exceeds transport limit")
    after = inspect_bytes(result, metadata_only=True)
    require(after["document_id"] == note["document_id"] and after["title"] == title and
            after["parent_id"] == parent, "Metadata readback failed")
    return result


def command(input_path, plan_path, output_path):
    require(Path(plan_path).stat().st_size <= 32768, "Library plan exceeds 32 KiB")
    plan = json.loads(Path(plan_path).read_text())
    if plan.get("operation") == "create":
        require(input_path in ("-", "/-"), "New notebook must not use an existing archive")
        result = create_bytes(plan)
    else:
        require(Path(input_path).stat().st_size <= MAX_PAYLOAD, "Metadata input exceeds 4 MiB")
        result = change_bytes(Path(input_path).read_bytes(), plan)
    fd = os.open(output_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(result)
        output.flush()
        os.fsync(output.fileno())
    return dict(sha256=sha256(result).hexdigest(), byte_count=len(result))


if __name__ == "__main__":
    try:
        require(len(sys.argv) == 4, "Usage: note_library.py input.note-or-- plan.json new-output.note")
        print(json.dumps(command(*sys.argv[1:])))
    except Exception as error:
        print(json.dumps(dict(error=str(error))))
        sys.exit(1)
