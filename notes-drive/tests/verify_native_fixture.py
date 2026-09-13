#!/usr/bin/env python3
"""Wait for a disposable native apply and retain its verified native re-export."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--publication", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    publication = json.loads(args.publication.read_text())
    revision, identity = publication["revision"], publication["native_id"]
    assert len(revision) == 64 and all(c in "0123456789abcdef" for c in revision)
    assert len(identity) <= 100 and identity.replace("-", "").isascii() and identity.replace("-", "").isalnum()
    adb = [str(ROOT / "tools/platform-tools/adb"), "-s", args.serial]
    native = "/data/user/0/com.onyx.android.note"
    job = native + "/files/boox_drive_apply/" + revision

    def root(command, binary=False):
        return subprocess.check_output(adb + ["exec-out", "/debug_ramdisk/su", "-c", command], text=not binary)

    def read(path):
        value = root("if [ -f " + path + " ]; then cat " + path + "; fi")
        return json.loads(value) if value.strip() else None

    for attempt in range(180):
        journal = read(job + "/journal.json")
        if journal and journal["phase"] == "COMMITTED":
            break
        assert not journal or journal["phase"] != "ROLLED_BACK", "Incoming fixture rolled back; retain evidence"
        if attempt % 15 == 0:
            root("input keyevent KEYCODE_WAKEUP")
            if not root("pidof com.onyx.android.note").strip():
                root("am start -n com.onyx.android.note/com.onyx.android.sdk.note.ui.library.ui.LibraryActivity")
        time.sleep(1)
    else:
        raise RuntimeError("Native apply timed out; incoming journal retained")
    state = read("/data/user/0/local.boox.notesdrive/files/library/states/boox-" + identity + ".json")
    assert hashlib.sha256(state["revision"].encode()).hexdigest() == revision
    data = root("cat " + job + "/readback.note", binary=True)
    spec = importlib.util.spec_from_file_location("native_readback_reader", ROOT / "notes-drive/macos/Resources/note_reader.py")
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    note = reader.inspect_bytes(data)
    assert note["document_id"] == identity and note["title"].startswith("GDrive-Sync-Probe")
    args.output.mkdir(parents=True, exist_ok=True)
    args.output.chmod(0o700)
    readback = args.output / "native-readback.note"
    readback.write_bytes(data)
    readback.chmod(0o600)
    report = dict(revision=revision, native_id=identity, phase=journal["phase"],
                  exact_branch_verified=True, native_semantic_verification_passed=True,
                  readback_sha256=hashlib.sha256(data).hexdigest(), pages=len(note["pages"]),
                  live_pen_strokes=sum(s["type"] == 2 and s["supported"]
                                       for page in note["pages"] for s in page["strokes"]))
    (args.output / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
