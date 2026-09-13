#!/usr/bin/env python3
"""Install Notes Drive and verify the actual Vector-loaded hook generation.

Close every native Notes editor before using --notes-closed. App data, Google
grants and the OpenAI module are preserved. No root/flashing operation is performed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
VECTOR = "/data/adb/modules/zygisk_vector/cli"
LIBRARY = "com.onyx.android.note/com.onyx.android.sdk.note.ui.library.ui.LibraryActivity"
LAUNCHER = "com.onyx/com.onyx.tablet.main.ui.TabletMainActivity"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--notes-closed", action="store_true", required=True)
    p.add_argument("--readers-closed", action="store_true", required=True)
    p.add_argument("--report", type=Path)
    args = p.parse_args()
    adb = [str(ROOT / "tools/platform-tools/adb"), "-s", args.serial]
    registration = json.loads((ROOT / "notes-drive/android/registration.json").read_text())
    apk = ROOT / "notes-drive/android/app/build/outputs/apk/release/app-release.apk"
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == registration["apk_sha256"], "APK changed since build"
    expected = registration["hook_build"]
    assert re.fullmatch("[0-9a-f]{16}", expected)

    def root(command):
        return subprocess.check_output(adb + ["exec-out", "/debug_ramdisk/su", "-c", command], text=True)

    assert root("id -u").strip() == "0", "Root is required"
    info = root("dumpsys package com.onyx.android.note")
    assert re.search(r"versionCode=45326\b", info), "Only inspected Notes45326 is supported"
    launcher_info = root("dumpsys package com.onyx")
    assert re.search(r"versionCode=56737\b", launcher_info), "Only inspected launcher56737 is supported"
    reader_info = root("dumpsys package com.onyx.kreader")
    assert re.search(r"versionCode=38701\b", reader_info), "Only inspected NeoReader38701 is supported"
    scopes = set(re.findall(r"^([A-Za-z][\w.]+)\s+(\d+)\s*$",
                            root(VECTOR + " scope ls local.boox.notesdrive"), re.MULTILINE))
    assert scopes == {("com.onyx.android.note", "0"), ("com.onyx", "0"), ("com.onyx.kreader", "0")}, \
        "Drive requires Notes, launcher and NeoReader scopes; review the scope migration first"
    root("am force-stop com.onyx.android.note")
    root("am force-stop com.onyx.kreader")
    disabled = root(VECTOR + " modules disable local.boox.notesdrive")
    assert "Failed: []" in disabled, disabled
    time.sleep(5)  # Vector rebuilds asynchronously; do not coalesce disable and enable.
    try:
        subprocess.run(adb + ["install", "-r", str(apk)], check=True)
        installed = root("pm path local.boox.notesdrive").strip().removeprefix("package:")
        assert installed.startswith("/data/app/") and installed.endswith("/base.apk")
        assert root("sha256sum " + installed).split()[0] == registration["apk_sha256"]
    finally:
        enabled = root(VECTOR + " modules enable local.boox.notesdrive")
        assert "Failed: []" in enabled, enabled
    time.sleep(5)
    marker = "Native sync adapter v0.4 build " + expected + " ready for Notes 45326"
    logs = ""
    for launch in range(2):
        root("am start -n " + LIBRARY)
        root("am start -n local.boox.notesdrive/.SetupActivity")
        for attempt in range(30):
            pid = root("pidof com.onyx.android.note").strip()
            if pid.isdigit():
                logs = subprocess.check_output(adb + ["logcat", "-d", "--pid=" + pid, "-s", "BooxNotesDrive:I"], text=True)
                if marker in logs:
                    break
            time.sleep(0.5)
        if marker in logs:
            break
        # On this firmware, the first library process can start without any module
        # while Vector's changed scope becomes active. No editor was opened here.
        assert "ready for Notes 45326" not in logs, "A stale hook loaded; keep its recovery state and investigate"
        root("am force-stop com.onyx.android.note")
    assert marker in logs, "Installed APK verified, but expected native hook did not load; do not assume success"
    launcher_marker = "Launcher Notes Settings build " + expected + " ready for launcher 56737"
    root("am force-stop com.onyx")
    root("am start -n " + LAUNCHER)
    launcher_logs = ""
    for attempt in range(40):
        launcher_pid = root("pidof com.onyx").strip()
        if launcher_pid.isdigit():
            launcher_logs = subprocess.check_output(
                adb + ["logcat", "-d", "--pid=" + launcher_pid, "-s", "BooxNotesDrive:I"], text=True)
            if launcher_marker in launcher_logs:
                break
        time.sleep(0.5)
    assert launcher_marker in launcher_logs, "Updated launcher Settings hook did not load"
    # Querying this existing provider starts NeoReader without opening a book.
    reader_marker = "Native Reader build " + expected + " ready for NeoReader 38701"
    reader_logs = ""
    for launch in range(2):
        root("content query --uri content://com.onyx.kreader.feature_list.ContentProvider")
        for attempt in range(40):
            reader_pid = root("pidof com.onyx.kreader").strip()
            if reader_pid.isdigit():
                reader_logs = subprocess.check_output(adb + ["logcat", "-d", "--pid=" + reader_pid, "-s", "BooxNotesDrive:I"], text=True)
                if reader_marker in reader_logs:
                    break
            time.sleep(0.5)
        if reader_marker in reader_logs:
            break
        assert "ready for NeoReader 38701" not in reader_logs, "A stale Reader hook loaded; investigate before continuing"
        root("am force-stop com.onyx.kreader")
    assert reader_marker in reader_logs, "Updated Reader hook did not load"
    report = dict(schema=1, reader_version=38701, reader_pid=reader_pid, reader_marker=reader_marker, apk_sha256=registration["apk_sha256"], hook_build=expected,
                  native_pid=pid, loaded_marker=marker, notes_version=45326,
                  launcher_pid=launcher_pid, launcher_version=56737, launcher_marker=launcher_marker,
                  grants_preserved=True, other_modules_changed=False)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
