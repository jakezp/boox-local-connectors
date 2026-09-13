#!/usr/bin/env python3
"""Verify a published disposable metadata revision in the actual native database."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import tarfile
import tempfile
import time
from publish_native_fixture import canonical

ROOT = Path(__file__).resolve().parents[2]
NATIVE = "/data/user/0/com.onyx.android.note"
LIBRARY = "com.onyx.android.note/com.onyx.android.sdk.note.ui.library.ui.LibraryActivity"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--publication", type=Path, required=True)
    parser.add_argument("--record", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    publication = json.loads(args.publication.read_text())
    revision, record = publication["revision"], publication["record"]
    notebook = record["notebook"]
    assert re.fullmatch("[a-f0-9]{64}", revision) and hashlib.sha256(canonical(record)).hexdigest() == revision
    assert re.fullmatch("[A-Za-z0-9-]{1,128}", notebook)
    assert notebook.startswith("folder-gdrive-probe-folder-") or notebook.startswith("boox-")
    assert record["device"] == "metadata-fixture-validator" or (
        publication.get("transport") == "Android conflict review UI" and record["device"].startswith("android-")) or (
        publication.get("transport") == "Mac lifecycle GUI own OAuth" and record["device"].startswith("mac-"))
    native_id = notebook.removeprefix("folder-").removeprefix("boox-")
    expected = json.loads(args.record.read_text()) if args.record else None
    assert record["deleted"] == (expected is None)
    if expected:
        assert hashlib.sha256(canonical(expected)).hexdigest() == record["payload"]
    adb = [str(ROOT / "tools/platform-tools/adb"), "-s", args.serial]

    def root(command):
        return subprocess.check_output(adb + ["exec-out", "/debug_ramdisk/su", "-c", command], text=True)

    def read(path):
        value = root("if [ -f " + path + " ]; then cat " + path + "; fi")
        return json.loads(value) if value.strip() else None

    deadline, next_wake = time.monotonic() + 180, 0
    journal_path = NATIVE + "/files/boox_drive_apply/" + revision + "/journal.json"
    while time.monotonic() < deadline:
        if time.monotonic() >= next_wake:
            root("input keyevent KEYCODE_WAKEUP")
            if not root("pidof com.onyx.android.note").strip():
                root("am start -n " + LIBRARY)
            next_wake = time.monotonic() + 15
        journal = read(journal_path)
        if journal and journal["phase"] == "COMMITTED":
            break
        assert not journal or journal["phase"] != "ROLLED_BACK", "Metadata rolled back; evidence retained"
        time.sleep(1)
    else:
        raise RuntimeError("Metadata was not applied within 180 seconds; inspect native status")
    state = read("/data/user/0/local.boox.notesdrive/files/library/states/" + notebook + ".json")
    assert hashlib.sha256(state["revision"].encode()).hexdigest() == revision
    # Copy the SQLite database and its WAL together while the closed library is frozen.
    pid = root("pidof com.onyx.android.note").strip()
    assert pid.isdigit()
    with tempfile.TemporaryDirectory(prefix="native-metadata-readback-") as temporary:
        temporary = Path(temporary)
        snapshot = temporary / "database.tar"
        root("kill -STOP " + pid)
        try:
            with snapshot.open("wb") as output:
                subprocess.run(adb + ["exec-out", "/debug_ramdisk/su", "-c",
                    "tar -C / -cf - data/user/0/com.onyx.android.note/databases"],
                    stdout=output, check=True)
        finally:
            root("kill -CONT " + pid)
        with tarfile.open(snapshot) as archive:
            archive.extractall(temporary, filter="data")
        connection = sqlite3.connect(temporary / NATIVE.lstrip("/") / "databases/ShapeDatabase.db")
        connection.row_factory = sqlite3.Row
        try:
            rows = connection.execute(
                "SELECT uniqueId,title,parentUniqueId,type,status FROM NoteModel WHERE uniqueId=?", [native_id]).fetchall()
        finally:
            connection.close()
    assert len(rows) == 1, "Native row missing or duplicated"
    actual = dict(rows[0])
    assert actual["status"] == (0 if record["deleted"] else 1)
    if expected:
        assert actual["title"] == expected["title"] and actual["type"] == 0
        assert (actual["parentUniqueId"] or None) == expected["parent"]
    report = dict(schema=1, revision=revision, native_row=actual,
                  phase=journal["phase"], branch_verified=True, native_application_verified=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    args.report.chmod(0o600)
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
