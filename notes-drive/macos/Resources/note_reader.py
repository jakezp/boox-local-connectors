#!/usr/bin/python3
"""Read BOOX firmware 4.2 exports without extracting or modifying them.

This is a pen-coordinate preview, not BOOX's renderer. Unknown content is
reported. Based on the repository's native_fixture.py and recorded SDK formats.
"""
import io
import json
import math
import re
import struct
import sys
import zipfile
from hashlib import sha256
from pathlib import Path

MAX_BYTES = 16 * 1024 * 1024
MAX_POINTS = 200000
MAX_SHAPES = 10000


def require(ok, message):
    if not ok:
        raise ValueError(message)


def protobuf(data):
    offset, fields, field_count = 0, {}, 0

    def varint():
        nonlocal offset
        value = 0
        for shift in range(0, 70, 7):
            require(offset < len(data), "Truncated protobuf integer")
            byte = data[offset]
            offset += 1
            require(shift < 63 or byte <= 1, "Protobuf integer overflow")
            value |= (byte & 127) << shift
            if byte < 128:
                return value
        raise ValueError("Invalid protobuf integer")

    while offset < len(data):
        tag = varint()
        number, wire = tag >> 3, tag & 7
        require(number > 0, "Invalid protobuf field")
        if wire == 0:
            value = varint()
        else:
            require(wire in (1, 2, 5), "Unsupported protobuf wire type")
            size = varint() if wire == 2 else {1: 8, 5: 4}[wire]
            require(offset + size <= len(data), "Truncated protobuf field")
            value = data[offset:offset + size]
            offset += size
        fields.setdefault(number, []).append(value)
        field_count += 1
        require(field_count <= 50000, "Too many protobuf fields")
    return fields


def single(fields, number, default=None):
    values = fields.get(number, [default])
    require(len(values) == 1, "Duplicate scalar field %d" % number)
    return values[0]


def text(value):
    require(isinstance(value, bytes), "Expected text field")
    result = value.decode("utf-8")
    require(len(result) <= 32768, "Text field too large")
    return result


def note_record(data, document_id):
    """Select one notebook from the native repeated NoteModel wrapper.

    Native exports may embed ancestor folders. They are metadata, not additional
    notebooks. A move can retain old folder rows, so an incomplete or detached
    folder chain is allowed; duplicate identities and cycles are not.
    """
    def valid_id(value):
        return bool(re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value))

    require(valid_id(document_id), "Invalid archive-root notebook identity")
    records = protobuf(data).get(1, [])
    require(0 < len(records) <= 256, "Expected 1–256 native metadata records")
    rows, raw_note = {}, None
    for raw in records:
        require(isinstance(raw, bytes), "Invalid native metadata record")
        fields = protobuf(raw)
        identity = text(single(fields, 1))
        parent = text(single(fields, 4, b"")) or None
        title = text(single(fields, 6))
        kind = single(fields, 8, 0)
        require(valid_id(identity) and identity not in rows, "Invalid/duplicate native metadata identity")
        require(parent is None or valid_id(parent), "Invalid native metadata parent")
        require(kind == (1 if identity == document_id else 0),
                "Unsupported native metadata type or additional notebook")
        rows[identity] = dict(id=identity, parent=parent, title=title)
        if identity == document_id:
            raw_note = raw
    require(raw_note is not None, "Archive-root notebook metadata is missing")
    for identity, row in rows.items():
        seen = {identity}
        parent = row["parent"]
        while parent is not None:
            require(parent != document_id and parent not in seen, "Cyclic native metadata ancestry")
            seen.add(parent)
            parent = rows[parent]["parent"] if parent in rows else None
    return raw_note, [row for identity, row in rows.items() if identity != document_id]


def archive(data, budget):
    require(len(data) <= MAX_BYTES, "Archive too large (16 MiB limit)")
    result = zipfile.ZipFile(io.BytesIO(data))
    infos = result.infolist()
    require(len(infos) <= 512, "Too many archive entries")
    names = [i.filename for i in infos]
    require(len(names) == len(set(names)), "Duplicate archive path")
    require(all(not n.startswith("/") and "\\" not in n and
                ".." not in n.split("/") for n in names), "Unsafe archive path")
    require(all(not i.flag_bits & 1 for i in infos), "Encrypted export unsupported")
    require(all(i.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                for i in infos), "Unsupported ZIP compression")
    budget[0] += sum(i.file_size for i in infos)
    require(budget[0] <= MAX_BYTES, "Expanded archives exceed 16 MiB")
    return result


def point_records(data, counters):
    require(len(data) >= 80, "Truncated point document")
    require(struct.unpack_from(">hh", data) == (0, 1), "Unsupported point version")
    page_id = data[4:40].decode("ascii").strip()
    revision_id = data[40:76].decode("ascii").strip()
    require(page_id and revision_id, "Missing point page/revision ID")
    xref = struct.unpack_from(">I", data, len(data) - 4)[0]
    require(76 <= xref <= len(data) - 4 and (len(data)-4-xref) % 44 == 0,
            "Invalid point index")
    records, expected = {}, 76
    for position in range(xref, len(data) - 4, 44):
        raw_id, offset, length = struct.unpack_from(">36sII", data, position)
        shape_id = raw_id.decode("ascii").strip()
        require(shape_id and shape_id not in records, "Duplicate/empty point ID")
        require(offset == expected and length >= 4 and (length-4) % 16 == 0 and
                offset + length <= xref, "Invalid point block")
        count = (length-4) // 16
        counters[0] += count
        require(counters[0] <= MAX_POINTS, "Notebook exceeds 200,000 point preview limit")
        points = []
        for x, y, size, pressure, stamp in struct.iter_unpack(
                ">ffhhi", data[offset+4:offset+length]):
            require(math.isfinite(x) and math.isfinite(y) and
                    abs(x) <= 1000000 and abs(y) <= 1000000,
                    "Invalid pen coordinate")
            points.append([x, y])
        records[shape_id] = {
            "points": points,
            "point_hash": sha256(data[offset:offset+length]).hexdigest(),
            "sample_count": count,
        }
        expected += length
    require(expected == xref, "Unindexed point data")
    return page_id, revision_id, records


def rectangle(fields):
    raw = single(fields, 7)
    if not raw:
        return None
    value = json.loads(raw)
    rect = [float(value.get(k, 0)) for k in ("left", "top", "right", "bottom")]
    require(all(math.isfinite(n) and abs(n) <= 1000000 for n in rect),
            "Invalid shape bounds")
    return rect


def inspect_bytes(data, metadata_only=False):
    require(len(data) <= MAX_BYTES, "Notebook exceeds 16 MiB preview limit")
    budget, counters = [0], [0]
    warnings = [
        "Pen-coordinate preview: recorded color and nominal width; BOOX pressure, "
        "brush smoothing and eraser compositing are not reproduced."
    ]
    with archive(data, budget) as z:
        names = z.namelist()
        note_paths = [n for n in names if n.endswith("/note/pb/note_info")]
        require(len(note_paths) == 1, "Expected exactly one BOOX notebook")
        notebook_root = note_paths[0][:-len("note/pb/note_info")]
        point_prefix, shape_prefix = notebook_root + "point/", notebook_root + "shape/"
        raw_note, folders = note_record(z.read(note_paths[0]), notebook_root[:-1])
        note = protobuf(raw_note)
        if folders:
            warnings.append("%d embedded folder metadata rows are preserved; "
                            "Drive folder records determine the library hierarchy." % len(folders))
        document_id, title = text(single(note, 1)), text(single(note, 6))
        page_ids = json.loads(single(note, 20))["pageNameList"]
        require(isinstance(page_ids, list) and 0 < len(page_ids) <= 200,
                "Expected 1–200 notebook pages")
        require(all(isinstance(p, str) and len(p) <= 128 for p in page_ids)
                and len(set(page_ids)) == len(page_ids), "Invalid/duplicate page IDs")
        page_info = json.loads(single(note, 12))["pageInfoMap"]
        pages = {}
        for page_id in page_ids:
            info = page_info[page_id]
            width, height = float(info["width"]), float(info["height"])
            require(all(math.isfinite(n) and 0 < n <= 100000 for n in (width, height)),
                    "Unsupported page dimensions")
            layers = info.get("layerList", [])
            require(isinstance(layers, list) and len(layers) <= 256,
                    "Invalid layer list")
            layers = [{"id": int(l["id"]), "visible": bool(l.get("show", True)),
                       "locked": bool(l.get("lock", False))} for l in layers]
            require(len({l["id"] for l in layers}) == len(layers), "Duplicate layer ID")
            pages[page_id] = {"id": page_id, "width": width, "height": height,
                              "layers": layers, "strokes": [], "warnings": [],
                              "raw_points": {}, "shape_ids": set(), "point_refs": set()}
        if metadata_only:
            return {"title": title, "document_id": document_id,
                    "sha256": sha256(data).hexdigest(), "byte_count": len(data),
                    "page_count": len(page_ids), "page_ids": page_ids,
                    "parent_id": text(single(note, 4, b"")) or None}
        seen_points, seen_shapes, shape_count = set(), set(), 0
        for name in names:
            if name.startswith(point_prefix) and name.endswith("#points"):
                page_id, revision_id, records = point_records(z.read(name), counters)
                require(page_id in pages, "Unknown point page")
                require((page_id, revision_id) not in seen_points,
                        "Duplicate point revision for page")
                seen_points.add((page_id, revision_id))
                # Stock PageListShapeData exports multiple chunks per page after
                # 20,000 samples. Shape field 16 identifies the required chunk;
                # never let a different revision replace its point block.
                pages[page_id]["raw_points"].update(
                    ((revision_id, shape_id), record)
                    for shape_id, record in records.items())
        for name in names:
            # A stock save also includes stash/shape history. Only this
            # notebook's active shape directory supplies the current preview.
            if not name.startswith(shape_prefix) or not name.endswith(".zip"):
                continue
            filename = name[len(shape_prefix):]
            require("/" not in filename, "Unsupported nested active shape archive")
            parts = filename[:-4].split("#")
            require(len(parts) == 3 and all(parts), "Invalid shape archive name")
            page_id, revision_id, _ = parts
            require(page_id in pages, "Unknown shape page")
            require((page_id, revision_id) not in seen_shapes,
                    "Duplicate shape revision for page")
            if any(p == page_id for p, _ in seen_shapes):
                pages[page_id]["warnings"].append(
                    "Multiple active shape chunks; cross-chunk drawing order is approximate.")
            seen_shapes.add((page_id, revision_id))
            page = pages[page_id]
            with archive(z.read(name), budget) as sz:
                entries = [n for n in sz.namelist() if not n.endswith("/")]
                require(len(entries) == 1, "Unexpected shape archive")
                shape_document = protobuf(sz.read(entries[0]))
                if set(shape_document) - {1}:
                    page["warnings"].append("Unknown shape archive fields are not rendered.")
                records = shape_document.get(1, [])
            for raw in records:
                shape_count += 1
                require(shape_count <= MAX_SHAPES, "Too many shapes")
                fields = protobuf(raw)
                shape_id = text(single(fields, 1))
                require(shape_id, "Empty shape ID")
                require(shape_id not in page["shape_ids"], "Duplicate shape ID")
                page["shape_ids"].add(shape_id)
                kind, layer = single(fields, 12, 0), single(fields, 6, 0)
                raw_width = single(fields, 5, struct.pack("<f", 2))
                require(isinstance(raw_width, bytes) and len(raw_width) == 4,
                        "Invalid pen width")
                width = struct.unpack("<f", raw_width)[0]
                require(math.isfinite(width) and 0 <= width <= 10000, "Invalid pen width")
                point_ref = (text(single(fields, 16, b"")), shape_id)
                record = page["raw_points"].get(point_ref)
                if record is not None:
                    page["point_refs"].add(point_ref)
                if single(fields, 15, 0) == 1:
                    # Native ShapeStatus.REMOVED is a retained tombstone, not
                    # an unsupported visible shape or an orange placeholder.
                    continue
                supported = kind == 2 and record is not None
                points = [] if record is None else record["points"]
                matrix = single(fields, 8)
                if matrix:
                    values = json.loads(matrix)["values"]
                    require(isinstance(values, list) and len(values) == 9 and
                            all(isinstance(v, (int, float)) and math.isfinite(v) and
                                abs(v) <= 1000000 for v in values), "Invalid transform")
                    if values[6:] != [0, 0, 1]:
                        supported = False
                        page["warnings"].append("Perspective transform omitted.")
                    elif kind == 2:
                        a, c, tx, b, d, ty = values[:6]
                        points = [[a*x+c*y+tx, b*x+d*y+ty] for x, y in points]
                        require(all(abs(n) <= 1000000 for p in points for n in p),
                                "Transformed coordinate exceeds preview bounds")
                        if abs(a-d) > 0.0001 or abs(b+c) > 0.0001:
                            page["warnings"].append("Nonuniform pen transform: width is approximate.")
                        width *= math.sqrt(a*a+b*b)
                        require(width <= 100000, "Transformed pen width exceeds preview bounds")
                label, target = "", None
                if kind == 33:
                    label = "Notebook link (preview placeholder)"
                    try:
                        link = json.loads(single(fields, 10))["docBean"]
                        if link.get("documentId") == document_id:
                            target = link.get("pageId")
                            if target not in pages:
                                target = None
                        else:
                            label = "External notebook link (not followed)"
                    except (KeyError, ValueError, TypeError):
                        label = "Unsupported link data"
                    page["warnings"].append("Link thumbnails are not rendered; labeled bounds show their position.")
                elif not supported:
                    label = "Unsupported shape type %s" % kind
                    page["warnings"].append(label)
                if single(fields, 15, 0) != 0:
                    supported = False
                    page["warnings"].append("Non-default shape status; shape omitted.")
                if kind == 2 and record is None:
                    page["warnings"].append("Pen shape missing its point block for the recorded revision.")
                if any(single(fields, n) for n in (14, 22, 24)):
                    page["warnings"].append("Embedded resource/rich-text/inline point data is not rendered.")
                page["strokes"].append({
                    "id": shape_id, "type": kind, "layer": layer,
                    "width": width, "color": single(fields, 4, 0xff000000) & 0xffffffff,
                    "supported": supported, "points": points if supported else [],
                    "point_hash": record["point_hash"] if record else None,
                    "sample_count": record["sample_count"] if record else 0,
                    "bounds": rectangle(fields), "label": label, "target_page": target,
                })
        for page in pages.values():
            orphans = set(page["raw_points"]) - page["point_refs"]
            if orphans:
                page["warnings"].append(
                    "%d unreferenced point blocks (possibly retired revisions) are not rendered." % len(orphans))
            del page["raw_points"], page["shape_ids"], page["point_refs"]
            known_layers = {l["id"] for l in page["layers"]}
            if any(s["layer"] not in known_layers for s in page["strokes"]):
                page["warnings"].append("Shapes reference unknown layer/z-order values; shown after known layers.")
            page["warnings"] = sorted(set(page["warnings"]))
        if any("/resource/" in n and not n.endswith("/") for n in names):
            warnings.append("Resource records are present; attachments and media are not decoded.")
        if any(n.endswith(".template_json") for n in names):
            warnings.append("Page template/background artwork is not rendered (white paper shown).")
        if any("/virtual/" in n and not n.endswith("/") for n in names):
            warnings.append("Virtual/infinite-canvas metadata is present; only the declared notebook pages are shown.")
        if any("/stash/" in n and not n.endswith("/") for n in names):
            warnings.append("Archived/stashed content is present; historical shapes are not rendered.")
        omitted_records = sum(
            ("/point/" in n and n.endswith("#points") and not n.startswith(point_prefix)) or
            ("/shape/" in n and n.endswith(".zip") and not n.startswith(shape_prefix))
            for n in names)
        if omitted_records:
            warnings.append("%d point/shape files outside the active notebook directories are not rendered."
                            % omitted_records)
    return {"title": title, "document_id": document_id, "sha256": sha256(data).hexdigest(),
            "byte_count": len(data), "sample_count": counters[0],
            "warnings": warnings, "pages": [pages[p] for p in page_ids]}


def inspect_path(path, expected_hash=None, metadata_only=False):
    with open(path, "rb") as source:
        data = source.read(MAX_BYTES+1)
    if expected_hash is not None:
        require(sha256(data).hexdigest() == expected_hash,
                "Notebook checksum mismatch before archive decoding")
    return inspect_bytes(data, metadata_only=metadata_only)


if __name__ == "__main__":
    try:
        arguments = sys.argv[1:]
        metadata_only = "--metadata" in arguments
        if metadata_only:
            arguments.remove("--metadata")
        require(len(arguments) in (1, 2), "Usage: note_reader.py notebook.note [expected-sha256] [--metadata]")
        expected = arguments[1] if len(arguments) == 2 else None
        json.dump(inspect_path(arguments[0], expected, metadata_only),
                  sys.stdout, separators=(",", ":"), allow_nan=False)
    except Exception as error:
        print(json.dumps({"error": str(error)}))
        sys.exit(1)
