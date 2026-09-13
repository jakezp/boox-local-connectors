# Source packaging and dependency bootstrap

Current checkpoint: 2026-09-13. The public source package builds all five Android
APKs and the Mac app from a separate clean clone. Mac v0.6 also passed final
interactive nested-notebook sync and automatic following. See
[validation](../VALIDATION.md) for the evidence and
[private archive](../PRIVATE-ARCHIVE.md) for complete recovery storage.

Public source contains authored code, tests, synthetic fixtures and reproducible
dependency/setup tools. Firmware, project keys, real notebooks and acquired
binaries belong only in the explicitly private archive.

## Reviewable source plan

Run from the source checkout with Python 3.10 or newer:

```sh
python3 docs/reproduction/inventory_sources.py
python3 tools/package_source.py
```

The first command snapshots explicit source globs and changes only the inventory.
Run it after authors finish their edits. The second command is read-only and
prints deterministic JSON. It reads only the inventory and explicitly listed
source files; it neither searches private directories nor executes source.
The inventory covers the authored Android/Mac apps, host scripts/tests, native
probes, protocol/format prototypes, build files and documentation. The packager
adds the inventory JSON itself to the export so a fresh checkout can plan again.
Root `.gitignore` and `AGENTS.md` are included. `--root /absolute/staged-checkout`
selects a separate source tree; regenerate that tree's inventory using its own
`docs/reproduction/inventory_sources.py` after edits. No source-path relationship
to the old private workspace is required.

At the parent's explicit request, a separate mode-0700 review directory can be
prepared from checked source bytes while identity-bearing source/docs await
sanitization. It is a private review candidate, not a clean export or release.
Credentials, keys, notebook payloads, live evidence and downloaded binaries are
still excluded. Parent sanitizes legacy Markdown only in that stage and brings
in the accepted Mac source changes before rerunning the clean check. The original
private workspace and evidence remain untouched. The tar export guard remains
strict and does not accept unresolved findings.

To save a plan to a **new** file:

```sh
python3 tools/package_source.py \
  --write-plan docs/reproduction/source-package-plan.json
```

This generated plan is deliberately excluded from source inventory to avoid
recursive hashes and stale embedded review results. An existing file is never
overwritten; use a different new path outside the checkout for a later plan.
Exit 0 means ready for content review, 1 means findings block export, and 2 means
invalid/unsafe input, an unmatched review or an output creation failure.

The tool checks all listed paths before opening any listed source, then verifies
recorded SHA-256 and byte counts. It rejects traversal, case collisions, symlink
files/directories, special files, non-text content and over-limit inputs.
Ordinary hard-linked source files are read as regular bytes; tar exports contain
no hard links or symlinks. A bounded byte snapshot prevents later source changes
from changing an export silently.

Detected private-key/API-key literals, OAuth registration identities, selected
fixed device/project/folder bindings, personal email candidates and absolute
home paths block export. Diagnostics contain only source path, line and rule;
they do not repeat the value. Reserved synthetic email domains are permitted.
Detection is intentionally limited: review every source file, legacy document,
test literal and attribution before using the explicit content-review flag.
No automatic redaction or omission is performed; missing or blocked source
cannot silently become an apparently complete package.

After resolving findings and reviewing the exact plan, a local export command is:

```sh
python3 tools/package_source.py \
  --export /absolute/new-output/boox-source.tar \
  --reviewed-plan /absolute/reviewed/source-plan.json \
  --content-reviewed
```

The output must be a new path outside the source checkout, with an existing parent
directory. Export requires a byte-equivalent regenerated plan, binding all source
digests and the packaging tool itself. Tar members are sorted regular files with
zero timestamps/owner IDs and fixed modes: shell scripts 0755, other source 0644.
The tar file is 0600. No Git command, upload or archive extraction occurs.
An export still does not prove that a clean checkout builds.

## Dependency acquisition from declared pins

`tools/bootstrap_dependencies.py` uses the exact URL, size, SHA-256 and declared
`bootstrap.zip_layouts` in `acquisition.json`. It does not infer missing pins,
resolve latest versions or execute downloaded files.

```sh
python3 tools/bootstrap_dependencies.py > /absolute/new-output/dependencies-plan.json
python3 tools/bootstrap_dependencies.py \
  --apply --destination /absolute/new-output/boox-dependencies \
  --reviewed-plan /absolute/new-output/dependencies-plan.json
```

The default selection is platform-tools 37.0.1, build-tools 35.0.0, Android
platform 35 revision 2 and Gradle 8.11.1. These are the recorded macOS archives;
this is not a Linux/Windows toolchain bootstrap. Explicit `--select NAME`
arguments replace the default selection and must be repeated identically when
applying a reviewed plan. Additional declared recipes are `jadx`, `vector`
and `edl_loader`. Vector and loader are downloaded as opaque artifacts only;
they are never installed, executed or flashed.

For a fully offline copy from already acquired archives:

```sh
python3 tools/bootstrap_dependencies.py \
  --apply --destination /absolute/new-output/boox-dependencies \
  --reviewed-plan /absolute/new-output/dependencies-plan.json \
  --offline --archive-root /absolute/private/archive-root
```

The archive root must contain the `local_archive` relative paths in the manifest,
such as `tools/build-tools.zip`. Local archive symlinks are rejected. Without
`--archive-root`, explicit apply downloads using HTTPS from allowed upstream/
release hosts. It does not load proxy credentials, send auth headers or accept
SDK licenses. Network acquisition was not exercised by this sidecar.
The parent subsequently passed a fresh network bootstrap of all four default
archives with exact hashes, successful extraction and receipt status
`dependencies_materialized_not_executed`. Private evidence is named
`clean-dependencies-bootstrap.json`. The requested fresh-clone build will use
that separate dependency tree and a new `GRADLE_USER_HOME`, without a hidden SDK
cache. This is parent-reported acquisition evidence, not yet a completed
fresh-clone build.

The destination must not exist; the tool never merges into an existing SDK or
replaces existing files. All selected archive sizes/hashes pass before extraction.
ZIP traversal, links/special entries, encrypted entries, case collisions,
unexpected archive roots and excessive expansion are rejected. Executable bits
are normalized from the archive metadata. A successful tree contains
`dependency-receipt.json`. On failure, a new partial tree can remain without that
receipt; it is not an installed/verified toolchain.

The SDK layout follows the actual retained archive roots:

| Archive | Root inside ZIP | Direct-use layout | Gradle/sdkmanager layout |
| --- | --- | --- | --- |
| build-tools 35.0.0 | `android-15/` | `tools/android-sdk/android-15` | `tools/android-sdk/build-tools/35.0.0` |
| platform 35 | `android-35/` | `tools/android-sdk/android-35` | `tools/android-sdk/platforms/android-35` |

Both SDK layouts contain ordinary copied files, not symlinks. In particular,
`android-15` is the historical build-tools archive directory, **not Android API15**.
Current archives were inspected read-only against these layouts. Offline safety
tests materialize only synthetic miniature archives; no real dependency tool was
executed by this sidecar.

JDK17, Xcode/Swift, Python/package locks, the complete Gradle transitive graph,
firmware-derived inputs, signing keys and OAuth registration still require their
separate acquisition/provisioning steps. A null pin in `acquisition.json` is not
an invitation to download an unverified substitute. Gradle may resolve further
dependencies on the first build; this bootstrap does not make it an offline build.

## Current Android build environment

The parent added `tools/android_build_env.py`; Notes, OpenAI, OpenAI
instrumentation, both historical probe builders and the native-corpus preflight
now use it. These environment overrides are explicit local choices:

| Variable | Resolution/meaning |
| --- | --- |
| `JAVA_HOME` | JDK home containing `bin/javac`; otherwise macOS `/usr/libexec/java_home -v 17`, then a `javac`-derived home on other hosts |
| `ANDROID_HOME` | First-choice SDK root |
| `ANDROID_SDK_ROOT` | SDK root fallback if `ANDROID_HOME` is unset |
| `BOOX_ANDROID_BUILD_TOOLS` | Explicit directory containing `apksigner`, `d8`, `aapt`, `zipalign` |
| `BOOX_GRADLE` | Path to the Gradle executable, not its installation directory |
| `GRADLE_USER_HOME` | Gradle cache home; also honored by the optional native-corpus preflight when locating pinned `org.json` |

Without SDK overrides, the resolver uses `tools/android-sdk` relative to the
project. It prefers `platforms/android-35/android.jar` and `build-tools/35.0.0`,
then the historical `android-35/android.jar` and `android-15` layouts.
The Gradle fallback is `tools/gradle-8.11.1/bin/gradle`. An explicitly provided
JDK must actually be JDK17; checking for `javac` alone does not enforce its version.

For the newly bootstrapped dependency tree:

```sh
export JAVA_HOME="/absolute/installed/jdk-17"
export ANDROID_HOME="/absolute/new-output/boox-dependencies/tools/android-sdk"
export BOOX_GRADLE="/absolute/new-output/boox-dependencies/tools/gradle-8.11.1/bin/gradle"
python3 openai-adapter/build.py
python3 notes-drive/android/build.py
```

Those are reproduction commands, not builds executed by this sidecar.
New signing identities change Android update/registration compatibility; do not
copy signing keys into source exports. Installer/doctor host assumptions remain
separate from the new build resolver; the existing installer still expects its
workspace-relative SDK/ADB locations.

`tools/boox_ui.py` now requires `BOOX_SERIAL` before any device subprocess:

```sh
export BOOX_SERIAL="DEVICE_SERIAL"
```

It has no real-device fallback. UI commands remain mutating/live operations and
were not executed here. Other device tools should continue using their own
explicit serial options; setting this variable is not permission to run them.

## Verified portability and publication boundary

The source-only Android build generates synthetic notebook assets; it does not
need private captures. Mac source has no personal Google project/client/folder
bindings. OpenAI, instrumentation, Notes and both historical probes use the shared
build environment resolver. All five APKs and the Mac app built in a fresh local
clone using downloaded, hash-verified Android/Gradle tools and an empty Gradle
cache. Strict offline dependency verification passed with 501 SHA-256 entries.

Historical capture tests require explicit `BOOX_PRIVATE_WORKSPACE`. Mac builds
contain no OAuth configuration unless the optional private build flag is supplied.
Public Markdown is sanitized separately from the preserved original workspace.
The exact public file set is reviewed and scanned; private archive creation uses
a separate tool with complete entry coverage. Xposed compile-only interface
attribution is recorded in [third-party notices](../THIRD-PARTY-NOTICES.md).

Remaining input/order requirements are explicit:

- Use `notes-drive/android/build.py` to generate the connection asset and synthetic
  variants before Gradle tests; a direct fresh Gradle invocation does not perform
  this Python generation. Preserve Mac synthetic generator/reader/editor source
  because the Android fixture generator imports them.
- Build OpenAI production code before its instrumentation APK: tests reuse
  `openai-adapter/build/classes` and the locally generated signing identity.
  No pre-existing private key is required for a new independent source build.
- Compatible installed-app updates still need the existing private signing keys;
  live Drive use needs the user's own OAuth registration/grant. These inputs
  remain excluded from source exports.
- Rebuilding the firmware-specific AMS overlay intentionally requires the exact
  original private `services.jar`. That optional root-support build is separate
  from default app/test builds. Historical corpora are explicitly opt-in.

Regenerate inventory and a new packaging plan after the parent/Mac changes
stabilize. No fresh checkout build, repository initialization or upload is claimed
by this packaging checkpoint.

Offline verification for this continuation: 18 source-packaging tests, 10
bootstrap tests and 58 doctor/setup/AMS/UI-helper tests passed (86 total across
these selected suites). The six previously frozen setup/doctor/helper/AMS tool
and test hashes remained unchanged. All four retained default-bootstrap archives
matched their declared sizes/SHA-256 and all six SDK/Gradle/platform-tools
extraction mappings passed read-only ZIP inspection.
