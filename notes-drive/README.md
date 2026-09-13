# BOOX Notes Drive and Mac editor

Android connector **v0.4** replaces the inspected Notes cloud path with automatic
Google Drive publishing and journaled incoming application. Mac reader/editor
**v0.6** is the second client, with its own OAuth grant, pen drawing/whole-stroke
erasing, durable drafts, notebook/folder management and automatic library updates.

Live checks passed Mac UI pen edits → Drive → native BOOX readback/visual opening
→ native save → automatic Mac following. Creation, rename/move, recoverable
notebook deletion/restoration, native folder lifecycle, all-head Android conflict
selection and seven crash checkpoints passed. The v0.6 nested-folder export fix
passes automated/retained-export tests; its final interactive check awaits the
rebuilt app's macOS Keychain prompt.

A clean source checkout builds all applications without private notebooks or
Google config. Android 57 and Mac 162 mandatory checks pass. Start with the
[project README](../README.md), [end-to-end setup](../docs/reproduction/README.md),
[protocol](PROTOCOL.md), [Android guide](android/README.md),
[Mac guide](macos/README.md) and [current validation](INCOMING-VALIDATION.md).

Incoming changes wait for editors to close. Mac sync runs while the app is open.
The adapter is firmware-specific and bounded; [current limits](../docs/reproduction/GAPS.md)
record scale, renderer and acceptance boundaries. Each client retains its own
credentials; no ONYX account is required for the configured replacement path.

The milestones below are historical, including their original unfinished-work
statements. They are retained to explain how the implementation developed.

## Earlier milestones

**Earlier manual interoperability milestone.** The user requested a macOS reader
as the second test client instead of another BOOX. A subagent built the native
[Mac app](macos/README.md); the parent integrated and tested both directions.
Android v0.2 is installed with a durable revision queue and incoming staging.
**22 Android + 25 Mac checks pass**, and the live offline/restart/retry → Mac
download/render → Mac publication → Android staging exercise passed.
Start with [INTEROPERABILITY-VALIDATION.md](INTEROPERABILITY-VALIDATION.md) and
[PROTOCOL.md](PROTOCOL.md). Automatic Notes library sync and native apply remain off.

Start with [APPLY-VALIDATION.md](APPLY-VALIDATION.md) for the latest results,
[NATIVE-VALIDATION.md](NATIVE-VALIDATION.md) for the original round trips, and
[FEASIBILITY.md](FEASIBILITY.md) for the overall design.

Current state: a disposable notebook was updated in place, reopened and exported.
Notebook/page/existing stroke IDs stayed stable; incoming pen data, layers and
internal links passed readback. An unchanged observer's inbound link retained
its target. **40 offline tests and 24 live fault/guard cases pass**, plus verified
commit/stale-proof checks. The probes and fixtures were removed; the three original
notebooks and 182 associated files match the pre-test backup.

A separate Android Google authorization/Drive revision preview **v0.2 is installed**;
see [android/README.md](android/README.md). Twenty-two Android tests pass and the native
Google grant, app-owned folder creation and real disposable notebook upload/download
now pass. Downloaded bytes match SHA-256; the test upload is in Trash. Restart and
manual reconnect retain the account/folder without another consent prompt.
See [live evidence](research/apply/drive-live-validation.json).
No automatic Notes sync is enabled. The native experiment still
needs an app-open gate, production verifier, general format handling and broader
content testing. Durable revision transport now has Mac interoperability evidence.
No user notebook
content was uploaded; only the bundled disposable fixture was tested.

The working OpenAI/ChatGPT connector remains separate in `../openai-adapter/`.
