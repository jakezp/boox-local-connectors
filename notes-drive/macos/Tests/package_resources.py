#!/usr/bin/python3
"""Build-time staging of synthetic test notebooks and optional explicit config.

No sibling fixtures, previous bundles or implicit OAuth configuration are read.
The runtime verifies these resource bytes against their bundled manifest.
"""
import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import re

from synthetic_fixtures import fixture_bytes

RESOURCE_PROFILES = {"Target-after.note": "two-page.note", "B2.note": "two-page-variant.note"}


def exclusive_write(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)


def explicit_config(path):
    """Preserve exact requested bytes; never print values or rewrite identity."""
    if not path.is_file() or path.stat().st_size > 32768:
        raise ValueError("Explicit OAuth config must be a regular JSON file within 32 KiB")
    with path.open("rb") as source:
        data = source.read(32769)
    if len(data) > 32768:
        raise ValueError("Explicit OAuth config exceeds 32 KiB")
    try:
        value = json.loads(data)
    except (ValueError, UnicodeError):
        raise ValueError("Explicit OAuth config is not valid JSON") from None
    if not isinstance(value, dict) or "web" in value:
        raise ValueError("Explicit OAuth config must be a Desktop installed-client configuration")
    installed = value.get("installed", value)
    if not isinstance(installed, dict) or not isinstance(installed.get("client_id"), str) or not re.fullmatch(
            r"[A-Za-z0-9_-]+\.apps\.googleusercontent\.com", installed["client_id"]):
        raise ValueError("Explicit OAuth config needs a Desktop client ID")
    if len(installed["client_id"]) > 256:
        raise ValueError("Explicit OAuth client ID exceeds its limit")
    for key in ("client_secret", "project_id"):
        if key in installed and (not isinstance(installed[key], str) or not installed[key]):
            raise ValueError("Explicit OAuth field has the wrong type or is empty")
    return data


def stage(destination, oauth_config=None):
    destination = Path(destination)
    if not destination.is_dir():
        raise ValueError("Bundle resources directory must already exist")
    # Validate the requested config before creating any new resource file.
    config_bytes = explicit_config(Path(oauth_config)) if oauth_config is not None else None
    pins = json.loads((Path(__file__).parent / "synthetic-manifest.json").read_text())
    resources = {}
    pending = {}
    for output_name, profile in RESOURCE_PROFILES.items():
        data = fixture_bytes(profile)
        digest = sha256(data).hexdigest()
        if digest != pins[profile]["sha256"]:
            raise ValueError("Synthetic fixture differs from its pinned source manifest")
        pending[output_name] = data
        resources[output_name] = dict(profile=profile, sha256=digest, bytes=len(data))
    manifest = dict(schema=1, generator="synthetic_fixtures.py", synthetic_only=True, resources=resources)
    pending["synthetic-resources.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    if config_bytes is not None:
        pending["oauth-desktop.json"] = config_bytes
    # Staging is private to a new build; refuse reuse instead of replacing data.
    reserved = set(pending) | {"oauth-desktop.json"}
    if any((destination / name).exists() or (destination / name).is_symlink() for name in reserved):
        raise ValueError("Resource staging refuses to overwrite existing output")
    for name, data in pending.items():
        exclusive_write(destination / name, data)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", required=True)
    parser.add_argument("--oauth-config")
    args = parser.parse_args()
    try:
        stage(args.resources, args.oauth_config)
    except (ValueError, OSError, KeyError) as error:
        # No JSON input, secret, token, or config field values appear in errors.
        parser.exit(1, "Resource staging failed: " + type(error).__name__ + "\n")
    print("Staged generated notebook resources" + (" with explicitly requested private OAuth config" if args.oauth_config else " without OAuth config"))
