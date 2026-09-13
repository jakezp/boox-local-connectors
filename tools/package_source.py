#!/usr/bin/env python3
"""Plan or export explicitly inventoried source; never discover private inputs.

Default: deterministic JSON plan on stdout. Export is a local, uncompressed tar
to a new path, gated on an identical reviewed plan and explicit content review.
No build, subprocess, device, credential provisioning, network or Git operations.
"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import sys
import tarfile


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = "docs/reproduction/source-inventory.json"
MAX_FILE = 16 * 1024 * 1024
MAX_TOTAL = 64 * 1024 * 1024
MAX_FILES = 5000

# Additional boundary checks for the explicit inventory, not recursive discovery.
SOURCE_RULES = (
    ("openai-adapter/src/", {".java"}),
    ("openai-adapter/stubs/", {".java"}),
    ("openai-adapter/res/", {".xml"}),
    ("notes-drive/android/app/src/", {".java", ".xml"}),
    ("notes-drive/android/xposed-stubs/src/", {".java"}),
    ("notes-drive/probe/src/", {".java"}),
    ("notes-drive/apply-probe/src/", {".java"}),
)
DIRECT_RULES = {
    "openai-adapter/tests": {".java"},
    "notes-drive/macos/Sources": {".swift"},
    "notes-drive/macos/Tests": {".swift", ".py"},
    "notes-drive/macos/Resources": {".py"},
    "notes-drive/macos": {".sh"},
    "notes-drive/prototype": {".py"},
    "notes-drive/tests": {".py", ".java"},
    "docs/reproduction": {".md", ".py"},
}
DOC_DIRS = {
    ".", "docs", "openai-adapter", "notes-drive", "notes-drive/android",
    "notes-drive/macos",
}
EXACT = {
    ".gitignore",
    "tools/boox_doctor.py", "tools/test_boox_doctor.py",
    "tools/boox_setup.py", "tools/test_boox_setup.py",
    "tools/install_notes_drive.py", "tools/build_ams_fix.py",
    "tools/boox_ui.py", "tools/test_boox_ui.py", "tools/run_edl.py",
    "tools/repair_adb_tar.py", "tools/test_repair_adb_tar.py",
    "tools/package_source.py", "tools/test_package_source.py",
    "tools/android_build_env.py",
    "tools/bootstrap_dependencies.py", "tools/test_bootstrap_dependencies.py",
    "tools/prepare_magisk_stage.py", "tools/test_prepare_magisk_stage.py",
    "openai-adapter/build.py", "openai-adapter/AndroidManifest.xml",
    "openai-adapter/assets/xposed_init", "openai-adapter/tests/build.py",
    "openai-adapter/tests/AndroidManifest.xml",
    "notes-drive/android/build.py", "notes-drive/android/gradle.properties",
    "notes-drive/android/build.gradle", "notes-drive/android/settings.gradle",
    "notes-drive/android/app/build.gradle",
    "notes-drive/android/xposed-stubs/build.gradle",
    "notes-drive/android/app/src/main/assets/xposed_init",
    "notes-drive/macos/Tests/protocol-vectors.json",
    "notes-drive/macos/Tests/synthetic-manifest.json",
    "notes-drive/macos/Tests/native-apply-manifest.json",
    "notes-drive/tests/protocol-vectors.json",
    "notes-drive/.gitignore", "notes-drive/android/.gitignore",
    "notes-drive/macos/.gitignore",
    "docs/reproduction/acquisition.json",
    "docs/reproduction/BACKUP-RECEIPT.example.json",
    "docs/reproduction/setup-checkpoint.json",
}
for _probe in ("probe", "apply-probe"):
    EXACT.update({
        f"notes-drive/{_probe}/build.py",
        f"notes-drive/{_probe}/AndroidManifest.xml",
        f"notes-drive/{_probe}/assets/xposed_init",
    })

DENIED_COMPONENTS = {
    "backups", "research", "artifacts", "evidence", "build", "dist", "vendor",
    "node_modules", "credentials", "grants", "keys", "secrets", "__pycache__",
}
DENIED_NAME_FRAGMENTS = (
    "decompiled", "local-signing", "client_secret", "privatekey", "private-key",
)

# Findings include rule and line only. No matching value or source excerpt leaves
# this process. These checks supplement mandatory human privacy/license review.
CONTENT_RULES = {
    "private_key_material": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "api_key_literal": re.compile(r"\bsk-[A-Za-z0-9_-]{24,}"),
    "google_api_key_literal": re.compile(r"\bAIza[A-Za-z0-9_-]{30,}"),
    "oauth_client_identity": re.compile(r"\b[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com"),
    "user_home_path": re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+/"),
    "personal_email_candidate": re.compile(
        r"\b[A-Za-z0-9._%+-]+@(?!example\.(?:com|org|net)\b)[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
    "drive_private_link": re.compile(r"https://drive\.google\.com/(?:drive/folders|file/d)/[A-Za-z0-9_-]{12,}"),
    "device_serial_literal": re.compile(
        r"\b(?:SERIAL|serial)\s*[:=]\s*[\"']?[a-f0-9]{8,16}\b"),
    "adb_serial_argument": re.compile(r"""["']-s["']\s*,\s*["'][a-f0-9]{8,16}["']"""),
    "adb_serial_command": re.compile(r"\badb\s+-s\s+[a-f0-9]{8,16}\b"),
    "drive_folder_binding": re.compile(
        r"""\bknownFolderID\s*=\s*["'][A-Za-z0-9_-]{16,}["']"""),
    "google_project_binding": re.compile(
        r"""\bproject\s*==\s*["'][a-z][a-z0-9-]{8,}["']"""),
    "bearer_literal": re.compile(r"\bBearer [A-Za-z0-9_.-]{24,}"),
}


class PackageError(ValueError):
    """Fixed, non-sensitive diagnostic suitable for stdout/stderr."""


def canonical(value):
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def allowed(name):
    if not isinstance(name, str) or not name or len(name) > 400:
        return False
    parts = name.split("/")
    if any(not re.fullmatch(r"[A-Za-z0-9_.-]+", part) or part in {".", ".."}
           for part in parts):
        return False
    for part in parts:
        lower = part.lower()
        if (lower in DENIED_COMPONENTS or lower.endswith(".app")
                or (part.startswith(".") and part != ".gitignore")
                or any(fragment in lower for fragment in DENIED_NAME_FRAGMENTS)):
            return False
    path = Path(name)
    parent, suffix = path.parent.as_posix(), path.suffix
    if name in EXACT:
        return True
    if parent in DOC_DIRS and suffix == ".md":
        return True
    if suffix in DIRECT_RULES.get(parent, set()):
        return True
    return any(name.startswith(prefix) and suffix in extensions
               for prefix, extensions in SOURCE_RULES)


def read_regular(root, name):
    """Open relative to a pinned root, refusing symlinks at every component."""
    parts = name.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise PackageError("Invalid relative input path")
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                            dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                     dir_fd=directory)
        with os.fdopen(fd, "rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode):
                raise PackageError("Input is not a regular file")
            if before.st_size > MAX_FILE:
                raise PackageError("Input exceeds size limit")
            data = stream.read(MAX_FILE + 1)
            after = os.fstat(stream.fileno())
            if (len(data) > MAX_FILE or before.st_size != len(data)
                    or (before.st_mtime_ns, before.st_ctime_ns)
                    != (after.st_mtime_ns, after.st_ctime_ns)):
                raise PackageError("Input changed during read")
            return data
    finally:
        os.close(directory)


def load_inventory(root):
    try:
        raw = read_regular(root, INVENTORY)
        inventory = json.loads(raw)
    except (OSError, ValueError):
        raise PackageError("Inventory is unreadable or invalid") from None
    if not isinstance(inventory, dict) or inventory.get("schema") != 1:
        raise PackageError("Unsupported inventory schema")
    entries = inventory.get("files")
    if not isinstance(entries, list) or not 0 < len(entries) <= MAX_FILES:
        raise PackageError("Invalid inventory file count")
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict) or not allowed(entry.get("path")):
            raise PackageError("Inventory contains a disallowed source path")
        name = entry["path"]
        # Case collisions are unsafe on common macOS destination filesystems.
        if name.casefold() in seen:
            raise PackageError("Inventory contains duplicate or case-colliding paths")
        seen.add(name.casefold())
        if (not isinstance(entry.get("sha256"), str)
                or not re.fullmatch("[0-9a-f]{64}", entry["sha256"])
                or type(entry.get("bytes")) is not int
                or not 0 <= entry["bytes"] <= MAX_FILE):
            raise PackageError("Inventory contains invalid size or digest")
    # Validate the complete path list BEFORE opening any inventoried file.
    return sorted(entries, key=lambda item: item["path"]), raw


def plan_source(root):
    entries, inventory_bytes = load_inventory(root)
    # The generator excludes its own JSON to avoid a self-referential digest.
    # Include those exact validated bytes so the exported checkout can plan again.
    entries.append({"path": INVENTORY, "bytes": len(inventory_bytes),
                    "sha256": sha(inventory_bytes)})
    entries.sort(key=lambda item: item["path"])
    findings, files, payloads = [], [], {}
    total = 0
    for entry in entries:
        name = entry["path"]
        try:
            data = inventory_bytes if name == INVENTORY else read_regular(root, name)
        except (OSError, PackageError):
            findings.append({"path": name, "rule": "unreadable_or_unsafe_input"})
            continue
        total += len(data)
        if total > MAX_TOTAL:
            raise PackageError("Combined source exceeds size limit")
        digest = sha(data)
        if digest != entry["sha256"] or len(data) != entry["bytes"]:
            findings.append({"path": name, "rule": "inventory_digest_or_size_mismatch"})
        try:
            text = data.decode("utf-8")
            if any(ord(char) < 32 and char not in "\n\r\t" for char in text):
                raise ValueError("binary")
        except (UnicodeDecodeError, ValueError):
            findings.append({"path": name, "rule": "non_text_source"})
        else:
            for rule, pattern in CONTENT_RULES.items():
                for match in pattern.finditer(text):
                    if rule == "personal_email_candidate":
                        domain = match.group().split("@")[-1].lower()
                        if domain.endswith((".invalid", ".test", ".example", ".localhost")):
                            continue
                    findings.append({"path": name, "rule": rule,
                                     "line": text.count("\n", 0, match.start()) + 1})
        files.append({"path": name, "bytes": len(data), "sha256": digest,
                      "mode": "0755" if name.endswith(".sh") else "0644"})
        payloads[name] = data
    result = {
        "schema": 1,
        "status": "blocked" if findings else "ready_for_content_review",
        "file_count": len(files),
        "inventory_file_count": len(entries) - 1,
        "files": files,
        "findings": sorted(findings, key=lambda row: (row["path"], row.get("line", 0), row["rule"])),
        "boundaries": {
            "private_input_paths_excluded": True,
            "automatic_sanitization": False,
            "privacy_and_license_review_required": True,
            "fresh_build_verified": False,
            "git_or_upload_performed": False,
        },
    }
    # Identity binds policy/runtime semantics as well as source bytes.
    result["packager_sha256"] = sha(Path(__file__).read_bytes())
    result["plan_id"] = sha(canonical(result))
    return result, payloads


def write_new(path, data):
    """Create mode-0600 output without replacing an existing file."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)


def export_tar(plan, payloads, destination):
    if plan["status"] != "ready_for_content_review":
        raise PackageError("Export refused: resolve all plan findings first")
    # Assemble entirely from the checked byte snapshot; never reread source.
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for entry in plan["files"]:
            info = tarfile.TarInfo(entry["path"])
            info.size = len(payloads[entry["path"]])
            info.mode = int(entry["mode"], 8)
            info.mtime = info.uid = info.gid = 0
            info.uname = info.gname = ""
            archive.addfile(info, io.BytesIO(payloads[entry["path"]]))
    write_new(destination, buffer.getvalue())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--write-plan", type=Path, help="Write plan to a NEW file; default is stdout only")
    parser.add_argument("--export", type=Path, help="Explicitly export a deterministic tar to a NEW file")
    parser.add_argument("--reviewed-plan", type=Path)
    parser.add_argument("--content-reviewed", action="store_true",
                        help="Attest all selected content, privacy and licensing were reviewed")
    args = parser.parse_args(argv)
    if args.export and (not args.reviewed_plan or not args.content_reviewed):
        parser.error("--export requires --reviewed-plan and --content-reviewed")
    if not args.export and (args.reviewed_plan or args.content_reviewed):
        parser.error("Review options require --export")
    try:
        root = args.root.absolute()
        plan, payloads = plan_source(root)
        for output, is_export in ((args.write_plan, False), (args.export, True)):
            if output and output.resolve().is_relative_to(root.resolve()):
                # The one permitted workspace output is an excluded plan artifact.
                expected = root / "docs/reproduction/source-package-plan.json"
                if is_export or output.absolute() != expected:
                    raise PackageError("Output must be outside source root (except the packaging plan)")
        if args.export:
            try:
                reviewed = json.loads(args.reviewed_plan.read_bytes())
            except (OSError, ValueError):
                raise PackageError("Reviewed plan is unreadable or invalid") from None
            if reviewed != plan:
                raise PackageError("Reviewed plan differs from current source and policy")
            export_tar(plan, payloads, args.export)
        if args.write_plan:
            write_new(args.write_plan, canonical(plan))
        sys.stdout.write(canonical(plan).decode())
        return 1 if plan["findings"] else 0
    except (PackageError, OSError, ValueError):
        # OS exception messages can contain paths or values; never echo them.
        sys.stderr.write("Source packaging refused: invalid/unsafe input, unresolved findings, "
                         "changed review, or existing/unsafe output. No Git/upload occurred.\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
