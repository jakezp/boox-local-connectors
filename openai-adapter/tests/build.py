#!/usr/bin/env python3
"""Build the separately installed, same-signature instrumentation test APK."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
tests = root / "tests"
build = tests / "build"
shutil.rmtree(build, ignore_errors=True)
(build / "classes").mkdir(parents=True)
sys.path.insert(0, str(root.parent / "tools"))
from android_build_env import java_home, sdk_paths
java = java_home()
sdk, bt, android = sdk_paths(root.parent)
env = dict(os.environ, JAVA_HOME=str(java))


def run(*args):
    subprocess.run([str(a) for a in args], env=env, check=True)


run(java / "bin/javac", "-source", "8", "-target", "8", "-Xlint:-options",
    "-cp", f"{android}:{root / 'build/classes'}", "-d", build / "classes",
    *sorted(tests.glob("*.java")))
# Bundle a snapshot of the transport/provider code into the test app. Instrument
# that app itself so checks cannot interrupt login or access production storage.
production = [
    path for path in sorted((root / "build/classes/local/boox/openai").rglob("*.class"))
    if not path.name.startswith(("NativeHook", "DisclaimerViews"))
]
run(bt / "d8", "--lib", android, "--classpath", root / "build/classes",
    "--min-api", "26", "--output", build, *production, *sorted((build / "classes").rglob("*.class")))
run(bt / "aapt", "package", "-f", "-M", tests / "AndroidManifest.xml", "-I", android,
    "-F", build / "unsigned.apk")
with zipfile.ZipFile(build / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
    apk.write(build / "classes.dex", "classes.dex")
run(bt / "zipalign", "-f", "4", build / "unsigned.apk", build / "aligned.apk")
run(bt / "apksigner", "sign", "--ks", root / "local-signing.p12", "--ks-pass",
    "pass:local-build-only", "--out", build / "validation.apk", build / "aligned.apk")
