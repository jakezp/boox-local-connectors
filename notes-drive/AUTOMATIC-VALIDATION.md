# Automatic BOOX → Drive → Mac validation

The automatic publishing path is implemented and live-tested. Android v0.3
captures saved native notebooks, retains them offline, and publishes immutable
revisions with its own Google grant. Mac v0.2 reconnects with its own Keychain
grant and refreshes the library every 12 seconds while open.

This is **automatic outbound library publishing**, not yet a complete replacement
for bidirectional ONYX Notes sync. Incoming native application, deletion handling,
folder hierarchy and broader content/editing support remain unfinished.

## Live results

- All three original notebooks appeared automatically in the Mac library:
  `Notebook-1`, `Infinite-1`, and `test` (20 pages). No manual transfer or
  refresh button was used to deliver them.
- Created `GDrive-Sync-Probe-Automatic` through native Notes and drew a box.
  Its first verified revision was `1fed6e05f9ab05fdfd49547e135bcb73fc5104f64f204b230005b781cf252607`.
  The Mac rendered four pen strokes / 124 recorded samples.
- Added a line while Wi-Fi was disabled. The durable capture survived connector
  force-stop and reopening byte-for-byte. Wi-Fi was then restored.
- The exact offline payload
  `b108fc79302e26281ea230fb745f019020aa14bce8f1658969885590c856bab2`
  reached Drive as a descendant and the open Mac notebook advanced automatically.
  The reader displayed five pens / 154 samples, retained page
  `8a5754c7d24f4ceca6e5631b6bdb5ec4`, and retained **125% zoom**.
- Its descendant revision is
  `c0249ae5c125c1b011fc3c55f4e0c3f2c34599c617453d4b4e5736ee347f3c4f`.
  The final Mac build also reopened, silently reconnected and rendered that exact
  revision without a stale preview-error label. Fresh library checks reused
  26 verified cached objects and downloaded zero media bytes.
- A shell caller was denied access to the native bridge. Only the inspected
  installed Notes UID/package can submit captures.
- The three original NoteModel rows and all **182 original associated files**
  match the fresh pre-change backup. The additional disposable notebook remains
  available for manual testing, also under the Mac's validation disclosure.

Evidence is under `research/automatic/`; `preservation.json` records the exact
comparison. The Mac decoder/build checks are under `macos/evidence/`.
Android has 30 passing tests; the Mac has 60. Historical native apply tests remain
separate and do not establish production-safe incoming application.
`research/automatic/live-validation.json` records the final installed hashes and
readback. The private checkpoint is
`../backups/notes-drive-automatic-v0.3-20260912/`. Pending uploads were zero, Wi-Fi
was enabled and the connector remained unfrozen at the final check.

## Capture and recovery

`NativeNotesHook` is scoped only to `com.onyx.android.note`, code 45326. It uses
the inspected native serializer on Notes' shared single scheduler. Export output
goes to a unique private temporary directory. The hook omits UI, a second save,
the stock export-path override and cloud-result callbacks.

A successful native save triggers capture. A closed-notebook scan runs when
Notes starts and approximately once per minute while that process remains alive.
Locked notebooks, locked parent folders, associated documents and unsupported
identities are skipped. The current transfer limit is 4 MiB per `.note`.

The stock exporter regenerates some archive identifiers/timestamps. Private
source receipts use native metadata changes to avoid repeatedly exporting an
unchanged notebook. Receipts are written only after durable bridge acceptance.
These values are local deduplication hints, never revision-ordering clocks.
The first investigation runs produced extra immutable versions before this was
corrected; their verified history is retained.

`NotesBridge` checks the caller and destination generation, accepts bytes through
a one-use file descriptor, validates bounded archive paths and the payload hash,
and durably stages the capture in the connector. No Google token crosses this
bridge. Native capture and outgoing revision journals bind the account and folder.

An immediate, debounced attempt runs when the connector is alive and a capture
arrives; validated network reconnection triggers another attempt. Persisted
network-constrained jobs provide retry/reboot recovery, with expedited scheduling
when available and a periodic safety check. Android can defer jobs during sleep;
this is not continuous streaming or a guaranteed delivery deadline.

Revisions follow the local branch rather than silently adopting another device's
head. Uncertain publication retries the identical immutable record. Parent
revisions upload first. Completed local receipts are compacted without deleting
remote history or pending work. Unchanged Drive media is reused only with matching
Drive ID/version/metadata and reverified cached bytes.

## Device integration fixes

- Xposed minimum API 82 preserves the existing private preference location.
  Advertising 93 caused the framework to redirect preferences in early builds.
- The native bridge poller starts after application attach on its own handler
  thread; constructing a main-loop handler during module instantiation was too
  early for Vector.
- BOOX auto-freezing was disabled specifically for this connector through the
  launcher's **Unfreeze** action; the menu then showed **Freeze**. `pm enable`
  alone was insufficient for the persistent setting.
- `tools/boox_ui.py` now removes the previous UI hierarchy before dumping, so a
  failed Android accessibility read cannot silently reuse stale controls.

ONYX's Notes-data setting was already false in the observed configuration and
was preserved. Native ONYX sync buttons are not yet routed to Drive. Other KSync
features, OneNote and the OpenAI connector were not changed.

## Reader fixes from real exports

Real notebooks contain multiple valid point chunks for a page. The decoder now
resolves them by page, revision and shape identity, retaining collision checks.
Archived `stash/shape` entries are distinguished from active shape data and
omitted with an explicit history warning; disjoint active chunks are supported.

The Mac still provides a coordinate preview, not BOOX's complete renderer.
Pressure/brush composition, background artwork, attachments and unsupported
shape types can differ or remain unrendered. Verified but unsupported incoming
previews preserve the currently open notebook.

## Remaining work toward full ONYX replacement

Implement the persistent native app-open/apply/recovery coordinator and device-side
semantic verifier before applying incoming updates automatically. Preserve native
notebook/page/stroke/link identities, retain conflicts, and expand coverage for
resources, rich text, page removal/reordering, locks and folder/deletion semantics.
Then route the native Notes sync controls/status to Drive. The Mac currently reads
the library; it is not an editable second BOOX.
