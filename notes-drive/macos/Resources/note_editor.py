#!/usr/bin/python3
"""Bounded native pen edits. Unchanged member contents and unknown wire fields survive.

This is not a general BOOX serializer. It modifies active normal-pen records,
retains removed pens using native status 1, and never rewrites source files.
"""
import copy
import io
import json
import math
import os
from pathlib import Path
import struct
import sys
import uuid
import zipfile

from note_reader import archive, inspect_bytes, note_record, protobuf, require, single, text
from hashlib import sha256

MAX_PAYLOAD = 4 * 1024 * 1024
MAX_OPERATIONS = 500
MAX_NEW_POINTS = 50000


def integer(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        require(offset < len(data), "Truncated protobuf integer")
        byte = data[offset]
        offset += 1
        require(shift < 63 or byte <= 1, "Protobuf integer overflow")
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError("Invalid protobuf integer")


def wire_fields(data):
    """Retain the exact encoding of every field, including unknown fields."""
    protobuf(data)  # Reuse the reader's type, field-count and length bounds.
    result, offset = [], 0
    while offset < len(data):
        start = offset
        tag, offset = integer(data, offset)
        number, wire = tag >> 3, tag & 7
        if wire == 0:
            value, offset = integer(data, offset)
        else:
            size, offset = integer(data, offset) if wire == 2 else ({1: 8, 5: 4}[wire], offset)
            value = data[offset:offset + size]
            offset += size
        result.append((number, wire, value, data[start:offset]))
    return result


def varint(value):
    value &= (1 << 64) - 1
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def field(number, value, wire=None):
    if isinstance(value, str):
        value = value.encode()
    wire = (2 if isinstance(value, bytes) else 0) if wire is None else wire
    body = varint(value) if wire == 0 else (varint(len(value)) + value if wire == 2 else value)
    return varint((number << 3) | wire) + body


def patch(data, replacements):
    remaining = dict(replacements)
    result = []
    seen = set()
    for number, _, _, raw in wire_fields(data):
        if number in replacements:
            require(number not in seen, "Duplicate edited scalar field")
            seen.add(number)
            result.append(remaining.pop(number))
        else:
            result.append(raw)
    result.extend(remaining.values())
    return b"".join(result)


def replace_note_record(outer, document_id, replacement):
    """Replace only the selected document; retain folder/wrapper bytes in place."""
    original, _ = note_record(outer, document_id)
    result = b"".join(field(1, replacement) if number == 1 and value == original else raw
                      for number, _, value, raw in wire_fields(outer))
    note_record(result, document_id)
    return result


def json_bytes(value):
    return json.dumps(value, separators=(",", ":"), allow_nan=False).encode()


def rewrite_zip(data, replacements, additions=None):
    """Preserve each untouched member's bytes, metadata and archive comment.

    Container compression/offsets may change; member contents are never decoded
    and reserialized unless explicitly named in replacements.
    """
    output = io.BytesIO()
    with archive(data, [0]) as original, zipfile.ZipFile(output, "w") as target:
        target.comment = original.comment
        names = set()
        for info in original.infolist():
            name, content = replacements.get(info.filename, (info.filename, original.read(info)))
            require(name not in names, "Edited archive path collision")
            names.add(name)
            metadata = copy.copy(info)
            metadata.filename = name
            target.writestr(metadata, content)
        for name, content in (additions or {}).items():
            require(name not in names, "Added archive path collision")
            names.add(name)
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o600 << 16
            target.writestr(info, content)
    return output.getvalue()


def new_shape(pen, point_revision, info_revision, stamp):
    x, y = zip(*pen["points"])
    bounds = dict(left=min(x), top=min(y), right=max(x), bottom=max(y), empty=False, stability=0)
    color = pen["color"]
    if color >= (1 << 31):
        color -= 1 << 32
    values = [
        field(1, pen["id"]), field(2, stamp), field(3, stamp), field(4, color),
        field(5, struct.pack("<f", pen["width"]), 5), field(6, pen["layer"]),
        field(7, json_bytes(bounds)),
        field(11, json_bytes(dict(alphaFactor=1.0, displayScale=1.0, dpi=320.0,
                                 maxPressure=4095.0, pressure=2048.0, pressureSensitivity=0.3,
                                 smoothLevel=0.0, source=0, tiltX=0, tiltY=0))),
        field(12, 2), field(16, point_revision),
        field(17, b'{"lineStyle":{"phase":0.0,"type":0}}'),
        field(18, info_revision), field(21, b"[]"), field(26, b'{"repo":{}}'),
    ]
    return b"".join(values)


def new_points(page_id, revision, pens):
    require(len(page_id.encode("ascii")) <= 36, "Page ID is incompatible with native point format")
    data = bytearray(struct.pack(">hh", 0, 1) + page_id.encode().ljust(36, b" ") + revision.encode())
    index = bytearray()
    for pen in pens:
        block = struct.pack(">hh", 0, 0) + b"".join(
            struct.pack(">ffhhi", x, y, 0, 2048, i * 8) for i, (x, y) in enumerate(pen["points"]))
        index.extend(struct.pack(">36sII", pen["id"].encode(), len(data), len(block)))
        data.extend(block)
    xref = len(data)
    return bytes(data + index + struct.pack(">I", xref))


def edit_bytes(data, plan):
    require(len(data) <= MAX_PAYLOAD, "Editing requires an export within the 4 MiB transport limit")
    require(set(plan) == {"base_sha256", "document_id", "edit_id", "edited_at_ms", "operations"},
            "Unknown/missing edit plan fields")
    require(sha256(data).hexdigest() == plan["base_sha256"], "Edit base checksum mismatch")
    namespace = uuid.UUID(plan["edit_id"])
    require(str(namespace) == plan["edit_id"], "Edit ID must be a canonical UUID")
    stamp = plan["edited_at_ms"]
    require(type(stamp) == int and 0 < stamp < (1 << 53), "Invalid edit timestamp")
    operations = plan["operations"]
    require(isinstance(operations, list) and len(operations) <= MAX_OPERATIONS, "Too many edit operations")
    note = inspect_bytes(data)
    require(note["document_id"] == plan["document_id"], "Notebook identity changed")
    pages = {p["id"]: p for p in note["pages"]}
    original_pens = {(p["id"], s["id"]): s for p in note["pages"] for s in p["strokes"]}
    additions, removed, new_ids, total_points = {}, set(), set(), 0
    all_ids = {s["id"] for s in original_pens.values()}
    for operation in operations:
        kind, page_id = operation.get("kind"), operation.get("page_id")
        require(page_id in pages, "Unknown edit page")
        page = pages[page_id]
        unlocked = {l["id"] for l in page["layers"] if l["visible"] and not l["locked"]}
        if kind == "add":
            require(set(operation) == {"kind", "page_id", "id", "layer", "width", "color", "points"},
                    "Unknown/missing pen fields")
            pen_id = operation["id"]
            require(str(uuid.UUID(pen_id)) == pen_id and pen_id not in all_ids | new_ids,
                    "Duplicate/invalid added shape ID")
            require(type(operation["layer"]) == int and operation["layer"] in unlocked, "Layer is hidden/locked/unknown")
            require(type(operation["color"]) == int and 0 <= operation["color"] <= 0xffffffff, "Invalid pen color")
            width, points = operation["width"], operation["points"]
            require(type(width) in (float, int) and math.isfinite(width) and 0.5 <= width <= 40, "Invalid pen width")
            require(isinstance(points, list) and 2 <= len(points) <= 5000, "A pen needs 2–5,000 points")
            for point in points:
                require(isinstance(point, list) and len(point) == 2 and
                        all(type(v) in (float, int) and math.isfinite(v) for v in point) and
                        0 <= point[0] <= page["width"] and 0 <= point[1] <= page["height"],
                        "Pen point is outside the declared page")
            total_points += len(points)
            require(total_points <= MAX_NEW_POINTS, "Edit exceeds 50,000 new samples")
            new_ids.add(pen_id)
            additions[(page_id, pen_id)] = operation
        elif kind == "erase":
            require(set(operation) == {"kind", "page_id", "ids"} and isinstance(operation["ids"], list)
                    and 0 < len(operation["ids"]) <= 1000 and len(set(operation["ids"])) == len(operation["ids"]),
                    "Invalid erase operation")
            for shape_id in operation["ids"]:
                key = (page_id, shape_id)
                if key in additions:
                    require(additions[key]["layer"] in unlocked, "Cannot erase a locked layer")
                    del additions[key]
                else:
                    shape = original_pens.get(key)
                    require(shape is not None and shape["supported"] and shape["type"] == 2 and
                            shape["layer"] in unlocked and key not in removed,
                            "Erase requires an active editable pen")
                    removed.add(key)
        else:
            raise ValueError("Unsupported edit operation")
    if not additions and not removed:
        return data, {"changed": False, "sha256": plan["base_sha256"], "added": 0, "removed": 0}
    changed_pages = {p for p, _ in additions} | {p for p, _ in removed}
    replacements, extra = {}, {}
    with archive(data, [0]) as z:
        names = z.namelist()
        note_path = next(n for n in names if n.endswith("/note/pb/note_info"))
        root = note_path[:-len("note/pb/note_info")]
        outer = z.read(note_path)
        raw_note, _ = note_record(outer, note["document_id"])
        note_fields = protobuf(raw_note)
        # Native timestamps only make local PB changes discoverable. Wire ancestry
        # continues to use the captured parent hashes, never timestamp ordering.
        stamp = max(stamp, single(note_fields, 3, 0) + 1)
        shape_archives = {}
        for name in names:
            if not name.startswith(root + "shape/") or not name.endswith(".zip"):
                continue
            page_id = name.rsplit("/", 1)[-1].split("#")[0]
            with archive(z.read(name), [0]) as sz:
                inner = next(n for n in sz.namelist() if not n.endswith("/"))
                raw = sz.read(inner)
                records = protobuf(raw).get(1, [])
                for record in records:
                    stamp = max(stamp, single(protobuf(record), 3, 0) + 1)
                shape_archives.setdefault(page_id, []).append((name, inner, raw))
        require(stamp < (1 << 53), "Native timestamp exceeds edit limit")
        for page_id in sorted(changed_pages):
            pens = [p for (page, _), p in additions.items() if page == page_id]
            point_rev = str(uuid.uuid5(namespace, page_id + "/points"))
            if pens:
                extra[root + "point/" + page_id + "/" + page_id + "#" + point_rev + "#points"] = new_points(page_id, point_rev, pens)
            archives = shape_archives.get(page_id, [])
            if not archives:
                info_rev = str(uuid.uuid5(namespace, page_id + "/shapes"))
                filename = page_id + "#" + info_rev + "#" + str(stamp)
                raw = b"".join(field(1, new_shape(p, point_rev, info_rev, stamp)) for p in pens)
                extra[root + "shape/" + filename + ".zip"] = rewrite_zip(empty_zip(), {}, {filename: raw})
            for index, (name, inner, raw) in enumerate(archives):
                fields = wire_fields(raw)
                affected = any((page_id, text(single(protobuf(value), 1))) in removed
                               for number, _, value, _ in fields if number == 1)
                if not affected and not (index == 0 and pens):
                    continue
                info_rev = str(uuid.uuid5(namespace, name))
                records = []
                for number, _, value, encoded in fields:
                    if number != 1:
                        records.append(encoded)
                        continue
                    shape_id = text(single(protobuf(value), 1))
                    updates = {18: field(18, info_rev)}
                    if (page_id, shape_id) in removed:
                        updates.update({15: field(15, 1), 3: field(3, stamp)})
                    records.append(field(1, patch(value, updates)))
                if index == 0:
                    records.extend(field(1, new_shape(p, point_rev, info_rev, stamp)) for p in pens)
                filename = page_id + "#" + info_rev + "#" + str(stamp)
                changed = rewrite_zip(z.read(name), {inner: (filename, b"".join(records))})
                replacements[name] = (root + "shape/" + filename + ".zip", changed)
        page_info = json.loads(single(note_fields, 12))
        for page_id in changed_pages:
            page_info["pageInfoMap"][page_id]["lastModifyTime"] = stamp
        raw_note = patch(raw_note, {3: field(3, stamp), 12: field(12, json_bytes(page_info))})
        replacements[note_path] = (note_path, replace_note_record(outer, note["document_id"], raw_note))
    output = rewrite_zip(data, replacements, extra)
    require(len(output) <= MAX_PAYLOAD, "Edited export exceeds 4 MiB; shorten the edit")
    result = inspect_bytes(output)
    require(result["document_id"] == note["document_id"] and
            [p["id"] for p in result["pages"]] == [p["id"] for p in note["pages"]], "Edited identities changed")
    expected = {key for key, s in original_pens.items() if s["supported"]} - removed | set(additions)
    actual = {(p["id"], s["id"]) for p in result["pages"] for s in p["strokes"] if s["supported"]}
    require(expected == actual, "Edited pen readback mismatch")
    return output, {"changed": True, "sha256": sha256(output).hexdigest(), "added": len(additions),
                    "removed": len(removed), "document_id": note["document_id"], "edited_at_ms": stamp}


def empty_zip():
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w"):
        pass
    return result.getvalue()


def run(input_path, plan_path, output_path):
    source, target = Path(input_path), Path(output_path)
    require(source.resolve() != target.resolve() and not target.exists(), "Output must be a new file; originals are never overwritten")
    with source.open("rb") as f:
        data = f.read(MAX_PAYLOAD + 1)
    with open(plan_path, "rb") as f:
        plan_data = f.read(4 * 1024 * 1024 + 1)
    require(len(plan_data) <= 4 * 1024 * 1024, "Edit plan too large")
    output, report = edit_bytes(data, json.loads(plan_data))
    fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(output)
        f.flush()
        os.fsync(f.fileno())
    parent = os.open(target.parent, os.O_RDONLY)
    try:
        os.fsync(parent)
    finally:
        os.close(parent)
    return report


if __name__ == "__main__":
    try:
        require(len(sys.argv) == 4, "Usage: note_editor.py input.note plan.json new-output.note")
        print(json.dumps(run(*sys.argv[1:]), separators=(",", ":")))
    except Exception as error:
        print(json.dumps({"error": str(error)}))
        sys.exit(1)
