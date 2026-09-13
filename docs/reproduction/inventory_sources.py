#!/usr/bin/env python3
"""Snapshot authored source paths/hashes only; never scan private asset contents.

Run after concurrent authors finish. This is an inventory, not a source secret
scanner or permission to publish the listed files. Writes only its own JSON.
"""

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GROUPS = {
    "host_tools": ["tools/*.py"],
    "ai_android": [
        "openai-adapter/build.py", "openai-adapter/AndroidManifest.xml",
        "openai-adapter/src/**/*.java", "openai-adapter/stubs/**/*.java",
        "openai-adapter/res/**/*.xml",
        "openai-adapter/assets/xposed_init", "openai-adapter/tests/*.java",
        "openai-adapter/tests/build.py", "openai-adapter/tests/AndroidManifest.xml",
    ],
    "notes_android": [
        "notes-drive/android/*.gradle", "notes-drive/android/gradle.properties",
        "notes-drive/android/build.py", "notes-drive/android/app/build.gradle",
        "notes-drive/android/app/src/**/*.java",
        "notes-drive/android/app/src/**/*.xml",
        "notes-drive/android/app/src/main/assets/xposed_init",
        "notes-drive/android/xposed-stubs/*.gradle",
        "notes-drive/android/xposed-stubs/src/**/*.java",
    ],
    "notes_macos": [
        "notes-drive/macos/Sources/*.swift", "notes-drive/macos/Tests/*.swift",
        "notes-drive/macos/Tests/*.py", "notes-drive/macos/Resources/*.py",
        "notes-drive/macos/Tests/protocol-vectors.json",
        "notes-drive/macos/Tests/synthetic-manifest.json",
        "notes-drive/macos/Tests/native-apply-manifest.json",
        "notes-drive/macos/*.sh",
    ],
    "historical_probes": [
        "notes-drive/probe/build.py", "notes-drive/probe/AndroidManifest.xml",
        "notes-drive/probe/assets/xposed_init", "notes-drive/probe/src/**/*.java",
        "notes-drive/apply-probe/build.py", "notes-drive/apply-probe/AndroidManifest.xml",
        "notes-drive/apply-probe/assets/xposed_init",
        "notes-drive/apply-probe/src/**/*.java",
    ],
    "prototype_and_validation": [
        "notes-drive/prototype/*.py", "notes-drive/tests/*.py",
        "notes-drive/tests/*.java", "notes-drive/tests/protocol-vectors.json",
    ],
    "legacy_documentation_review_required": [
        "*.md", ".gitignore", "docs/*.md", "openai-adapter/*.md", "notes-drive/*.md",
        "notes-drive/android/*.md", "notes-drive/macos/*.md",
        "notes-drive/.gitignore", "notes-drive/android/.gitignore",
        "notes-drive/macos/.gitignore",
    ],
    "reproduction": [
        "docs/reproduction/*.md", "docs/reproduction/*.py",
        "docs/reproduction/acquisition.json",
        "docs/reproduction/BACKUP-RECEIPT.example.json",
        "docs/reproduction/setup-checkpoint.json",
    ],
}


def collect(root):
    entries = []
    seen = set()
    for group, patterns in GROUPS.items():
        for pattern in patterns:
            for path in sorted(root.glob(pattern)):
                if not path.is_file() or path.is_symlink() or path in seen:
                    continue
                seen.add(path)
                data = path.read_bytes()
                entries.append({
                    "path": path.relative_to(root).as_posix(),
                    "group": group,
                    "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "publication_review_required": group != "reproduction",
                })
    return sorted(entries, key=lambda entry: entry["path"])


def main():
    entries = collect(ROOT)
    result = {
        "schema": 1,
        "checkpoint_date": "2026-09-13",
        "atomic_snapshot": False,
        "warning": "Sources may change concurrently; regenerate after final builds. "
                   "Listed source/legacy docs still require privacy and licensing review.",
        "excluded": [
            "Private signing material, OAuth configuration, grants and account/folder registration",
            "Personal notebooks, live evidence, screenshots, backups and app state",
            "Firmware/APKs/JARs/loader archives, decompiled proprietary sources",
            "Downloaded tools, virtual environments, build outputs and caches",
            "This generated inventory itself (avoids a self-referential hash)",
        ],
        "counts": {group: sum(entry["group"] == group for entry in entries) for group in GROUPS},
        "files": entries,
    }
    destination = Path(__file__).with_name("source-inventory.json")
    destination.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"file_count": len(entries), "groups": result["counts"]}, indent=2))


if __name__ == "__main__":
    main()
