# Source and live validation checkpoint

Checkpoint: 2026-09-13. Private notebook captures and device credentials remain in
the original workspace; the source repository contains the reproducible tests,
synthetic fixture generators and validation scripts.

## Clean-checkout results

A separate Git clone of source commit `113c0fe` built:

- Notes Drive Android connector and its57 unit tests.
- OpenAI connector and its separate instrumentation APK.
- Both historical Notes probe APKs.
- Mac app and162 mandatory checks (69 Python / 93 Swift).
- Host suite:115 passed, one explicitly optional private-input case skipped.
- Protocol/prototype suite:19 passed,21 historical private-capture cases skipped.

No private `.note` file, OAuth config or signing key was copied to that clone.
It generated fresh signing identities. Android SDK/Gradle came from freshly
downloaded hash-verified archives, and Gradle used an empty user cache. The Mac's
existing Python/JDK/Xcode installations were used. This was not a clean OS
provisioning or a fresh-device root/install.

The subsequent dependency manifest records501 SHA-256 entries across291 components.
The Android build passed again with `--dependency-verification strict --offline`.
Future dependency changes require a deliberate review of regenerated checksums.
Builds of new clones use a different local signing key, so APK hashes need not
match the installed development APK; hook source and synthetic asset hashes do.

## Live device and Mac results

The installed Notes connector uses hook `1b35137b83528a78`; the synthetic-asset
APK hash is `fce3cc2727b64f139796ae4f45d2dab48a4a5c41ce1f2fdf02bd3fb432bf42e6`.
Its installer verified the actual current Notes process loaded that generation.

The Mac v0.5 lifecycle UI independently reconnected its Google grant and passed:

1. Mouse-drawn pen → Save to library → automatic Drive publication → native
   same-ID six-pen readback → visible stock Notes opening.
2. Normal native save/close → automatic Mac following, with page/zoom retained.
3. Whole-stroke erase, undo and redo → native five-pen readback.
4. New notebook, rename and folder move → native creation/opening.
5. Recoverable deletion → native Recycle Bin status 0 → restoration under the
   original ID with the retained blank page.

Earlier native tests passed all seven apply crash checkpoints, folder lifecycle,
and Android conflict selection retaining both heads as parents. The recorded
preservation snapshot matched all 182 associated original files and every field
of three original notebook rows. That snapshot is a dated proof, not an assertion
that later user edits cannot exist.

Testing found repeated ancestor folder records in native notebook exports. Mac
v0.6 selects the exact archive-root notebook and preserves ancestor/unknown bytes
through edits. Its162automated checks and retained native export exercises pass.
The rebuilt app imported the existing Desktop configuration through its UI;
**its final interactive nested-notebook check awaits the macOS Keychain prompt**.
The computer-use tool cannot operate macOS SecurityAgent.

The v0.6 executable checkpoint is
`fc19e074c3dab9f9b19a0a6125c577042cc066f5ddbd3cf4f2a56eb4e6996890`.
No live acceptance is claimed for that binary until the pending step completes.

## Reproduce and interpret

Use the commands in the root README and [end-to-end guide](reproduction/README.md).
Run live fixture helpers only against an explicitly selected device and current
revision. A verified upload is not proof of native application; the native
readback helpers check the applied branch separately.

Root preparation has27 mandatory safety tests plus an optional actual-input test;
all 28 passed in the original workspace. The 15 staged assets matched the historical
environment. No new boot patch or flash was performed. See
[root staging](ROOT-STAGING.md) and [remaining limits](reproduction/GAPS.md).
