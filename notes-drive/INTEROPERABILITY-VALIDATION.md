# Android ↔ macOS Drive validation — 2026-09-12

The user chose a Mac reader as the second test client instead of another BOOX.
The requested subagent built `macos/`; the parent reviewed it, integrated the
shared protocol, configured its own Google client and performed live validation.

## Installed/built clients

- BOOX Notes Drive **Android v0.2** is installed on Note Air4C `DEVICE_SERIAL`.
  Its stable signing identity and Google grant are preserved.
- **BOOX Notes Reader macOS v0.1** is built and open on this Mac:
  `macos/build/BOOX Notes Reader.app`. It is a native SwiftUI/AppKit reader,
  with a bundled bounded decoder using this Mac's `/usr/bin/python3`.
- Both use the same managed directory
  `DRIVE_FOLDER_ID` and Google account permission ID, verified
  independently through their own OAuth grants.
- The Desktop client is registered in the existing project
  `GOOGLE_PROJECT_ID`; public metadata is in `android/google-project.json`.
  Its private config is `desktop-oauth.local.json`. No browser, Android or OpenAI
  tokens were extracted. Mac refresh tokens are in its own Keychain entry.

## Live results

1. Android verified the initially empty revision catalog.
2. Wi-Fi was temporarily disabled. Publishing the bundled `Target-after.note`
   saved an immutable revision and payload to its local journal, then failed
   visibly on the network request.
3. Android was force-stopped, Wi-Fi restored and the app reopened/reconnected.
   Retrying published the same revision bytes and parents; its journal became
   verified only after Drive readback and complete catalog validation.
4. The Mac completed its own PKCE/state/loopback OAuth flow. A separate app process
   reconnected using its saved Keychain grant and found the same marked folder.
5. The Mac downloaded and verified Android's revision, cached its exact 18,171-byte
   notebook and rendered both pages. The UI reported 343 point samples; its
   internal page-link control navigated to page 1.
6. The Mac durably queued and published the distinct bundled `B2.note` fixture as
   a descendant of Android's revision. The verified receipt is retained locally.
7. Android refreshed, found two revisions with one head from the Mac, and saved
   the downloaded payload, canonical revision and catalog to its private incoming
   staging directory. The staged B2 bytes and revision SHA-256 match the Mac.

| Object | SHA-256 |
| --- | --- |
| Android initial revision | `ad7b6fd13cea46a4fb407fa377bcd7c50d28b057d7c44baaeb569cb1203328d6` |
| Android Target-after payload | `50ac19fd0b9a7dc806ea46157f6d053ee49cb7cf84d046938eb0140e7ba92ebc` |
| Mac descendant revision | `28217412bc5e4b37f40fc95a3b7b5685fad8797f94c830839c3f2b7e3a88d8e1` |
| Mac B2 payload | `5f86bb8c3710e24d83cfe7746b0e943a024446ce77720b089b8bc5ef8f43ee6b` |

These four immutable objects remain in the dedicated Drive folder for continued
testing. The earlier connection-only test uploads remain in Trash. No user
notebook was read, uploaded, imported or replaced in this continuation.

## Automated checks and evidence

**22 Android checks and 25 Mac checks pass.** They cover canonical shared Python
vectors, malformed records/archives, checksum and ancestry failures, conflicting
heads, scoped directory validation, pagination, unknown server write outcomes,
durable journal reopen and account/folder binding. Mac tests also exercise a real
loopback socket rejecting wrong state before accepting the matching callback.
Publication tests verify payload-before-record ordering and idempotent retries.
The parent fixed a protocol mismatch that rejected renamed-but-valid objects in
the Mac client; empty objects are also rejected before transfer.

- `research/interoperability/android-live.json`
- `research/interoperability/mac-live.json`
- `research/interoperability/android-offline-queued.png`
- `research/interoperability/android-revision-published.png`
- `research/interoperability/android-mac-descendant.png`
- `research/interoperability/android-staged-mac.note`
- `macos/evidence/validation.json`, `core-tests.txt`, rendered page PNGs
- `android/app/build/test-results/testReleaseUnitTest/`
- `research/apply/android-revisions-build.txt`

Private checkpoint: `backups/notes-drive-mac-interoperability-20260912/` at the
project root. It includes builds, sources, signing/configuration files, app-local
journals and evidence. It excludes Mac Keychain/Google Play services credentials.

## Actual scope and remaining work

The Mac is a reader and transport test client, not a full BOOX editor. It previews
recorded normal-pen coordinates, nominal width/color, layers and page links.
It reports unsupported content and does not reproduce BOOX pressure brushes,
templates, eraser compositing, rich text or media.

Android now has bounded immutable revision transport, durable pending uploads
and incoming staging. Retry is explicit; there is no background scheduler,
resumable large-file transport, journal retention policy or automatic recovery
coordinator. The preview limits are in [PROTOCOL.md](PROTOCOL.md).

**Automatic Notes library sync and native application remain disabled.** The
native save/export integration, persistent app-open gate, device-side semantic
verification, broader format support and Notes sync-control routing are still
required. This Mac exercise replaces the proposed second-BOOX test for the current
phase; it does not establish compatibility with other BOOX firmware.
