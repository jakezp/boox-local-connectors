# Supported scope and remaining acceptance work

Checkpoint: **2026-09-13**. The [setup guide](README.md) provides the operational
sequence. [Validation evidence](../VALIDATION.md) records source/build artifacts;
the current acceptance boundaries are summarized here.

## Established results

A separate source-only Git clone built all five Android APKs and the Mac app.
It used freshly downloaded hash-verified SDK/Gradle archives, an empty Gradle
cache, synthetic notebooks and new local signing identities. Tests passed:
**57 Android, 162 Mac, 137 host and 19 portable prototype**. One optional host
private-input case and 21 historical prototype cases skip by default.
Dependency verification now pins **501 SHA-256 entries across 291 components**;
the Android tasks passed with strict verification in offline mode. The pre-publication
documentation baseline is `3fa9050`; detailed build provenance
identifies the earlier clean-build checkpoint separately.

**Mac v0.6 nested-notebook own-OAuth UI acceptance passed.** Draw/undo/redo/save
published `dc3b267a8ea4…`; native application committed one pen under the same
notebook ID, and stock Notes displayed it. Normal native close produced
`2cab97843ab9…`, which the Mac automatically downloaded and then opened from the
library. A subsequent native UI rename produced `009b9ae05cad…` and automatically
updated the already-open Mac document title, retaining page, zoom, one pen and
two samples without refresh or reopening. The bundled test notebook loaded in
the GUI with two pages and 343 samples. All v0.6 live acceptance gates are
complete. Earlier live tests cover add/erase, notebook and folder lifecycle,
seven native crash checkpoints and conflict selection preserving both heads.
The recorded original-data preservation checkpoint matched 182 files and all
fields in three notebook rows.

## Remaining boundaries

| Area | Boundary |
| --- | --- |
| Build environment | The clean clone used existing Python/JDK/Xcode installations. Fresh operating-system provisioning was not tested; original host-tool archive provenance and the complete EDL/AMS Python dependency lock remain incomplete. |
| Companion installer | Existing NotesDrive wrapper preflight/apply passed, and the synthetic-asset APK passed current-process hook verification. First-install, OpenAI wrapper and combined wrapper routes have offline tests only. The installer does not create backups, provision root/AMS/Vector, perform OAuth or configure the Mac. |
| Root staging | Exact original boot/APK guards and 15 asset comparisons passed; all 28 staging tests passed with the optional originals enabled. The official Magisk v30.2 APK download matches the saved installed APK. No new device patch or flash was performed. |
| Firmware and unlock | The demonstrated root path starts with an already-unlocked NoteAir4C on the exact recorded firmware, active B. Other firmware/devices and bootloader unlocking are outside the validated procedure. |
| Magisk support repair / 30.7 | Historical successful root/UI and later 30.7 were recorded. The exact earlier missing files/repair commands and later upgrade sequence were not. Staging does not establish bit-identical recreation of the current rooted boot. |
| GPT, recovery and OTA | Historical B-only flash/readback evidence and selected original partition images exist. The historical EDL command wrote no GPT backup payloads. Full recovery, OTA and root preservation across OTA have not been rehearsed. |
| AMS overlay | The builder checks exact original/patched JAR hashes and the bytecode change. A new module installation was not repeated, and new firmware needs separate analysis. Disable/remove the firmware-specific overlay before OTA. |
| OAuth lifetime | Independent Android/Mac authorization and real publication passed. Long-term expiry/revocation and unattended multi-day operation are not established. A rebuilt ad-hoc-signed Mac app may require a local Keychain prompt. |
| Scale | Limits are 4 MiB per notebook, 200 Android-managed items, 1,000 Drive objects, 64 MiB per verified catalog and 16 Mac queued saves. Unlimited history and large-library operation are not implemented. |
| Editing and content | Mac normal pen editing, whole-stroke erase, IDs and retained opaque data are supported. Full pressure-brush rendering, rich-text/media/template editing, pixel erasing and page/layer topology editing are unsupported. Locked/associated notebooks and unsupported metadata remain held for review. |
| Physical stylus | Mac mouse-drawn pen acceptance does not establish physical BOOX stylus entry on a freshly Mac-created blank notebook. |
| Availability | Native incoming changes wait for editors to close. BOOX sleep can defer work; Mac sync runs while the app is open. |

The preservation result refers to its recorded checkpoint; later user edits need
current backups. Historical revision hashes are evidence references, not bases
to reuse for new mutations. Root details are in
[ROOT-RECOVERY.md](ROOT-RECOVERY.md) and [ROOT-STAGING.md](../ROOT-STAGING.md).
Earlier dated validation documents retain narrower checkpoints and should be
read with these current boundaries.
