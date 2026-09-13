#!/usr/bin/env python3
"""Run production native-format validation offline against supplied .note files."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from android_build_env import java_home


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("notebooks", type=Path, nargs="+")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    java = java_home() / "bin"
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    jars = list((cache / "caches/modules-2/files-2.1/org.json/json/20240303").rglob("*.jar"))
    if len(jars) != 1:
        raise SystemExit("Build the Android connector first to resolve its pinned org.json dependency.")
    source = ROOT / "notes-drive/android/app/src/main/java/local/boox/notesdrive"
    with tempfile.TemporaryDirectory(prefix="native-format-check-") as directory:
        subprocess.run([str(java / "javac"), "-cp", str(jars[0]), "-d", directory,
                        *[str(source / (name + ".java")) for name in
                          ("DriveClient", "Revision", "NativeSnapshot", "NativeArchive")],
                        str(ROOT / "notes-drive/tests/NativeArchiveCheck.java")], check=True)
        checked = subprocess.run([str(java / "java"), "-cp", directory + os.pathsep + str(jars[0]),
                                  "local.boox.notesdrive.NativeArchiveCheck",
                                  *map(str, args.notebooks)], text=True, capture_output=True)
        results = json.loads(checked.stdout)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(results, indent=2) + "\n")
            args.report.chmod(0o600)
        print(json.dumps(results, indent=2))
        raise SystemExit(checked.returncode)


if __name__ == "__main__":
    main()
