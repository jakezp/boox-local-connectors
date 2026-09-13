#!/bin/bash
set -euo pipefail
MAC_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$MAC_DIR/build"
OAUTH_CONFIG=""
usage() {
    printf 'Usage: build.sh [--output-dir DIR] [--oauth-config FILE]\n'
    printf 'Defaults to a credential-free build with generated notebook fixtures.\n'
}
while [ "$#" -gt 0 ]; do
    case "$1" in
        --output-dir|--oauth-config)
            if [ "$#" -lt 2 ] || [ -z "$2" ]; then
                usage >&2
                exit 2
            fi
            case "$2" in
                -*) usage >&2; exit 2 ;;
            esac
            if [ "$1" = "--output-dir" ]; then OUTPUT_DIR="$2"; else OAUTH_CONFIG="$2"; fi
            shift 2
            ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
chmod 700 "$OUTPUT_DIR"
STAGING="$(mktemp -d "$OUTPUT_DIR/.reader-build.XXXXXX")"
trap 'rm -rf "$STAGING"' EXIT
APP="$STAGING/BOOX Notes Reader.app"
DESTINATION="$OUTPUT_DIR/BOOX Notes Reader.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
# Generate fixtures and stage only an explicitly requested configuration.
# Never inherit secrets or notebook bytes from another checkout or old bundle.
RESOURCE_ARGS=(--resources "$APP/Contents/Resources")
if [ -n "$OAUTH_CONFIG" ]; then RESOURCE_ARGS+=(--oauth-config "$OAUTH_CONFIG"); fi
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 "$MAC_DIR/Tests/package_resources.py" "${RESOURCE_ARGS[@]}"
xcrun swiftc -swift-version 5 -O -parse-as-library \
    -target arm64-apple-macosx13.0 \
    "$MAC_DIR"/Sources/*.swift \
    -o "$APP/Contents/MacOS/BOOXNotesReader" \
    -framework SwiftUI -framework AppKit -framework Security -framework Network -framework CryptoKit \
    -framework LocalAuthentication
cp "$MAC_DIR/Resources/note_reader.py" "$APP/Contents/Resources/"
cp "$MAC_DIR/Resources/note_editor.py" "$APP/Contents/Resources/"
cp "$MAC_DIR/Resources/note_library.py" "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>BOOXNotesReader</string>
<key>CFBundleIdentifier</key><string>local.boox.notesreader</string>
<key>CFBundleName</key><string>BOOX Notes Reader</string>
<key>CFBundleDisplayName</key><string>BOOX Notes Reader</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.6</string>
<key>CFBundleVersion</key><string>7</string>
<key>LSMinimumSystemVersion</key><string>13.0</string>
<key>NSHighResolutionCapable</key><true/>
<key>CFBundleDocumentTypes</key><array><dict>
<key>CFBundleTypeName</key><string>BOOX Notebook Export</string>
<key>CFBundleTypeRole</key><string>Viewer</string>
<key>LSHandlerRank</key><string>Alternate</string>
<key>CFBundleTypeExtensions</key><array><string>note</string></array>
</dict></array>
</dict></plist>
PLIST
codesign --force --sign - --identifier local.boox.notesreader "$APP"
codesign --verify --strict "$APP"
# Preserve the old executable's inode if an earlier app is currently running.
# This build never terminates/launches it or touches Application Support/Keychain.
PREVIOUS=""
if [ -d "$DESTINATION" ]; then
    PREVIOUS="$(mktemp -d "$OUTPUT_DIR/previous-reader.XXXXXX")"
    mv "$DESTINATION" "$PREVIOUS/BOOX Notes Reader.app"
fi
if ! mv "$APP" "$DESTINATION"; then
    if [ -n "$PREVIOUS" ]; then
        mv "$PREVIOUS/BOOX Notes Reader.app" "$DESTINATION"
    fi
    exit 1
fi
printf 'Built %s (restart the running app to activate this version)\n' "$DESTINATION"
