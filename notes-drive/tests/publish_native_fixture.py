#!/usr/bin/env python3
"""Seed an independent fixture branch through Android's existing app-owned grant.

This tests native application of Mac-encoded bytes, not the Mac OAuth/UI path.
Only named GDrive-Sync-Probe notebooks are eligible. Existing notebooks require
an exact base; --create-anchor binds a new fixture to an existing named probe.
It never changes native content or the applied library branch directly.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
APP = "/data/user/0/local.boox.notesdrive"


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("payload", type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-base", required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--fault-phase")
    parser.add_argument("--create-anchor", help="Existing native probe ID, only for a new fixture with empty base")
    args = parser.parse_args()
    adb = [str(ROOT / "tools/platform-tools/adb"), "-s", args.serial]

    def root(command, binary=False):
        return subprocess.check_output(adb + ["exec-out", "/debug_ramdisk/su", "-c", command],
                                       text=not binary)

    def read(path):
        value = root("if [ -f " + shlex.quote(path) + " ]; then cat " + shlex.quote(path) + "; fi")
        return json.loads(value) if value.strip() else None

    spec = importlib.util.spec_from_file_location("fixture_reader", ROOT / "notes-drive/macos/Resources/note_reader.py")
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    data = args.payload.read_bytes()
    note = reader.inspect_bytes(data)
    assert note["title"].startswith("GDrive-Sync-Probe"), "Only named disposable fixtures may be published"
    native_id = note["document_id"]
    assert re.fullmatch("[A-Za-z0-9-]{1,100}", native_id), "Invalid native ID"
    notebook = "boox-" + native_id
    capture_path = f"{APP}/files/library/captures/{notebook}.json"
    state_path = f"{APP}/files/library/states/{notebook}.json"
    capture, state = read(capture_path), read(state_path)
    if args.create_anchor:
        assert re.fullmatch("[A-Za-z0-9-]{1,100}", args.create_anchor)
        assert capture is None and state is None and args.expected_base == "", "New fixture already has a branch"
        anchor_path = f"{APP}/files/library/captures/boox-{args.create_anchor}.json"
        binding = read(anchor_path)
        assert binding and binding["title"].startswith("GDrive-Sync-Probe"), "Anchor is not a named probe"
        base = ""
    else:
        assert capture and state, "Existing probe baseline is missing"
        assert capture["title"].startswith("GDrive-Sync-Probe"), "Native capture is not a disposable fixture"
        assert state["enqueued"] and capture["fingerprint"] == state["fingerprint"], "Local changes pending"
        old = json.loads(state["revision"])
        base = hashlib.sha256(canonical(old)).hexdigest()
        assert base == args.expected_base, "Native branch changed"
        assert state["account"] == capture["account"] and state["folder"] == capture["folder"]
        binding = state
    payload_hash = hashlib.sha256(data).hexdigest()
    revision = dict(schema=1, notebook=notebook, device="mac-encoded-fixture-validator",
                    parents=[base] if base else [], payload=payload_hash, deleted=False)
    encoded = canonical(revision)
    revision_id = hashlib.sha256(encoded).hexdigest()
    queue = dict(schema=1, account=binding["account"], folder=binding["folder"],
                 revision=encoded.decode(), state="pending")
    # Prevent the live connector from observing a partially staged journal.
    root("am force-stop local.boox.notesdrive")
    assert read(state_path) == state and read(capture_path) == capture, "State changed before staging"
    if args.create_anchor:
        assert read(anchor_path) == binding, "Anchor changed before staging"
    uid = root(f"stat -c %u {APP}").strip()
    assert uid.isdigit() and int(uid) >= 10000
    with tempfile.TemporaryDirectory(prefix="boox-native-fixture-") as work:
        def install(data, destination, owner=uid):
            local = Path(work) / "stage"
            local.write_bytes(data)
            local.chmod(0o600)
            remote = "/data/local/tmp/boox-native-fixture-stage"
            subprocess.run(adb + ["push", str(local), remote], check=True, capture_output=True)
            temporary = destination + ".fixture-tmp"
            command = (f"cp {shlex.quote(remote)} {shlex.quote(temporary)} && "
                       f"chown {owner}:{owner} {shlex.quote(temporary)} && chmod 600 {shlex.quote(temporary)} && "
                       f"restorecon {shlex.quote(temporary)} && sync && "
                       f"mv {shlex.quote(temporary)} {shlex.quote(destination)} && sync && rm {shlex.quote(remote)}")
            root(command)
            assert root("cat " + shlex.quote(destination), binary=True) == data
        if args.fault_phase:
            phases = {"PREPARED", "DOCUMENT_REPLACED", "POINTS_REPLACED", "ROWS_CLEARED",
                      "HYDRATED", "VERIFIED", "ACKNOWLEDGED"}
            assert args.fault_phase in phases
            notes_root = "/data/user/0/com.onyx.android.note"
            notes_uid = root(f"stat -c %u {notes_root}").strip()
            install(canonical(dict(id=native_id, revision=revision_id, phase=args.fault_phase)),
                    notes_root + "/files/boox_drive_apply/validation-fault.json", notes_uid)
        install(data, f"{APP}/files/revisions/payloads/{payload_hash}.note")
        install(canonical(queue), f"{APP}/files/revisions/jobs/{revision_id}.json")
    report = dict(schema=1, native_id=native_id, base=base, revision=revision_id,
                  payload=payload_hash, fault_phase=args.fault_phase,
                  transport="Android app-owned Google grant; independently authored Mac fixture bytes",
                  native_application_verified=False)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    args.report.chmod(0o600)
    root("am start -n local.boox.notesdrive/.SetupActivity")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
