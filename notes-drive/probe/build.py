#!/usr/bin/env python3
"""Build the isolated fixture probe using existing local SDK/Xposed headers."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
from android_build_env import java_home, sdk_paths
SDK, TOOLS, ANDROID = sdk_paths(PROJECT)
JAVA = java_home()
BUILD = ROOT / "build"


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT,
                   env={**os.environ, "JAVA_HOME": str(JAVA)}, check=True)


def build():
    BUILD.mkdir(exist_ok=True)
    classes = BUILD / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    headers = PROJECT / "openai-adapter/stubs"
    run(JAVA / "bin/javac", "-source", "8", "-target", "8", "-Xlint:-options",
        "-cp", ANDROID, "-d", classes, *sorted((ROOT / "src").rglob("*.java")),
        *sorted(headers.rglob("*.java")))
    run(TOOLS / "d8", "--lib", ANDROID, "--classpath", classes, "--min-api", "26",
        "--output", BUILD, *sorted((classes / "local").rglob("*.class")))
    run(TOOLS / "aapt", "package", "-f", "-M", ROOT / "AndroidManifest.xml",
        "-A", ROOT / "assets", "-I", ANDROID, "-F", BUILD / "unsigned.apk")
    with ZipFile(BUILD / "unsigned.apk", "a", ZIP_DEFLATED) as apk:
        apk.write(BUILD / "classes.dex", "classes.dex")
    run(TOOLS / "zipalign", "-f", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk")
    keystore = ROOT / "local-signing.p12"
    if not keystore.exists():
        run(JAVA / "bin/keytool", "-genkeypair", "-keystore", keystore,
            "-storepass", "local-build-only", "-keypass", "local-build-only",
            "-alias", "probe", "-keyalg", "RSA", "-keysize", "3072",
            "-validity", "3650", "-dname", "CN=Local BOOX Notes Probe")
        keystore.chmod(0o600)
    run(TOOLS / "apksigner", "sign", "--ks", keystore,
        "--ks-pass", "pass:local-build-only", "--out", BUILD / "notes-probe.apk",
        BUILD / "aligned.apk")
    run(TOOLS / "apksigner", "verify", "--verbose", BUILD / "notes-probe.apk")


if __name__ == "__main__":
    build()
