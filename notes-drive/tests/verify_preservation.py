#!/usr/bin/env python3
"""Compare untouched notebook rows and associated bytes between private snapshots."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tarfile
import tempfile

DATABASE = "data/user/0/com.onyx.android.note/databases/ShapeDatabase.db"


def inventory(path, identities):
    files = {}
    with tempfile.TemporaryDirectory(prefix="notebook-preservation-") as directory:
        directory = Path(directory)
        with tarfile.open(path) as archive:
            for member in archive:
                if not member.isfile():
                    continue
                name = member.name.removeprefix("./").lstrip("/")
                associated = any(identity in name.split("/") or
                                 Path(name).name.startswith(identity + ".") or
                                 Path(name).name.startswith(identity + "_") for identity in identities)
                if associated:
                    assert name not in files, "Duplicate archive path"
                    files[name] = hashlib.file_digest(archive.extractfile(member), "sha256").hexdigest()
                if name in {DATABASE, DATABASE + "-wal", DATABASE + "-shm", DATABASE + "-journal"}:
                    target = directory / Path(name).name
                    assert not target.exists(), "Duplicate database entry"
                    target.write_bytes(archive.extractfile(member).read())
        database = directory / "ShapeDatabase.db"
        assert database.exists(), "Missing native metadata database"
        connection = sqlite3.connect(database)
        connection.row_factory = sqlite3.Row
        rows = {}
        try:
            for identity in identities:
                selected = connection.execute("SELECT * FROM NoteModel WHERE uniqueId=?", [identity]).fetchall()
                assert len(selected) == 1, "Missing or duplicated original notebook"
                rows[identity] = dict(selected[0])
        finally:
            connection.close()
    return files, rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--notebook", action="append", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    assert all(re.fullmatch("[A-Za-z0-9-]{1,100}", identity) for identity in args.notebook)
    before, old_rows = inventory(args.before, args.notebook)
    after, new_rows = inventory(args.after, args.notebook)
    changed = sorted(name for name in before if before[name] != after.get(name))
    added = sorted(set(after) - set(before))
    rows = {identity: sorted(key for key in set(old_rows[identity]) | set(new_rows[identity])
                             if old_rows[identity].get(key) != new_rows[identity].get(key))
            for identity in args.notebook}
    report = dict(original_files_before=len(before), original_files_after=len(after),
                  changed_original_files=changed, added_original_files=added,
                  original_row_changed_fields=rows,
                  preserved=not changed and not added and not any(rows.values()))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    args.report.chmod(0o600)
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["preserved"] else 1)


if __name__ == "__main__":
    main()
