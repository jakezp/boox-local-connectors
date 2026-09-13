#!/usr/bin/env python3
"""Small ADB helper for validating the native assistant with synthetic prompts."""
from pathlib import Path
import os
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REMOTE = "/data/local/tmp/boox-validation-ui.xml"
LOCAL = Path("/tmp/boox-validation-ui.xml")


def adb_command():
    serial = os.environ.get("BOOX_SERIAL", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]*", serial):
        raise RuntimeError("Set BOOX_SERIAL to the intended device before using the UI helper.")
    return [str(ROOT / "tools/platform-tools/adb"), "-s", serial]


def shell(command):
    return subprocess.check_output(adb_command() + ["shell", command], text=True)


def dump():
    # Never act on an old hierarchy when Android cannot produce a fresh one.
    shell(f"rm -f {REMOTE}")
    shell(f"uiautomator dump {REMOTE}")
    subprocess.run(adb_command() + ["pull", REMOTE, str(LOCAL)], check=True, capture_output=True)
    LOCAL.chmod(0o600)
    return list(ET.parse(LOCAL).iter("node"))


def tap(resource_id):
    nodes = [n for n in dump() if n.get("resource-id", "").endswith("/" + resource_id)]
    if len(nodes) != 1:
        raise RuntimeError(f"Expected one {resource_id}; found {len(nodes)}")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
    shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "dump":
        for node in dump():
            a = node.attrib
            if a.get("text") or a.get("content-desc") or a.get("clickable") == "true":
                print(a.get("resource-id"), repr(a.get("text")), a.get("bounds"))
    elif command == "tap":
        tap(sys.argv[2])
    elif command == "send":
        tap("et_question")
        shell("input text " + shlex.quote(sys.argv[2].replace(" ", "%s")))
        shell("input keyevent 4")
        tap("btn_send")
    elif command == "screenshot":
        adb = adb_command()
        with Path(sys.argv[2]).open("wb") as output:
            subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=output, check=True)
    else:
        raise SystemExit("Use dump, tap RESOURCE_ID, send TEXT, or screenshot PATH")
