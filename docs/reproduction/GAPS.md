# Remaining limits and acceptance work

Current checkpoint: 2026-09-13. This distinguishes tested functionality from
historical evidence and work that has not been demonstrated.

| Area | Established | Remaining boundary |
| --- | --- | --- |
| Core Notes synchronization | Real Mac UI pen add/erase, undo/redo, automatic publication, native same-ID readback and visible opening, automatic BOOX-to-Mac return, notebook creation/rename/move/recoverable deletion/restore; seven native crash checkpoints and Android all-head conflict selection | Final Mac v0.6 interactive nested-export acceptance awaits its macOS Keychain prompt. Its decoder/writer fix passes synthetic tests and actual retained native export checks. |
| Clean source | All five authored Android APKs and the Mac app built from a separate local Git clone. Freshly downloaded hash-verified SDK/Gradle, empty Gradle cache and new signing identities; no private notebook/configuration inputs | This used the existing Mac's installed JDK/Xcode/Python, not a newly provisioned OS. GitHub creation and a remote clone remain pending final live acceptance. |
| Mandatory tests | 162 Mac checks, 57 Android unit tests, 115 host checks and 19 portable prototype tests passed | One host and 21 prototype private-input cases intentionally skip by default. Separate historical private suites do not count as portable tests. |
| Root staging | Exact original boot/APK guards, 15 asset hashes, 28 staging checks including actual offline equality. Official Magisk v30.2 download matches the saved APK | A fresh device patch/flash was not repeated on the working tablet. Original stock firmware inputs and an already-unlocked bootloader are required; no unlock procedure was tested. |
| Magisk support repair / 30.7 | Historical successful root/UI and later observed 30.7 state | Exact earlier missing-file repair actions and the 30.7 upgrade sequence were not recorded. Do not claim a bit-identical recreation of the current rooted boot. |
| GPT and boot recovery | Original boot/selected partition images and B-only flash/readback evidence retained | The historical EDL command did not actually write GPT backup files. Full recovery/OTA rehearsal remains untested. |
| AMS module | Exact original/patched JAR hashes, parameterized builder, bytecode and unchanged-entry checks | New-firmware analysis and a new live module installation were not performed. Disable the firmware-specific module before OTA. |
| Installer | Existing NotesDrive wrapper preflight/apply passed; later synthetic-asset build installed with current-process hook verification | First-install paths and the OpenAI wrapper were not exercised live. Root, flashing and OAuth remain explicit separate stages. |
| OAuth | Each client owns its grant; actual Mac and Android publication passed | Long-term expiry/revocation and unattended multi-day operation are not established. A rebuilt ad-hoc-signed Mac app can require Keychain permission. |
| Scale | Bounded immutable protocol and verified content cache, durable offline queue | 4 MiB notebook payloads, 200 Android items, 1,000 Drive objects, 64 MiB verified catalog and 16 Mac queued saves. Unlimited history/large-library operation is not implemented. |
| Editing/rendering | Native pen coordinates, nominal ink, whole-stroke erase, IDs and retained opaque data | Mac rich text/media/templates/pressure-brush reproduction, pixel erasing and page/layer topology editing are unsupported. Physical BOOX stylus entry on a freshly Mac-created blank was not proven. |
| Sync availability | Android save hooks and background jobs; Mac polling while running | Incoming native changes wait until editors close. BOOX sleep may defer work; the Mac does not sync when closed. |
| Distribution | Reviewed authored source, synthetic fixtures, dependency pins and private staging | Credentials, personal notebooks, firmware/decompilation, installed signing keys and private evidence stay outside Git. Acquisition does not grant redistribution rights. |

Read the newest root README and handover first. Earlier validation documents
retain historical checkpoints, including superseded locked-Mac and outbound-only
milestones. Private evidence is retained in the original working directory; it is
not silently included in the source repository.
