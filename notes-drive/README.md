# BOOX Notes Drive exploration and native validation

## Current: automatic publishing

Android v0.3 now captures native Notes saves and publishes to Drive automatically.
Mac v0.2 reconnects silently, refreshes every 12 seconds while open, and follows
verified descendants without resetting page/zoom. The real library and an offline
edit/restart/reconnect round trip are validated. See
[AUTOMATIC-VALIDATION.md](AUTOMATIC-VALIDATION.md) for evidence and limits.

Incoming native application, folders/deletions and native sync-control replacement
remain unfinished. The earlier preview milestones below are historical.

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
