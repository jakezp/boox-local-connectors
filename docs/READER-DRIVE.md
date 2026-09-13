# Google Drive for books and reading data

The Drive companion also connects NeoReader to the same managed Google Drive
directory used for notebooks. It stores the book itself together with reading
position, bookmarks, text annotations, reading statistics and handwritten
book notes. You read and annotate in the original BOOX apps.

## Enable reading sync

1. Complete the device, Google project and signed-app setup in the main README.
   This integration requires NeoReader version **38701**, Notes **45326** and
   BOOX launcher **56737** on the documented NoteAir4C firmware.
2. Open **Library → menu → Library Settings**.
3. Find **Google Drive Sync** below the ONYX section. Tap its information icon
   to open **Google Drive Settings**.
4. Connect Google Drive. Create one managed directory for the library, or select
   your existing directory. Use that same directory on every linked BOOX.
5. Turn on **Automatically sync books and reading data**, or return to Library
   Settings and enable the Google Drive **Sync Switch**.
6. Open a disposable book, change its reading position and close NeoReader with
   Back. Check **Books and reading data** in Drive Settings for the result.

Notebook sync and reading sync have separate switches. They share Google
authorization and the selected directory. The information icon is the entry
point for connection, configuration and troubleshooting.

## Link a new BOOX

Install the same signed APK and native hooks on another supported device. Sign
into Google normally on that device, select the existing managed directory and
enable reading sync. Keep the device awake, online, and NeoReader closed during
the first download.

Incoming books are placed under
`Books/Google Drive/<book identity>/<original filename>` and registered in the
native library. Reading records keep their logical book and annotation
identities. Local file paths and provider fields belong to the receiving device.
An update to an existing book keeps its existing local location.

Open a downloaded disposable book and check its page, bookmarks, annotations
and handwriting. Make a small change, close the book and verify it on the
first device before relying on the pairing.

## When synchronization runs

Closing a saved document requests synchronization. Network reconnection and
persisted Android jobs provide retries; **Sync books now** requests a check
immediately. Android sleep and connectivity can delay background jobs.

NeoReader normally caches a document when Back returns to the library. While
Drive reading sync is enabled, the integration follows that transition with
NeoReader's own close-document action, including its final save. It does not
apply incoming data while an editor tab remains open.

This is asynchronous saved-state synchronization. It is not simultaneous editing
of an open document. Concurrent revisions remain separate in Drive history;
the connector does not choose a winner using modification time. Use **Review
conflicting reading versions** in Drive Settings to compare position and annotation
counts, then explicitly select a whole-book version. Other versions remain in
Drive history. If the heads change while reviewing, review them again.

## Supported data and boundaries

The adapter includes native per-book `Metadata`, `Annotation` and `Bookmark`
records; per-book ReaderNote document, resource and shape records; `.ksync`
document/point assets; reading-statistics records; and the original ebook.
Native numeric database row IDs are local. Existing rows keep their IDs during
updates, while portable logical identities are retained on new devices.

Only active native-library entries with a readable, unencrypted local file are
included. Open a newly copied book in NeoReader to ensure it is indexed.
DRM/provider-controlled books require their original provider. The bundle limit
is 1 GiB per book including notes, with at most 20,000 files and a 16 MiB reading-record limit. A directory stores
up to 10,000 reading revisions and 100,000 Reader objects in this implementation.

Library shelf layout, provider downloads and ONYX account settings are not part
of the per-book bundle. A missing local file is not treated as permission to
delete another device's copy. Retained Drive history must not be removed to
clear a sync error.

## Installation and update details

The Drive module needs exactly these Vector scopes:

```text
com.onyx.android.note/0
com.onyx/0
com.onyx.kreader/0
```

For an existing installation, save and close all native editors and retain a
current backup before migrating scopes:

```sh
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli scope set local.boox.notesdrive com.onyx.android.note/0 com.onyx/0 com.onyx.kreader/0"'
```

Use the guarded setup CLI for normal installation. The lower-level update tool
requires both saved/closed attestations:

```sh
python3 tools/install_notes_drive.py --serial "$BOOX_SERIAL" \
  --notes-closed --readers-closed --report /absolute/path/install-report.json
```

It verifies the signed APK and the matching hook generation inside Notes,
launcher and NeoReader. NeoReader's actual tabs run in separate processes; the
module loads there too. A successful APK install alone is not a hook check.

## Implementation and recovery

Reader objects use the separate `booxReaderProtocol=1` Drive namespace. Books
are streamed in independently verified 3 MiB chunks; immutable manifests and
revisions reuse unchanged chunks. The companion uses its own normal Google
grant and never copies another app's credentials.

The local ancestry state and pending publication are persisted before uploading.
Incoming application takes an exclusive lock shared with all Reader tab
processes, verifies the expected current content, then writes a durable per-book
preimage and journal. Files and native records are applied, recaptured and
compared against the incoming manifest before acknowledgement.

An interruption before commit restores the exact prior per-book rows and
assets. An interruption after commit finalizes the durable receipt; the
companion can adopt that receipt after restart without misclassifying an
already-applied version as a new edit. The adapter never replaces the whole
native library database.

The explicit device harness is
`notes-drive/android/app/src/androidTest/java/local/boox/notesdrive/ReaderDeviceChecks.java`.
Mutation checks require a book whose filename starts with
`BOOX-Drive-Validation`. Generate the original four-page test PDF with
`notes-drive/tests/generate_reader_fixture.py`. These controls are not exposed
in the everyday settings UI.

To build the device checks after the normal production build, keep the same
source generation:

```sh
HOOK_BUILD="$(python3 -c 'import json; print(json.load(open("notes-drive/android/registration.json"))["hook_build"])')"
"$BOOX_GRADLE" -p notes-drive/android --no-daemon \
  "-PhookBuild=$HOOK_BUILD" assembleReleaseAndroidTest
tools/platform-tools/adb -s "$BOOX_SERIAL" install -r \
  notes-drive/android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
tools/platform-tools/adb -s "$BOOX_SERIAL" shell am instrument -w \
  -e operation status \
  local.boox.notesdrive.test/local.boox.notesdrive.ReaderDeviceChecks
```

`status` and `inspect` are read-only. Pass `-e book '<native-book-UUID>'` for
`inspect`. After generating and opening the explicitly named fixture, the
mutating operations are `restore-new`, `roundtrip`, `roundtrip-edit`, `conflict`,
and `crash` (with `-e checkpoint files`, `metadata`, or `committed`).
`verify-conflict` checks the result after choosing a version through the UI.
Use `restore-new` before adding handwriting, because changing the identity of
an already annotated fixture requires conversion of its native binary assets.
The historical `repair-fixture` operation is limited to early generated restore
revisions; it is not part of normal installation or sync.

For the annotated check, add a bookmark, highlight, text note and pen stroke in
NeoReader; close it normally and wait for the companion's automatic check to
finish. Starting instrumentation restarts the companion, so running it during
publication can interrupt the very automatic check being observed.
`roundtrip-edit` changes the disposable note text to **Returned through Google
Drive**, publishes it through the normal Drive client, applies it natively and
verifies the complete resulting bundle. Open the book afterward to inspect the
text, pen stroke and saved position yourself.
