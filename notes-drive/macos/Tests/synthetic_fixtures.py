#!/usr/bin/python3
"""Shareable, deterministic native-format test data; never reads a notebook.

All IDs are UUID5 of public labels. All pen coordinates are generated arithmetic
zigzags, not transformed or anonymized handwriting. This independent encoder uses
only Python's standard library, not the production reader/editor or private data.
ZIP_STORED and fixed metadata avoid clock/zlib-dependent fixture bytes.
"""
import argparse
from functools import lru_cache
from hashlib import sha256
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import uuid
import zipfile

NAMESPACE = uuid.UUID("11112222-3333-5444-8555-666677778888")
STAMP = 1600000000000
PEN_COUNTS = [197, 108, 8, 1, 1, 1, 0, 1, 2] + [0] * 11
TITLES = {
    "two-page.note": "Synthetic two-page fixture",
    "two-page-variant.note": "Synthetic two-page variant",
    "chunked-20-page.note": "Synthetic chunked twenty-page fixture",
    "history-five-pen.note": "Synthetic five-pen history fixture",
    "proto3-reexport.note": "Synthetic five-pen history fixture",
}


def identifier(label, compact=False):
    value = uuid.uuid5(NAMESPACE, label)
    return value.hex if compact else str(value)


ADDED_PEN_ID = identifier("editor/added-pen")
EDIT_ID = identifier("editor/edit-namespace")
NEXT_PEN_ID = identifier("editor/next-pen")
ADDED_POINTS = [[100, 100], [140, 120], [180, 100], [220, 130]]


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
        value = value.encode("utf-8")
    wire = (2 if isinstance(value, bytes) else 0) if wire is None else wire
    body = varint(value) if wire == 0 else (varint(len(value)) + value if wire == 2 else value)
    return varint(number * 8 + wire) + body


def encoded_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def zip_bytes(entries):
    target = io.BytesIO()
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, content in entries.items():
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 3
            info.external_attr = (0o40700 if name.endswith("/") else 0o100600) << 16
            archive.writestr(info, content)
    return target.getvalue()


def points_for(page, shape, count):
    return [[120.0 + (shape % 17) * 31 + (sample % 41) * 5,
             180.0 + (page % 7) * 47 + (shape % 11) * 23 + ((sample * 3) % 37) * 7]
            for sample in range(count)]


def point_block(points, editor_style=False):
    return struct.pack(">hh", 0, 0) + b"".join(
        struct.pack(">ffhhi", x, y, 0, 2048 if editor_style else 1024 + (i % 32) * 17, i * 8)
        for i, (x, y) in enumerate(points))


def point_chunk(page_id, revision_id, records):
    data = bytearray(struct.pack(">hh", 0, 1) + page_id.encode().ljust(36, b" ") + revision_id.encode())
    index = bytearray()
    for record in records:
        block = point_block(record["points"], record.get("editor_style", False))
        index += struct.pack(">36sII", record["id"].encode().ljust(36, b" "), len(data), len(block))
        data += block
    offset = len(data)
    return bytes(data + index + struct.pack(">I", offset))


def shape_record(record, point_revision, shape_revision, document, target_page=None):
    points = record["points"]
    bounds = dict(left=min(p[0] for p in points), top=min(p[1] for p in points),
                  right=max(p[0] for p in points), bottom=max(p[1] for p in points), empty=False, stability=0)
    raw = field(1, record["id"]) + field(2, STAMP) + field(3, STAMP)
    raw += field(4, record.get("color", 0xff000000)) + field(5, struct.pack("<f", 4), 5)
    if not record.get("omit_zero_layer", False):
        raw += field(6, record.get("layer", 0))
    raw += field(7, encoded_json(bounds)) + field(12, record["kind"])
    if record["kind"] == 33:
        raw += field(10, encoded_json({"docBean": {"documentId": document, "pageId": target_page}}))
    else:
        raw += field(16, point_revision)
    raw += field(18, shape_revision)
    return raw


def geometry_metadata(document, pages, title, two_layers=False, infinite=False):
    infos = {}
    for i, page in enumerate(pages):
        layers = [dict(id=0, lock=False, show=True)]
        if two_layers and i == 1:
            layers.append(dict(id=1, lock=False, show=True))
        infos[page] = dict(width=1860, height=2480, currentLayerId=0, lastModifyTime=STAMP, layerList=layers)
    info = dict(canvasExpandType="INFINITE" if infinite else "DEFAULT", pageInfoMap=infos,
                syntheticExtension={"purpose": "public regression data"})
    raw = b"".join(field(k, v) for k, v in [
        (1, document), (2, STAMP), (3, STAMP), (6, title), (8, 1),
        (12, encoded_json(info)), (13, b'{"pageBKGroundMap":{},"useDocBKGround":false}'),
        (20, encoded_json({"pageNameList": pages})), (21, b'{"pageNameList":[]}'),
        (24, "Synthetic"), (31, 1), (40, 1), (44, b'{"pageNameList":[]}'),
        (300, b"synthetic unknown note field"),
    ])
    raw += field(22, struct.pack("<f", 1860), 5) + field(23, struct.pack("<f", 2480), 5)
    return field(1, raw) + field(301, b"synthetic unknown wrapper field")


@lru_cache(maxsize=5)
def fixture_bytes(name):
    if name not in TITLES:
        raise ValueError("Unknown synthetic fixture profile")
    large = name == "chunked-20-page.note"
    history = name in ("history-five-pen.note", "proto3-reexport.note")
    family = "large" if large else "history" if history else "two-page"
    document = identifier(family + "/document", compact=history or large)
    pages = [identifier(family + "/page/" + str(i), compact=history or large)
             for i in range(20 if large else 1 if history else 2)]
    root = document + "/"
    entries = {root + "note/pb/note_info": geometry_metadata(
        document, pages, TITLES[name], two_layers=not large and not history, infinite=large)}
    pens_per_page = PEN_COUNTS if large else [5] if history else [4, 7]
    unsupported_per_page = [2, 12] + [0] * 18 if large else [0] * len(pages)
    ordinal = 0
    for page_index, (page, pen_count, unsupported) in enumerate(zip(pages, pens_per_page, unsupported_per_page)):
        records = []
        for index in range(pen_count + unsupported):
            count = (163 if ordinal < 288 else 162) if large else (
                [30, 30, 30, 32, 32][index] if history else 33 if ordinal == 0 else 31)
            record = dict(id=identifier(family + "/pen/" + str(ordinal)),
                          kind=2 if index < pen_count else 2000, points=points_for(page_index, index, count),
                          layer=1 if family == "two-page" and page_index == 1 and index >= 4 else 0)
            records.append(record)
            ordinal += 1
        if name == "proto3-reexport.note":
            records.append(dict(id=ADDED_PEN_ID, kind=2, points=ADDED_POINTS, layer=0,
                                color=0xff1756aa, omit_zero_layer=True, editor_style=True))
        point_groups = [records[:151], records[151:]] if large and page_index == 0 else [records]
        point_revisions = {}
        for i, group in enumerate(point_groups):
            if not group:
                continue
            revision = identifier(family + "/points/" + str(page_index) + "/" + str(i))
            entries[root + "point/" + page + "/" + page + "#" + revision + "#points"] = point_chunk(page, revision, group)
            point_revisions.update({record["id"]: revision for record in group})
        if family == "two-page" and page_index == 1:
            records.append(dict(id=identifier(family + "/link"), kind=33,
                                points=[[400, 400], [550, 500]], layer=0))
        shape_groups = [records[:2], records[2:]] if history else (
            [records[:137], records[137:]] if large and page_index == 0 else [records])
        for i, group in enumerate(shape_groups):
            if not group:
                continue
            revision = identifier(family + "/shapes/" + str(page_index) + "/" + str(i))
            filename = page + "#" + revision + "#" + str(STAMP)
            raw = b"".join(field(1, shape_record(record, point_revisions.get(record["id"], ""), revision,
                                                document, pages[0])) for record in group)
            entries[root + "shape/" + filename + ".zip"] = zip_bytes({filename: raw})
    if history or large:
        shape_names = [n for n in entries if n.startswith(root + "shape/")]
        selected = shape_names[-1] if history else shape_names[0]
        entries[selected.replace("/shape/", "/stash/shape/")] = entries[selected]
    if large:
        point_name = next(n for n in entries if n.endswith("#points"))
        entries[point_name.replace("/point/", "/stash/point/")] = entries[point_name]
        entries[root + "stash/archivedShape/synthetic-history.bin"] = b"synthetic retained history"
        resource_id = identifier("large/resource")
        resource = field(1, resource_id) + field(2, document) + field(8, "synthetic.bin")
        entries[root + "resource/pb/" + resource_id] = field(1, resource)
        entries[root + "resource/data/synthetic.bin"] = b"synthetic resource bytes\x00\xff"
        entries[root + "template/json/" + pages[0] + ".template_json"] = b'{"synthetic":true,"type":"blank"}'
        entries[root + "virtual/doc/pb/" + document] = field(1, identifier("large/virtual")) + field(4, pages[0])
        entries[root + "extra/pb/extra"] = field(1, 1) + field(2, 45326) + field(4, document)
    return zip_bytes(entries)


def write_fixtures(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    manifest = {}
    for name in TITLES:
        data = fixture_bytes(name)
        path = directory / name
        if path.exists():
            if path.read_bytes() != data:
                raise ValueError("Refusing to overwrite differing synthetic fixture: " + name)
        else:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as output:
                output.write(data)
        manifest[name] = dict(sha256=sha256(data).hexdigest(), bytes=len(data), title=TITLES[name])
    return manifest


_temporary = None


@lru_cache(maxsize=1)
def fixture_directory():
    global _temporary
    configured = os.environ.get("BOOX_MAC_TEST_FIXTURE_DIR")
    if configured:
        path = Path(configured)
    else:
        root = Path(__file__).resolve().parents[1] / ".test-runs"
        root.mkdir(exist_ok=True, mode=0o700)
        _temporary = tempfile.TemporaryDirectory(prefix="synthetic-", dir=root)
        path = Path(_temporary.name)
    write_fixtures(path)
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="Dedicated synthetic-output directory; differing files are refused")
    args = parser.parse_args()
    print(json.dumps(write_fixtures(args.output), ensure_ascii=False, indent=2, sort_keys=True))
