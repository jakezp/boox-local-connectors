"""Explicit live crash tests. Not included in unittest discovery.

Usage: python3 .../test_device_apply.py fresh-run-prefix
Requires the disposable native fixtures and candidate already prepared.
"""

from hashlib import sha256
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys

import adb_apply as device


ROOT = Path(__file__).resolve().parents[1]
CANDIDATE = ROOT / "research/apply/candidate"


def journal(job):
    return json.loads(device.boox_ui.shell(
        "/debug_ramdisk/su -c " + shlex.quote(f"cat {device.REMOTE}/{job}/journal.json")))


def command(action, job, extra=None, expected=None):
    result = device.run(action, job, extra, check=False)
    if expected is None:
        if result.returncode:
            raise RuntimeError(f"{action}: {result.stderr or result.stdout}")
    elif expected not in result.stdout + result.stderr:
        raise RuntimeError(f"Expected {expected}: {result.returncode} {result.stdout} {result.stderr}")
    return result


def overwrite(remote, data, job):
    local = ROOT / "research/apply" / f"{job}-bytes"
    local.write_bytes(data)
    uploaded = f"/data/local/tmp/boox-apply-test-bytes-{job}"
    subprocess.run(device.boox_ui.ADB + ["push", str(local), uploaded],
                   check=True, capture_output=True)
    shell = f"dd if={uploaded} of={shlex.quote(remote)} conv=notrunc"
    device.boox_ui.shell("/debug_ramdisk/su -c " + shlex.quote(shell))
    device.boox_ui.shell("rm -- " + uploaded)
    local.unlink()


def run(prefix):
    results = []
    report = ROOT / "research/apply" / f"{prefix}-results.json"
    plan = json.loads((CANDIDATE / "plan.json").read_text())

    def record(name, job, **details):
        state = journal(job)
        if state["appliedRevision"] != state["baseRevision"]:
            raise AssertionError("An unverified revision was acknowledged")
        results.append({"test": name, "job": job, "state": state["state"],
                        "applied_revision_unchanged": True, **details})
        report.write_text(json.dumps(results, indent=2))
        print("PASS", name, flush=True)

    forward = ["journal-applying"]
    forward += [f"new-{part}-{step}" for part in device_parts()
                for step in ("old-moved", "new-moved")]
    forward += ["metadata-written", "awaiting-verification"]
    for index, point in enumerate(forward):
        job = f"{prefix}-forward-{index}"
        device.push_job(CANDIDATE, job)
        command("prepare", job)
        result = command("apply", job, point, expected="STOP_AT " + point)
        if result.returncode != 137:
            raise AssertionError("Expected actual process termination")
        command("recover", job)
        command("recover", job)  # Recovery is idempotent.
        record(point, job, process_killed=True, repeated_recovery=True)

    backward = ["journal-rolling-back"]
    backward += [f"old-{part}-{step}" for part in device_parts()
                 for step in ("old-moved", "new-moved")]
    backward += ["rollback-metadata-written"]
    for index, point in enumerate(backward):
        job = f"{prefix}-rollback-{index}"
        device.push_job(CANDIDATE, job)
        command("prepare", job)
        command("apply", job, "metadata-written", expected="STOP_AT metadata-written")
        command("recover", job, point, expected="STOP_AT " + point)
        command("recover", job)
        record(point, job, recovery_process_killed=True)

    job = f"{prefix}-late-edit"
    device.push_job(CANDIDATE, job)
    command("prepare", job)
    command("apply", job)
    point_file = next((CANDIDATE / "new/point").rglob("*#points"))
    remote = "/data/media/0/.ksync/point/" + plan["document"] + "/" + str(
        point_file.relative_to(CANDIDATE / "new/point"))
    original = point_file.read_bytes()
    changed = bytearray(original)
    changed[88] ^= 1
    overwrite(remote, changed, job)
    command("recover", job, expected="Recovery paused: notebook content changed")
    actual = subprocess.check_output(device.boox_ui.ADB + ["exec-out", "/debug_ramdisk/su",
                                                          "-c", "cat " + shlex.quote(remote)])
    if actual != changed:
        raise AssertionError("Recovery overwrote an unknown edit")
    overwrite(remote, original, job)
    command("recover", job)
    record("unknown edit preserved", job, unknown_edit_preserved=True)

    job = f"{prefix}-unverified"
    device.push_job(CANDIDATE, job)
    command("prepare", job)
    command("apply", job)
    command("ack", job, expected="verification.json")
    command("recover", job)
    record("readback proof required", job)

    job = f"{prefix}-damaged-backup"
    device.push_job(CANDIDATE, job)
    command("prepare", job)
    old_database = (ROOT / "research/apply/target-snapshot/database").read_bytes()
    changed = bytearray(old_database)
    changed[0] ^= 1
    remote = f"{device.REMOTE}/{job}/old/database"
    overwrite(remote, changed, job)
    command("apply", job, expected="Saved component checksum mismatch")
    command("recover", job, expected="Saved component checksum mismatch")
    overwrite(remote, old_database, job)
    command("recover", job)
    record("damaged backup blocks apply and recovery", job)

    for name, change in (
        ("stale-baseline", lambda p: p["expectedRow"].update(updatedAt=p["expectedRow"]["updatedAt"] + 1)),
        ("identity-change", lambda p: p["afterRow"].update(title="GDrive-Sync-Probe-Wrong")),
    ):
        local = ROOT / "research/apply" / f"{prefix}-{name}"
        shutil.copytree(CANDIDATE, local)
        modified = json.loads((local / "plan.json").read_text())
        change(modified)
        (local / "plan.json").write_text(json.dumps(modified))
        job = f"{prefix}-{name}"
        device.push_job(local, job)
        expected = "Local metadata changed" if name == "stale-baseline" else "Attempt to change local identity"
        command("prepare", job, expected=expected)
        results.append({"test": name, "job": job, "rejected_before_mutation": True})
        report.write_text(json.dumps(results, indent=2))
        print("PASS", name, flush=True)
    return results


def device_parts():
    return ("database", "document", "point")


if __name__ == "__main__":
    run(sys.argv[1])
