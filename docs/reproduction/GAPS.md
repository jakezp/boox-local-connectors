# Remaining reproducibility and acceptance gaps

Historical checkpoint: 2026-09-12; installer continuation: 2026-09-13.
These are concrete outstanding requirements, not a claim
that every item blocks ordinary use of the historically working features.

| Area | Evidence available | Still needed |
| --- | --- | --- |
| Full Notes/Mac replacement | Prior57 Android checks/native lifecycle/preservation; later Mac own-OAuth GUI pen/save/native commit/follow-back passed with six pens/129 samples and exact IDs, preserving page/zoom; UI notebook creation/rename/move committed/opened natively | Mac decoder ancestor-row fix and fixed-build acceptance pending; fresh-clone source-only build still required |
| Mac test checkpoints | Parent reports129 mandatory synthetic checks and a separate five-check explicit private-fixture suite; frozen v0.5 app has its own125-check build/test checkpoint; later interactive round trip passed | Keep suites/build checkpoints and later UI evidence distinct; record fixed decoder build/test identity and acceptance next |
| Source snapshot | Parent-requested documentation-update inventory includes root README/HANDOVER, OpenAI XML-resource coverage and named shareable Mac protocol/synthetic-manifest JSON inputs | Record final commit/build/test relationship after decoder fix acceptance and fresh-clone build; current snapshot is not release approval |
| Clean checkout | Existing local builds passed historically | Run on a fresh checkout using only published source plus declared private inputs; no hidden caches |
| Signing/OAuth | Stable local Android identities and independent client flows historically used | Private key provisioning for updates or clearly new install registration; no existing keys in Git; audit Mac bundled OAuth JSON |
| Host tools | SDK/Gradle/Vector/JADX archive hashes; current OracleJDK17.0.16, Xcode26.4.1/Swift6.3.1 and EDL Python/package metadata | Original JDK/Xcode acquisition/archive hashes and historical compiler provenance; full Python environment lock |
| Source/dependency packaging | Parent fresh network bootstrap passed all four default archive hashes/extraction with success receipt; all Android builders/preflight use shared resolver; mandatory Mac native-apply manifest added to rules | Resolve staged source privacy findings; refresh snapshot after final edits; run actual fresh-clone build using acquired tools and a new Gradle cache |
| Android dependencies | Direct Gradle versions and eight current cached JAR/AAR/POM hashes | Transitive dependency lock/verification metadata and fresh-resolution test; cached hashes are not remote provenance verification |
| Root | Exact original/patched images and B-only readback verified | Reusable parameterized backup/patch installer; exact original Magisk asset staging sequence; bootloader unlock not tested |
| GPT | Tool success text and source explain missing writes | Actual verified GPT payload backups and a tested read-only acquisition path |
| Magisk30.7 | Later rooted B hash/history | Deliberate upgrade provenance and exact assets; do not label original30.2 as current |
| AMS | Parameterized input/output; exact original/patched SHA guards and explicit bytecode proofs; offline original-JAR reproduction preserves every unsigned module entry | Live module reinstall not attempted; new-firmware analysis still required; outer ZIP timestamps/metadata are not reproducible |
| Firmware/native app inputs | Local private originals | Operator acquisition path and licensing; no firmware/system APK/decompilation in source repo |
| Fixtures | Parent Android source-only synthetic build passes57 tests/release assembly; all mandatory Android inputs generated; historical corpus suites explicitly opt-in/default-skip; parent installing synthetic APK now | Await installation/live acceptance result; final fresh-clone build and source review remain; keep optional private corpora excluded |
| Legacy docs/evidence | Detailed local handovers and validation | Sanitize serial/account/project/folder IDs, titles, absolute home paths, screenshots and private artifacts before repository inclusion |
| Staged installer | Parent NotesDrive existing-install read-only preflight and same-APK guarded apply passed, including delegated hook and post-signature/hash/scopes; subsequent native/Drive checks and later Mac own UI round trip passed | Mac follow-up decoder acceptance pending; OpenAI wrapper and all first-install paths remain without live wrapper validation; root/flash are outside the wrapper and no new live reproduction is claimed |
| Recovery | Targeted disable, per-notebook recovery architecture, original backups | Rehearsed full recovery and feature rollback on disposable data; backup alone does not export Keystore/Keychain |
| OTA | Explicit disable-before-update boundary | No OTA or inactive-slot root preservation tested; fresh-firmware verification mandatory |
| ChatGPT transport | Historical own-session sign-in/model dropdown/native reply | Custom endpoint stability and current account availability cannot be assumed; live subscription Stop/floating/regenerate coverage remains narrower than API |
| Drive lifetime/scale | Durable revisions, own grants and offline retries | Longer expiry/revocation/background-cycle and large-library/unsupported-content validation; do not infer these from a fixture round trip |

This sidecar performed no device queries, UI interaction, OAuth, token/private-key
reads, repository creation/push, cleanup or edits outside its authorized files.
The doctor is an inventory CLI, not an installer or a backup-restore validator.
The parent completed the new blank notebook's native opening with a visible
canvas and normal close. The later Mac own-OAuth GUI pen round trip passed;
it does not establish physical BOOX stylus validation. Both devices are available.
Final packaging and fresh-clone validation await the Mac decoder fix/acceptance
and source review; this sidecar performs no cleanup or Git work.

Closed by the parent's2026-09-13 follow-up:

- Fresh network dependency bootstrap: four default archives downloaded with exact
  hashes and extracted; receipt says `dependencies_materialized_not_executed`.
  Parent also reports four portable-path APK builder checks passed. The actual
  fresh-clone build remains separate.
- GUI erase/undo/redo committed five pens natively; UI deletion reached native SQL
  status 0; same-ID blank restoration committed with zero pens.
- All remaining Android instrumentation/probe/preflight JDK paths now use the
  shared resolver. Both historical prototype corpus suites skip by default and
  require explicit `BOOX_PRIVATE_WORKSPACE`; parent reports discovery passed.
- Final live doctor: both companion scopes match exactly; zero query failures.
- Existing NotesDrive wrapper update: plan
  `2d4531791aadf0796405372e3d2386727ccab53c01fc1a8a44503393b8edcfbf`
  passed read-only preflight and actual same-APK apply. Evidence
  `notes-drive/research/incoming/setup-apply.json` reports completed preflight,
  delegated installer and post-install identity/scopes; `notes_hook_verified=true`,
  `sync_verified=false`. Those original report fields remain unchanged.
  Subsequent parent acceptance in
  `notes-drive/research/incoming/latest-checkpoint.json` confirms the new
  Mac-created blank opened with a visible canvas and closed normally, both latest
  fixture heads were verified in Drive, zero uploads were pending, and both
  automatic settings were on. The later Mac own UI/OAuth round trip passed;
  the follow-up decoder fix still needs its own acceptance.
- Native original preservation before the wrapper reinstall: three original
  notebook rows and182 files unchanged. Final private archive
  `backups/notes-drive-native-v0.4-20260913/native-after.tar` has parent-recorded
  SHA-256 `99f4b4576fcb0dd3350f8e88246bfa334d27816c90c37d346dd9f981870616a9`.
- Loader acquisition: pinned raw download from bkerler/Loaders commit
  `2fe9ee42e2135c8db5005cb542472eda16d81473` matches the exact local binary.
  Commit/path/Git blob/SHA-256 are in `acquisition.json`; this closes the
  reproduction provenance gap. The original historical checkout commit remains
  unknown, which does not prevent acquiring the identical binary. This pin does
  not grant redistribution rights; downloaded loader binaries remain excluded
  from the source-only repository.

Sidecar verification completed:

- At the original setup checkpoint, **56 offline tests passed**
  (21 doctor +35 setup/AMS), also rerun successfully
  by the parent, including actual Vector
  table rows/help/errors, no subprocess in host-only mode, private-diagnostic
  rejection, backup/device/signature/downgrade guards, staged helper delegation,
  first-install planning and mocked apply/report failure behavior.
- Packaging continuation: **86 offline tests passed** across the selected suites:
  18 packaging, 10 bootstrap and 58 doctor/setup/AMS/UI-helper tests. The latter
  includes the original 56 plus two new required-serial UI-helper tests.
- `python3 tools/boox_doctor.py`: host-only mode completed and found the expected
  local build paths/fixtures. It computed APK hashes without contacting a device.
- Locally rehashed original/patched boot B, readback, AMS JAR/module, loader and
  tool archives. The patched boot and readback hashes matched.
- Rebuilt the AMS JAR in memory from the exact original and compared against
  `e2bad92800231d1ad86179202f42dd5f89d830114a7bc6273d25ce9c2c923c14`.
  Every unsigned module entry's contents matched the archived module. No binary
  output or device operation was performed.
- A real local OpenAI APK passed offline signature/package/planning checks and
  matched the recorded v0.9 artifact. This did not read a key or contact a device.
- Both real local APKs subsequently passed the combined offline plan at
  `setup-checkpoint.json`, including the parent's latest Notes build. Setup/doctor
  code remains unchanged under the parent's freeze. The parent has
  now requested one inventory regeneration after documentation updates, with
  root README/HANDOVER, OpenAI XML-resource glob and the Mac's two named shareable
  test-input JSON files covered. This does not authorize cleanup or Git work.
- New authored files checked for the known local serial/account/project/folder
  identifiers and private-key/API-key patterns: no matches. This targeted check
  does not certify existing source or legacy documents as safe to publish.
- Acquisition URLs derive from saved metadata or are explicitly identified as
  reproduction/reference URLs. The parent has now freshly downloaded and verified
  the pinned loader and four default bootstrap archives. This sidecar performed no new download or current
  external-service compatibility check; AI setup behavior remains source/history based.
