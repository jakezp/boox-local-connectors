#!/usr/bin/env python3
"""Build with a stable local Android OAuth signing identity."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
from android_build_env import java_home, sdk_paths, gradle_path


def source_digest():
    digest = hashlib.sha256()
    for path in sorted((ROOT / "app/src/main/java").rglob("*.java")):
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def build():
    java = java_home()
    sdk, build_tools, _ = sdk_paths(PROJECT)
    gradle = gradle_path(PROJECT)
    key = ROOT / "local-signing.p12"
    if not key.exists():
        subprocess.run([
            str(java / "bin/keytool"), "-genkeypair", "-keystore", str(key),
            "-storepass", "local-build-only", "-keypass", "local-build-only",
            "-alias", "notesdrive", "-keyalg", "RSA", "-keysize", "3072",
            "-validity", "3650", "-dname", "CN=Local BOOX Notes Drive",
        ], check=True)
        key.chmod(0o600)
    assets = ROOT / "app/src/main/assets"
    assets.mkdir(parents=True, exist_ok=True)
    fixtures = ROOT / "app/build/generated/synthetic-fixtures"
    subprocess.run([sys.executable, str(PROJECT / "notes-drive/tests/generate_build_fixtures.py"),
                    "--output", str(fixtures)], check=True)
    fixture = fixtures / "connection-test.note"
    (assets / "connection-test.note").write_bytes(fixture.read_bytes())
    (ROOT / "local.properties").write_text(f"sdk.dir={sdk}\n")
    env = {**os.environ, "JAVA_HOME": str(java), "ANDROID_HOME": str(sdk)}
    source_before = source_digest()
    hook_build = source_before[:16]
    subprocess.run([
        str(gradle),
        "--no-daemon", "--console=plain", "-PhookBuild=" + hook_build,
        "testReleaseUnitTest", "assembleRelease",
    ], cwd=ROOT, env=env, check=True)
    if source_digest() != source_before:
        raise RuntimeError("Native source changed during the build; rerun before installation")
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    verified = subprocess.check_output([
        str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk),
    ], env=env, text=True)
    fingerprint = re.search(r"certificate SHA-1 digest: ([0-9a-f]+)", verified).group(1)
    registration = {
        "application_type": "Android", "package_name": "local.boox.notesdrive",
        "signing_certificate_sha1": ":".join(fingerprint[i:i + 2].upper()
                                             for i in range(0, len(fingerprint), 2)),
        "scope": "https://www.googleapis.com/auth/drive.file",
        "version": "0.4", "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "hook_build": hook_build,
        "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
    }
    (ROOT / "registration.json").write_text(json.dumps(registration, indent=2))
    print(verified)
    print(json.dumps(registration, indent=2))


if __name__ == "__main__":
    build()
