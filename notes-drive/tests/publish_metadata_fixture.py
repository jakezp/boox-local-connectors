#!/usr/bin/env python3
"""Publish a disposable folder change or recoverable fixture tombstone.

This uses Android's own Google grant. An existing named probe anchors the Drive
binding. Only that probe or folders in the gdrive-probe-folder- namespace can be
targeted. It never writes native rows or the connector's applied library branch.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

from publish_native_fixture import APP, ROOT, canonical


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--anchor", required=True, help="Native ID of an existing GDrive-Sync-Probe notebook")
    p.add_argument("--notebook", required=True)
    p.add_argument("--expected-base", required=True, help="Empty only for a new disposable folder")
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--record", type=Path)
    mode.add_argument("--delete", action="store_true")
    p.add_argument("--report", type=Path, required=True)
    args = p.parse_args()
    assert re.fullmatch("[A-Za-z0-9-]{1,100}", args.anchor)
    assert re.fullmatch("[A-Za-z0-9-]{1,128}", args.notebook)
    folder = args.notebook.startswith("folder-gdrive-probe-folder-")
    assert folder or (args.delete and args.notebook == "boox-" + args.anchor), "Not a disposable target"
    adb = [str(ROOT / "tools/platform-tools/adb"), "-s", args.serial]

    def root(command, binary=False):
        return subprocess.check_output(adb + ["exec-out", "/debug_ramdisk/su", "-c", command], text=not binary)

    def read(path):
        raw = root("if [ -f " + path + " ]; then cat " + path + "; fi")
        return json.loads(raw) if raw.strip() else None

    anchor = read(APP + "/files/library/captures/boox-" + args.anchor + ".json")
    assert anchor and anchor["title"].startswith("GDrive-Sync-Probe")
    state_path = APP + "/files/library/states/" + args.notebook + ".json"
    capture_path = APP + "/files/library/captures/" + args.notebook + ".json"
    state, capture = read(state_path), read(capture_path)
    if state is None:
        assert capture is None and not args.expected_base and not args.delete
    else:
        assert capture and capture["title"].startswith("GDrive-Sync-Probe")
        assert state["enqueued"] and state["fingerprint"] == capture["fingerprint"]
        assert hashlib.sha256(state["revision"].encode()).hexdigest() == args.expected_base
        assert (state["account"], state["folder"]) == (anchor["account"], anchor["folder"])
    data = None
    if args.record:
        record = json.loads(args.record.read_text())
        assert set(record) == {"id", "kind", "parent", "schema", "title"}
        assert type(record["schema"]) is int and record["schema"] == 1 and record["kind"] == "folder"
        assert args.notebook == "folder-" + record["id"]
        assert record["title"].startswith("GDrive-Sync-Probe")
        assert record["parent"] is None or re.fullmatch("gdrive-probe-folder-[A-Za-z0-9-]+", record["parent"])
        data = canonical(record)
    revision = dict(schema=1, notebook=args.notebook, device="metadata-fixture-validator",
                    parents=[args.expected_base] if args.expected_base else [],
                    payload=hashlib.sha256(data).hexdigest() if data is not None else None,
                    deleted=data is None)
    encoded = canonical(revision)
    revision_id = hashlib.sha256(encoded).hexdigest()
    job = dict(schema=1, account=anchor["account"], folder=anchor["folder"],
               revision=encoded.decode(), state="pending")
    root("am force-stop local.boox.notesdrive")
    assert read(state_path) == state and read(capture_path) == capture, "Fixture changed before publication"
    uid = root("stat -c %u " + APP).strip()
    assert uid.isdigit() and int(uid) >= 10000
    with tempfile.TemporaryDirectory(prefix="boox-metadata-fixture-") as temporary:
        def write(content, destination):
            path = Path(temporary) / "stage"
            path.write_bytes(content); path.chmod(0o600)
            remote = "/data/local/tmp/boox-metadata-fixture-stage"
            subprocess.run(adb + ["push", str(path), remote], capture_output=True, check=True)
            staged = destination + ".fixture-tmp"
            root(f"cp {remote} {staged} && chown {uid}:{uid} {staged} && chmod 600 {staged} && "
                 f"restorecon {staged} && sync && mv {staged} {destination} && sync && rm {remote}")
            assert root("cat " + destination, True) == content
        if data is not None:
            write(data, APP + "/files/revisions/payloads/" + revision["payload"] + ".note")
        write(canonical(job), APP + "/files/revisions/jobs/" + revision_id + ".json")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    report = dict(revision=revision_id, record=revision, native_application_verified=False,
                  transport="Android app-owned grant; synthetic metadata validation")
    args.report.write_text(json.dumps(report, indent=2) + "\n"); args.report.chmod(0o600)
    root("input keyevent KEYCODE_WAKEUP")
    root("am start -n local.boox.notesdrive/.SetupActivity")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
