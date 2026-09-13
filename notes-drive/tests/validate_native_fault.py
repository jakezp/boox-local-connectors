#!/usr/bin/env python3
"""One disposable live crash/recovery case through the real native coordinator.

The fixture publisher verifies the title and exact branch. This runner verifies
the durable checkpoint, recovered branch, per-document files and owned SQL rows,
then removes its validation pause and waits for normal completion.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
NATIVE = "/data/user/0/com.onyx.android.note"
LIBRARY = "com.onyx.android.note/com.onyx.android.sdk.note.ui.library.ui.LibraryActivity"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def tree_hash(path):
    if not path.exists():
        return "absent"
    assert not path.is_symlink()
    if path.is_file():
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest()
    return digest("".join(p.name + "\0" + tree_hash(p) + "\n" for p in sorted(path.iterdir())).encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("payload", type=Path)
    parser.add_argument("--phase", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-base", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resume", action="store_true", help="Recheck an existing paused validation case")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    output.chmod(0o700)
    adb = [str(ROOT / "tools/platform-tools/adb"), "-s", args.serial]

    def root(command, binary=False):
        return subprocess.check_output(adb + ["exec-out", "/debug_ramdisk/su", "-c", command],
                                       text=not binary)

    def read(path):
        text = root("if [ -f " + path + " ]; then cat " + path + "; fi")
        return json.loads(text) if text.strip() else None

    def wait(predicate, description, seconds=150):
        deadline = time.monotonic() + seconds
        next_wake = 0
        while time.monotonic() < deadline:
            if time.monotonic() >= next_wake:
                root("input keyevent KEYCODE_WAKEUP")
                next_wake = time.monotonic() + 15
            value = predicate()
            if value:
                return value
            time.sleep(1)
        raise RuntimeError("Timed out: " + description + ". Validation pause/evidence retained.")

    publication = output / "publication.json"
    if not args.resume:
        subprocess.run([sys.executable, str(ROOT / "notes-drive/tests/publish_native_fixture.py"),
                        str(args.payload), "--serial", args.serial, "--expected-base", args.expected_base,
                        "--report", str(publication), "--fault-phase", args.phase], check=True)
    published = json.loads(publication.read_text())
    assert published["base"] == args.expected_base and published["fault_phase"] == args.phase
    assert published["payload"] == digest(args.payload.read_bytes())
    revision, native_id = published["revision"], published["native_id"]
    native_root = NATIVE + "/files/boox_drive_apply"
    job = native_root + "/" + revision
    branch_path = "/data/user/0/local.boox.notesdrive/files/library/states/boox-" + native_id + ".json"

    def branch():
        return digest(read(branch_path)["revision"].encode())

    def hit():
        value = read(native_root + "/last-validation-fault.json")
        return value if value and value.get("revision") == revision and value.get("phase") == args.phase else None

    checkpoint = wait(hit, "native fault checkpoint")
    root("am force-stop com.onyx.android.note")
    interrupted = read(native_root + "/active.json")
    root("am start -n " + LIBRARY)
    root("am start -n local.boox.notesdrive/.SetupActivity")
    wait(lambda: read(native_root + "/active.json") is None, "native journal recovery")
    # Wait for the restarted process to report recovery and read the pause.
    time.sleep(4)
    recovered = read(job + "/journal.json")
    acknowledged = args.phase == "ACKNOWLEDGED"
    assert recovered["phase"] == ("COMMITTED" if acknowledged else "ROLLED_BACK"), recovered["phase"]
    assert branch() == (revision if acknowledged else args.expected_base), "Recovery advanced the wrong branch"
    report = dict(schema=1, phase=args.phase, revision=revision, checkpoint=checkpoint,
                  interrupted_phase=interrupted["phase"] if interrupted else None,
                  recovered_phase=recovered["phase"], branch_after_recovery=branch())

    if not acknowledged:
        # Freeze the process only for a coherent private evidence copy; always resume.
        pid = root("pidof com.onyx.android.note").strip()
        assert pid.isdigit()
        snapshot = output / "recovered-native.tar"
        root("kill -STOP " + pid)
        try:
            paths = [job, NATIVE + "/databases", "/data/media/0/.ksync/document/" + native_id,
                     "/data/media/0/.ksync/point/" + native_id]
            with snapshot.open("wb") as stream:
                subprocess.run(adb + ["exec-out", "/debug_ramdisk/su", "-c",
                                     "tar -C / -cf - " + " ".join(p.lstrip("/") for p in paths)],
                               stdout=stream, check=True)
        finally:
            root("kill -CONT " + pid)
        snapshot.chmod(0o600)
        with tempfile.TemporaryDirectory(prefix="native-recovery-check-") as work:
            work = Path(work)
            with tarfile.open(snapshot) as archive:
                archive.extractall(work, filter="data")
            old_job = work / job.lstrip("/")
            targets = dict(database=work / (NATIVE + "/databases/" + native_id + ".db").lstrip("/"),
                           document=work / ("data/media/0/.ksync/document/" + native_id),
                           point=work / ("data/media/0/.ksync/point/" + native_id))
            hashes = {name: tree_hash(path) for name, path in targets.items()}
            assert hashes == recovered["oldHashes"], "Native file rollback differs from verified preimage"
            rows = json.loads((old_job / "old-rows.json").read_text())
            count = 0
            for database, tables in rows.items():
                connection = sqlite3.connect(work / (NATIVE + "/databases/" + database).lstrip("/"))
                connection.row_factory = sqlite3.Row
                try:
                    for table, data in tables.items():
                        assert table.replace("_", "").isalnum() and data["key"].replace("_", "").isalnum()
                        actual = []
                        for row in connection.execute(f'SELECT * FROM "{table}" WHERE "{data["key"]}"=?', [native_id]):
                            actual.append({k: {"blob": base64.b64encode(v).decode()} if isinstance(v, bytes) else v
                                           for k, v in dict(row).items()})
                        # JSONObject serializes integral Double values without ".0".
                        # SQL REAL affinity restores 1860 as 1860.0; these values are equal.
                        def canonical(value):
                            value = {key: int(item) if isinstance(item, float) and item.is_integer() else item
                                     for key, item in value.items()}
                            return json.dumps(value, sort_keys=True, separators=(",", ":"))
                        assert sorted(map(canonical, actual)) == sorted(map(canonical, data["rows"])), table
                        count += len(actual)
                finally:
                    connection.close()
            report.update(restored_component_hashes=hashes, restored_owned_rows=count,
                          private_snapshot_sha256=digest(snapshot.read_bytes()))
    root("rm " + native_root + "/validation-paused.json")
    wait(lambda: branch() == revision, "normal retry after verified recovery")
    assert read(job + "/journal.json")["phase"] == "COMMITTED"
    report["normal_retry_committed"] = True
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
