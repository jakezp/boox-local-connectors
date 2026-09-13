# Source inventory and packaging boundaries

`source-inventory.json` records selected authored source/configuration paths,
byte counts and SHA-256. It inventories **all current authored Python host tools,
Java/Swift production sources and tests, native probe sources and Python
prototypes**, plus authored build/config files and legacy documentation.
It excludes downloaded implementations, app/firmware binaries and live data.
This is a concurrent-work snapshot, not an atomic release or a permission to
publish unreviewed source.

Regenerate from the final checkout:

```sh
python3 docs/reproduction/inventory_sources.py
```

The generator reads only explicit source globs. It never traverses grants, keys,
private OAuth JSON, notebook exports, backups, evidence or decompiled sources.
The JSON itself is excluded from its own hashes. Regeneration only writes
`docs/reproduction/source-inventory.json`.

The parent requested one snapshot after the latest documentation updates.
Coverage includes root `README.md`/`HANDOVER.md`/`AGENTS.md` and `.gitignore`, the
`openai-adapter/res/**/*.xml` glob and the Mac's required shareable
`Tests/protocol-vectors.json` and `Tests/synthetic-manifest.json` inputs.
The later dependency audit also adds `Tests/native-apply-manifest.json`, required
by the default synthetic native-apply test suite.
Coverage also includes `docs/*.md` and the forthcoming
`tools/prepare_magisk_stage.py`/`tools/test_prepare_magisk_stage.py`, including
`docs/ROOT-STAGING.md` when Locke supplies it. Empty/missing future paths do not
create invented inventory entries; parent refreshes after all sources arrive.
Only existing matching files appear as inventory entries; an empty resource
directory does not create invented file records. This snapshot is not release
approval. The Mac own UI round trip has now passed; final packaging/fresh-clone
validation await the subsequent Mac decoder fix/acceptance and source review.

The later [packaging continuation](PACKAGING.md) adds an explicit inventory-based
dry-run/export tool and a checksummed dependency bootstrap. It preserves the
parent's new `tools/android_build_env.py` and build changes. The generated
`source-package-plan.json` is excluded from inventory; the packager includes the
inventory itself in its export without making its hashes self-referential.

## Authored components

| Component | Source and tests | Build/role |
| --- | --- | --- |
| OpenAI/ChatGPT native bridge | `openai-adapter/src/local/boox/openai/*.java`; `res/**/*.xml`; `tests/{ValidationRunner,OAuthValidationRunner,UiValidationRunner}.java` | `build.py`, `tests/build.py`; manifest/Xposed init; compile-only `stubs/` |
| Notes Drive Android | `notes-drive/android/app/src/main/java/local/boox/notesdrive/*.java`; `app/src/test/java/.../*.java` | `android/build.py`, Gradle settings/app/stub projects; manifest/Xposed init |
| Mac reader/editor/library | `notes-drive/macos/Sources/*.swift`; `Resources/{note_reader,note_editor,note_library}.py` | `build.sh`, `test.sh`; Python/Swift tests and named shareable protocol/synthetic-manifest JSON inputs in `Tests/` |
| Native import probe, historical | `notes-drive/probe/src/local/boox/notesprobe/ImportProbe.java` | Probe `build.py`, manifest/Xposed init; uses OpenAI compile-only headers |
| Native apply probe, historical | `notes-drive/apply-probe/src/local/boox/notesapply/{ApplyMain,ImportMaps}.java` | Apply-probe `build.py`, manifest/Xposed init |
| Protocol/format prototypes | `notes-drive/prototype/*.py` | Detailed list below; retain as research, not installation dependencies |
| Live validation harnesses | `notes-drive/tests/*.py`, `NativeArchiveCheck.java` | Preflight, disposable publishers, crash recovery and preservation verification |
| Host support | `tools/*.py` authored files only | UI helper, EDL wrapper, guarded AMS builder, installer, tar repair, doctor and tests |
| Reproduction | This directory | Guide, gaps, provenance, manifest generator and inventory |

The Android Notes production sources cover Drive/session, immutable revisions,
durable queues, native snapshot/archive/store/apply/inbox, metadata, UI routing
and conflict review/resolution. The Mac sources cover independent OAuth/Drive,
verified cache, automatic library, editing, folder/notebook management and a
separate disposable probe CLI. Specific new filenames are recorded by the
generator as concurrent authors add them.

Historical probe packages are not required for normal installation and were
removed from the device after their original experiments. Do not install or scope
them as part of the production setup.

## Authored Python tools and prototypes

| Script | Purpose / execution boundary |
| --- | --- |
| `tools/boox_doctor.py` | Host inventory by default; explicit read-only ADB/root metadata queries |
| `tools/test_boox_doctor.py` | Offline parser, privacy, no-device and command-shape checks |
| `tools/boox_setup.py` | Offline reviewed plan, explicit read-only preflight and backup-gated staged ADB install; never roots/flashes |
| `tools/test_boox_setup.py` | Synthetic planning/backup/signing/scope/delegation tests and AMS guard/module-content tests |
| `tools/install_notes_drive.py` | Mutating install, Vector cache refresh and actual hook marker validation; requires closed Notes editors |
| `tools/build_ams_fix.py` | Parameterized original JAR/new output paths; exact supported SHA and explicit patch proof |
| `tools/run_edl.py` | libusb backend wrapper; EDL command arguments determine read/write behavior |
| `tools/boox_ui.py` | Device UI automation helper; not a doctor dependency |
| `tools/test_boox_ui.py` | Offline required-`BOOX_SERIAL` and no-device-fallback checks |
| `tools/android_build_env.py` | Parent-owned shared JDK/SDK/Gradle resolver; no downloads |
| `tools/package_source.py` | Default read-only source plan; explicit reviewed deterministic local tar export |
| `tools/test_package_source.py` | Synthetic path/privacy/digest/export safety tests |
| `tools/bootstrap_dependencies.py` | Default pinned acquisition plan; explicit new-tree archive verification/extraction |
| `tools/test_bootstrap_dependencies.py` | Synthetic offline acquisition/layout/safety tests |
| `tools/repair_adb_tar.py` | Strict repair of known toybox-notice pollution at tar boundaries; preserves source archive |
| `tools/test_repair_adb_tar.py` | Offline tar parser/repair tests |
| `notes-drive/prototype/sync_protocol.py` | In-memory revision/catalog protocol model |
| `notes-drive/prototype/test_sync_protocol.py` | Protocol concurrency, integrity and ancestry tests |
| `notes-drive/prototype/native_fixture.py` | Native export/point-format inspection for fixtures |
| `notes-drive/prototype/test_native_fixture.py` | Native-format parser tests |
| `notes-drive/prototype/stylus_fixture.py` | Live Wacom input injection for a guarded disposable notebook; mutating |
| `notes-drive/prototype/prepare_apply_fixture.py` | Prepares bounded same-ID native apply fixture |
| `notes-drive/prototype/test_prepare_apply.py` | Apply-fixture preparation tests |
| `notes-drive/prototype/verify_apply_fixture.py` | Fixture semantic/readback verification |
| `notes-drive/prototype/adb_apply.py` | Historical root apply/recovery orchestration; mutating when used |
| `notes-drive/prototype/test_device_apply.py` | Explicit live native crash/apply cases behind a main guard; not a unittest test case; mutating |
| `notes-drive/tests/preflight_native_corpus.py` | Offline production archive validator; private input/output |
| `notes-drive/tests/publish_native_fixture.py` | Guarded disposable native revision publication; mutates Drive |
| `notes-drive/tests/publish_metadata_fixture.py` | Disposable folder/tombstone publication; mutates Drive |
| `notes-drive/tests/verify_metadata_fixture.py` | Native metadata validation against explicitly provided fixture context |
| `notes-drive/tests/validate_native_fault.py` | Device crash/recovery harness; mutating, disposable fixtures only |
| `notes-drive/tests/verify_preservation.py` | Parent-authored preservation checker added during this checkpoint |

`openai-adapter/build.py`, `openai-adapter/tests/build.py`,
`notes-drive/android/build.py`, both probe builders and the three Mac Python
resource modules plus their tests are included in the machine inventory too.
Inventory coverage should be checked again if the parent creates more scripts.

## Inputs, generated outputs and exclusions

Keep authored manifests, assets containing only Xposed class names, source and
synthetic protocol test vectors. Audit any hard-coded serial/path/test identity
in existing source before sharing; the inventory hashes do not sanitize content.

Native `.note` fixture assets are **not** included in this source-only manifest.
Android builds/tests now generate their mandatory inputs; Mac default builds/tests
also use synthetic generation. The two historical prototype corpus suites skip
unless `BOOX_PRIVATE_WORKSPACE` is explicitly set. The Mac's mandatory synthetic
suite and its three named shareable JSON inputs are distinct from its separately
invoked private-fixture suite. Private exports are not mandatory shareable inputs
merely because they were used by an optional suite.

Exclude:

- `backups/**`, `notes-drive/research/**`, Mac `evidence/**`, screenshots,
  native UI XML dumps and private app state.
- All `local-signing.p12`, keystores, Desktop OAuth JSON, Android registration/
  project/folder JSON and local SDK properties. Public OAuth registration fields
  can still identify the account/project and do not belong in this generic guide.
- Original/patched boot images, firmware JARs/APKs, `magisk-env/**`, binary contents
  of `patch/**`, loaders, downloaded SDK/Gradle/EDL/JADX trees and virtualenvs.
- `assistant-decompiled/**`, `ksync-decompiled/**`, `kreader-decompiled/**`,
  `notes-drive/research/notes-decompiled/**` and similar recovered vendor output.
- Build products/caches. Mac bundles can contain OAuth client configuration;
  never treat an ad-hoc build bundle as a sanitized source artifact.

Legacy authored Markdown is inventoried but explicitly marked for publication
review: local handovers and validation pages contain account/project/folder/serial
identifiers, private links and notebook metadata. Preserve originals privately
and publish sanitized copies only after the parent's review. Also review
synthetic instrumentation fixtures for embedded test-only credentials and clarify
their synthetic status.

## Third-party material and provenance

The acquisition manifest separately records downloaded tools and historical
firmware-derived inputs. They are not authored source. Magisk scripts copied into
`patch/`/`magisk-env/` and the EDL/JADX trees must not be misclassified as newly
authored scripts. Compile-only Xposed declarations contain no runtime implementation and are
excluded from app DEX. Their API attribution and upstream Apache license notice
are retained in [third-party notices](../THIRD-PARTY-NOTICES.md).

The public source repository is prepared as its own Git root, separately from the
original workspace. Never add the broader parent directory wholesale. The private
archive preserves the complete project collection, including ignored files and
recovery material; it uses [separate archive tooling](../PRIVATE-ARCHIVE.md), not
this public allowlist. Regenerate the source inventory after final edits.
