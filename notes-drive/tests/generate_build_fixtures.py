#!/usr/bin/env python3
"""Generate Android build/test inputs without reading personal notebooks."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile

MAC = Path(__file__).resolve().parents[1] / "macos"
sys.path.insert(0, str(MAC / "Tests"))
sys.path.insert(0, str(MAC / "Resources"))
from synthetic_fixtures import fixture_bytes, zip_bytes, field, ADDED_PEN_ID, ADDED_POINTS, EDIT_ID, identifier
import note_editor
import note_reader


def generate(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    base = fixture_bytes("history-five-pen.note")
    note = note_reader.inspect_bytes(base)
    plan = dict(
        base_sha256=hashlib.sha256(base).hexdigest(),
        document_id=note["document_id"], edit_id=EDIT_ID, edited_at_ms=1789250000000,
        operations=[dict(kind="add", page_id=note["pages"][0]["id"], id=ADDED_PEN_ID,
                         layer=0, width=4.0, color=0xff1756aa, points=ADDED_POINTS)])
    added, _ = note_editor.edit_bytes(base, plan)
    erase_plan = dict(plan, base_sha256=hashlib.sha256(added).hexdigest(),
                      edit_id=identifier("android-test/erase"),
                      operations=[dict(kind="erase", page_id=note["pages"][0]["id"],
                                       ids=[ADDED_PEN_ID])])
    erased, _ = note_editor.edit_bytes(added, erase_plan)
    # Model the observed native proto3 export: omit explicit zero z-order.
    # Preserve every other raw field, signed color, pen option and history byte.
    with zipfile.ZipFile(io.BytesIO(added)) as archive:
        normalized = {name: archive.read(name) for name in archive.namelist()}
    for name, data in list(normalized.items()):
        if "/shape/" not in name or "/stash/" in name:
            continue
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            members = {member: archive.read(member) for member in archive.namelist()}
        for member, raw in members.items():
            records = []
            for number, wire, value, encoded in note_editor.wire_fields(raw):
                if number == 1 and wire == 2:
                    value = b"".join(part for key, kind, value, part in note_editor.wire_fields(value)
                                     if not (key == 6 and kind == 0 and value == 0))
                    encoded = field(1, value)
                records.append(encoded)
            members[member] = b"".join(records)
        normalized[name] = zip_bytes(members)
    fixtures = {
        "connection-test.note": fixture_bytes("two-page.note"),
        "synthetic-add.note": added,
        "synthetic-erase.note": erased,
        "synthetic-normalized.note": zip_bytes(normalized),
    }
    manifest = {}
    for name, data in fixtures.items():
        (directory / name).write_bytes(data)
        manifest[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    print(json.dumps(generate(parser.parse_args().output), indent=2))
