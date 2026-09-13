"""Read-only checks for the small native .note fixtures from firmware 4.2.

This is a research validator, not a general notebook reader or import sanitizer.
It understands the observed v1 point format and normal-pen/link shape records.
No archive is extracted and no device or database is modified.
"""

from collections import Counter
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import struct
import sys
from zipfile import ZipFile


MAX_ARCHIVE_BYTES = 16 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def protobuf(data):
    """Decode the wire types used by this firmware; retain repeated fields."""
    offset = 0
    fields = {}

    def varint():
        nonlocal offset
        value = 0
        for shift in range(0, 70, 7):
            require(offset < len(data), "Truncated protobuf varint")
            byte = data[offset]
            offset += 1
            require(shift < 63 or byte <= 1, "Protobuf integer overflow")
            value |= (byte & 127) << shift
            if byte < 128:
                return value
        raise ValueError("Invalid protobuf varint")

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
    return fields


def single(fields, number, default=None):
    values = fields.get(number, [default])
    require(len(values) == 1, f"Duplicate scalar field {number}")
    return values[0]


def bounded_zip(data):
    require(len(data) <= MAX_ARCHIVE_BYTES, "Fixture archive too large")
    archive = ZipFile(BytesIO(data))
    infos = archive.infolist()
    require(len(infos) <= 512, "Too many fixture entries")
    names = [info.filename for info in infos]
    require(len(set(names)) == len(names), "Duplicate archive entry")
    require(sum(info.file_size for info in infos) <= MAX_ARCHIVE_BYTES,
            "Expanded fixture archive too large")
    require(all(not name.startswith("/") and "\\" not in name and
                ".." not in name.split("/") for name in names),
            "Unsafe archive path")
    return archive


def point_records(data):
    """Hash exact point blocks, excluding regenerated IDs and index offsets.

    PointDocument and PointDocumentLoaderV1 in the installed APK define a
    76-byte header, 16-byte samples, 44-byte index entries and 4-byte footer.
    Preserve both sample shorts verbatim; do not reinterpret their naming.
    """
    require(len(data) >= 80, "Truncated point document")
    require(struct.unpack_from(">hh", data) == (0, 1),
            "Unsupported point version")
    xref = struct.unpack_from(">I", data, len(data) - 4)[0]
    require(76 <= xref <= len(data) - 4 and
            (len(data) - 4 - xref) % 44 == 0, "Invalid point index")
    records = {}
    expected_offset = 76
    for position in range(xref, len(data) - 4, 44):
        raw_id, offset, length = struct.unpack_from(">36sII", data, position)
        shape_id = raw_id.decode("ascii").strip()
        require(shape_id and shape_id not in records, "Duplicate/empty shape ID")
        require(offset == expected_offset and length >= 4 and
                (length - 4) % 16 == 0 and offset + length <= xref,
                "Invalid point block")
        block = data[offset:offset + length]
        records[shape_id] = {
            "point_count": (length - 4) // 16,
            "point_sha256": sha256(block).hexdigest(),
        }
        expected_offset += length
    require(expected_offset == xref, "Unindexed point bytes")
    return data[4:40].decode("ascii").strip(), records


def style_hash(fields):
    # These are explicitly compared; other metadata is outside this validator.
    style = {}
    for number in (4, 5, 6, 7, 8, 9, 11, 12, 17, 20, 23, 25, 26):
        value = single(fields, number)
        if value is not None:
            style[str(number)] = value.hex() if isinstance(value, bytes) else value
    return sha256(json.dumps(style, sort_keys=True).encode()).hexdigest()


def inspect_archive(path):
    path = Path(path)
    require(path.stat().st_size <= MAX_ARCHIVE_BYTES, "Fixture file too large")
    data = path.read_bytes()
    with bounded_zip(data) as archive:
        names = archive.namelist()
        note_paths = [name for name in names if name.endswith("/note/pb/note_info")]
        require(len(note_paths) == 1, "Expected one notebook")
        outer = protobuf(archive.read(note_paths[0]))
        note = protobuf(single(outer, 1))
        document_id = single(note, 1).decode()
        title = single(note, 6).decode()
        require(title.startswith("GDrive-Sync-Probe"), "Not a disposable fixture")
        page_ids = json.loads(single(note, 20))["pageNameList"]
        require(len(page_ids) == len(set(page_ids)), "Duplicate page ID")
        page_info = json.loads(single(note, 12))["pageInfoMap"]
        pages = {page_id: {"id": page_id, "points": {}, "shapes": {}} for page_id in page_ids}
        point_pages = set()
        for name in names:
            if "/point/" in name and name.endswith("#points"):
                page_id, records = point_records(archive.read(name))
                require(page_id in pages and page_id not in point_pages,
                        "Unknown/duplicate point page")
                point_pages.add(page_id)
                pages[page_id]["points"] = records
            if "/shape/" in name and name.endswith(".zip"):
                page_id = name.rsplit("/", 1)[-1].split("#")[0]
                require(page_id in pages, "Unknown shape page")
                with bounded_zip(archive.read(name)) as shapes_zip:
                    require(len(shapes_zip.namelist()) == 1, "Unexpected shape archive")
                    shape_data = shapes_zip.read(shapes_zip.namelist()[0])
                for raw_shape in protobuf(shape_data).get(1, []):
                    fields = protobuf(raw_shape)
                    shape_id = single(fields, 1).decode()
                    require(shape_id not in pages[page_id]["shapes"], "Duplicate shape")
                    kind = single(fields, 12, 0)
                    require(kind in (2, 33), "Unsupported fixture shape type")
                    shape = {"id": shape_id, "type": kind, "layer": single(fields, 6, 0),
                             "style_sha256": style_hash(fields)}
                    if kind == 33:
                        link = json.loads(single(fields, 10))
                        target = link["docBean"]
                        shape["link"] = {
                            "document_id": target["documentId"],
                            "page_id": target.get("pageId"),
                            "page_index": target.get("pageIndex"),
                            "internal": target["documentId"] == document_id,
                        }
                    pages[page_id]["shapes"][shape_id] = shape
        result_pages = []
        for page_id in page_ids:
            page = pages[page_id]
            require(set(page["points"]) == set(page["shapes"]),
                    "Shape/point correspondence failed")
            info = page_info[page_id]
            shapes = []
            for shape_id, shape in page["shapes"].items():
                shapes.append({**shape, **page["points"][shape_id]})
            result_pages.append({
                "id": page_id, "width": info["width"], "height": info["height"],
                "layers": info["layerList"], "shapes": shapes,
            })
    return {"file": path.name, "sha256": sha256(data).hexdigest(),
            "document_id": document_id, "title": title, "pages": result_pages}


def compare(before, after):
    """Compare page order, pen data/style, layers and internal link destinations."""
    require(len(before["pages"]) == len(after["pages"]), "Page count changed")
    page_results = []
    for old, new in zip(before["pages"], after["pages"]):
        def pens(page):
            return Counter((s["point_sha256"], s["style_sha256"], s["layer"])
                           for s in page["shapes"] if s["type"] == 2)

        old_pens, new_pens = pens(old), pens(new)
        old_links = [s["link"] for s in old["shapes"] if s["type"] == 33]
        new_links = [s["link"] for s in new["shapes"] if s["type"] == 33]
        page_ids = [p["id"] for p in after["pages"]]
        links_valid = len(old_links) == len(new_links) and all(
            link["internal"] and link["page_id"] in page_ids and
            link["page_index"] == page_ids.index(link["page_id"])
            for link in new_links
        )
        old_target_indices = Counter(link["page_index"] for link in old_links)
        new_target_indices = Counter(link["page_index"] for link in new_links)
        page_results.append({
            "source_page": old["id"], "destination_page": new["id"],
            "pen_data_and_style_equal": old_pens == new_pens,
            "source_pens_preserved": not (old_pens - new_pens),
            "added_pens": sum((new_pens - old_pens).values()),
            "layers_equal": old["layers"] == new["layers"],
            "dimensions_equal": (old["width"], old["height"]) == (new["width"], new["height"]),
            "internal_links_remapped": links_valid and old_target_indices == new_target_indices,
            "source_links": len(old_links), "destination_links": len(new_links),
        })
    return {"source": before["file"], "destination": after["file"],
            "document_id_changed": before["document_id"] != after["document_id"],
            "pages": page_results}


if __name__ == "__main__":
    reports = [inspect_archive(path) for path in sys.argv[1:]]
    output = {"archives": reports}
    if len(reports) == 2:
        output["comparison"] = compare(*reports)
    print(json.dumps(output, indent=2))
