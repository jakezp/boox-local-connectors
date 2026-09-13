#!/bin/bash
set -euo pipefail
MAC_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$MAC_DIR/.test-runs"
RUN_DIR="$(mktemp -d "$MAC_DIR/.test-runs/run.XXXXXX")"
chmod 700 "$RUN_DIR"
export PYTHONDONTWRITEBYTECODE=1
export BOOX_MAC_TEST_FIXTURE_DIR="$RUN_DIR/fixtures"
export BOOX_MAC_TEST_BUNDLE_RESOURCES_DIR="$RUN_DIR/bundled-fixtures"
mkdir "$BOOX_MAC_TEST_BUNDLE_RESOURCES_DIR"
printf 'Disposable test output: %s\n' "$RUN_DIR"
/usr/bin/python3 "$MAC_DIR/Tests/synthetic_fixtures.py" --output "$BOOX_MAC_TEST_FIXTURE_DIR" > "$RUN_DIR/synthetic-manifest.json"
/usr/bin/python3 "$MAC_DIR/Tests/package_resources.py" --resources "$BOOX_MAC_TEST_BUNDLE_RESOURCES_DIR" > "$RUN_DIR/resource-packaging.log"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 "$MAC_DIR/Tests/test_synthetic.py"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 "$MAC_DIR/Tests/test_packaging.py"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 "$MAC_DIR/Tests/test_native_apply_fixtures.py"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 "$MAC_DIR/Tests/test_reader.py"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 "$MAC_DIR/Tests/test_editor.py"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 "$MAC_DIR/Tests/test_library.py"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 "$MAC_DIR/Tests/test_nested_metadata.py"
xcrun swiftc -swift-version 5 -parse-as-library -target arm64-apple-macosx13.0 \
    "$MAC_DIR/Sources/Models.swift" "$MAC_DIR/Sources/Revision.swift" \
    "$MAC_DIR/Sources/HTTP.swift" "$MAC_DIR/Sources/OAuth.swift" \
    "$MAC_DIR/Sources/Drive.swift" "$MAC_DIR/Sources/LocalStore.swift" \
    "$MAC_DIR/Sources/AutomaticLibrary.swift" "$MAC_DIR/Sources/VerifiedObjectCache.swift" \
    "$MAC_DIR/Sources/Editor.swift" "$MAC_DIR/Sources/FolderRecord.swift" \
    "$MAC_DIR/Sources/FolderManagement.swift" \
    "$MAC_DIR/Sources/NotebookManagement.swift" \
    "$MAC_DIR/Sources/ProbeCLI.swift" \
    "$MAC_DIR/Sources/ReaderModel.swift" "$MAC_DIR/Sources/EditorController.swift" \
    "$MAC_DIR/Tests/CoreTests.swift" "$MAC_DIR/Tests/AutomaticTests.swift" \
    "$MAC_DIR/Tests/EditorTests.swift" "$MAC_DIR/Tests/FolderTests.swift" \
    "$MAC_DIR/Tests/NotebookTests.swift" \
    "$MAC_DIR/Tests/PortableConfigTests.swift" \
    "$MAC_DIR/Tests/ProbeTests.swift" -o "$RUN_DIR/tests" \
    -framework SwiftUI -framework AppKit -framework Security -framework Network -framework CryptoKit -framework LocalAuthentication
"$RUN_DIR/tests" "$RUN_DIR/test-store" "$MAC_DIR/Tests/protocol-vectors.json"
