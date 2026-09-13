#!/usr/bin/python3
"""Generate public native-apply variants from the pinned synthetic five-pen base.

Uses the production pen writer for add/erase. Normalization only omits explicitly
encoded zero-valued field 6 in active shapes; IDs, points and opaque data survive.
No existing fixture or notebook is read. Differing outputs are never overwritten.
"""
import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import sys

from synthetic_fixtures import fixture_bytes, identifier, ADDED_PEN_ID, ADDED_POINTS, EDIT_ID, STAMP

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "Resources"))
import note_editor as editor
import note_reader as reader


def encoded_json(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def active_shapes(data):
    note = reader.inspect_bytes(data)
    records = []
    with reader.archive(data, [0]) as archive:
        for path in archive.namelist():
            if path.startswith(note["document_id"] + "/shape/") and path.endswith(".zip"):
                with reader.archive(archive.read(path), [0]) as nested:
                    raw = nested.read(nested.namelist()[0])
                    records.extend(reader.protobuf(raw).get(1, []))
    return records


def omit_zero_layers(data):
    note = reader.inspect_bytes(data)
    replacements = {}
    with reader.archive(data, [0]) as archive:
        for path in archive.namelist():
            if not path.startswith(note["document_id"] + "/shape/") or not path.endswith(".zip"):
                continue
            content = archive.read(path)
            with reader.archive(content, [0]) as nested:
                name = nested.namelist()[0]
                raw = nested.read(name)
            records = []
            for number, _, value, encoded in editor.wire_fields(raw):
                if number == 1 and reader.single(reader.protobuf(value), 6, None) == 0:
                    records.append(editor.field(1, editor.patch(value, {6: b""})))
                else:
                    records.append(encoded)
            changed = editor.rewrite_zip(content, {name: (name, b"".join(records))})
            replacements[path] = (path, changed)
    return editor.rewrite_zip(data, replacements)


def generated():
    base = fixture_bytes("history-five-pen.note")
    pins = json.loads((Path(__file__).parent / "synthetic-manifest.json").read_text())
    if sha256(base).hexdigest() != pins["history-five-pen.note"]["sha256"]:
        raise ValueError("Synthetic base differs from its pinned manifest")
    note = reader.inspect_bytes(base)
    page = note["pages"][0]
    add_plan = dict(base_sha256=sha256(base).hexdigest(), document_id=note["document_id"],
                    edit_id=EDIT_ID, edited_at_ms=STAMP + 1000,
                    operations=[dict(kind="add", page_id=page["id"], id=ADDED_PEN_ID,
                                     layer=0, width=4.0, color=0xff1756aa, points=ADDED_POINTS)])
    added, _ = editor.edit_bytes(base, add_plan)
    erased_id = identifier("history/pen/0")
    erase_plan = dict(base_sha256=sha256(added).hexdigest(), document_id=note["document_id"],
                      edit_id=identifier("native-apply/erase"), edited_at_ms=STAMP + 2000,
                      operations=[dict(kind="erase", page_id=page["id"], ids=[erased_id])])
    erased, _ = editor.edit_bytes(added, erase_plan)
    normalized = omit_zero_layers(added)
    files = {"history-five-pen.note": base, "history-add.note": added,
             "history-erase.note": erased, "history-add-normalized.note": normalized,
             "add-plan.json": encoded_json(add_plan), "erase-plan.json": encoded_json(erase_plan)}
    details = {}
    for name, data in files.items():
        info = dict(sha256=sha256(data).hexdigest(), bytes=len(data))
        if name.endswith(".note"):
            decoded = reader.inspect_bytes(data)
            shapes = active_shapes(data)
            info.update(page_count=len(decoded["pages"]),
                        visible_pens=sum(s["supported"] for p in decoded["pages"] for s in p["strokes"]),
                        stored_samples=decoded["sample_count"], raw_shapes=len(shapes),
                        tombstones=sum(reader.single(reader.protobuf(s), 15, 0) == 1 for s in shapes))
        details[name] = info
    manifest = dict(schema=1, synthetic_only=True, document_id=note["document_id"],
                    page_id=page["id"], added_shape_id=ADDED_PEN_ID, erased_shape_id=erased_id,
                    normalization="Omit explicit zero field 6 from active shape records only",
                    files=details)
    files["native-apply-manifest.json"] = encoded_json(manifest)
    return files, manifest


def write(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    files, manifest = generated()
    for name, data in files.items():
        path = directory / name
        if path.is_symlink() or path.exists() and path.read_bytes() != data:
            raise ValueError("Refusing to overwrite differing generated output: " + name)
    for name, data in files.items():
        path = directory / name
        if not path.exists():
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as output:
                output.write(data)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    print(json.dumps(write(args.output), indent=2, sort_keys=True))
