# BOOX AI use OpenAI and Google Drive Cloud Sync

Use your own OpenAI API or ChatGPT/Codex subscription instead of the default BOOX AI model in the native reading apps, and set up a replacement Google Drive sync inside the Native Notes app as an alternative to the ONYX Cloud sync.

The project provides three apps:

| App | What it adds |
| --- | --- |
| **BOOX OpenAI Setup** · Android | Connects the native AI Assistant and NeoReader AI panel to your OpenAI API account or the connector's ChatGPT sign-in mode. |
| **BOOX Notes Drive** · Android | Publishes saved native notebooks to Google Drive and applies incoming changes when the Notes editor is closed. |
| **BOOX Notes Reader** · macOS | Opens BOOX notebooks, supports pen editing, and follows the same Drive library automatically while the app is running. |

You continue reading and writing in the native BOOX apps. The Android companions
use runtime hooks to change their integration points; they do not replace the
Assistant, NeoReader or Notes APKs. You can install the AI connector, Notes sync,
or both. The Mac app can also open local `.note` exports without a Google account.

**This is a source-built project for a specific rooted BOOX configuration.**
It is not a general-purpose installer for every BOOX model. Start by checking
compatibility below; do not apply its firmware-specific changes to another device.

## Contents

- [Before you start](#before-you-start)
- [1. Get the source and prepare your Mac](#1-get-the-source-and-prepare-your-mac)
- [2. Build the apps](#2-build-the-apps)
- [3. Prepare the BOOX tablet](#3-prepare-the-boox-tablet)
- [4. Set up Google Drive access](#4-set-up-google-drive-access)
- [5. Install the Android companions](#5-install-the-android-companions)
- [6. Connect AI Assistant and NeoReader](#6-connect-ai-assistant-and-neoreader)
- [7. Connect Notes and the Mac app](#7-connect-notes-and-the-mac-app)
- [8. Check your first notebook round trip](#8-check-your-first-notebook-round-trip)
- [Everyday use](#everyday-use)
- [Troubleshooting](#troubleshooting)
- [Technical brief](#technical-brief)

## Before you start

### Device and computer requirements

| Requirement | Supported configuration |
| --- | --- |
| BOOX hardware | **Note Air4 C**, reported by Android as `NoteAir4C` |
| Firmware | **`2026-04-28_17-50_4.2-rel_04282_555977efe`** |
| Android / native Notes | Android 13, API 33; Notes version code **45326** |
| Root and framework | Working Magisk root, the matching AMS fix, and Vector **2.2 / 3080** |
| Installer checks | Active slot `_b`; Magisk version code **30200 or 30700** |
| Build computer | Apple Silicon Mac; macOS 13+; Python 3.10+, JDK 17 and Xcode command-line tools with Swift |
| Device connection | USB data cable, USB debugging enabled, and ADB authorization on the tablet |
| Notes sync | Google account, Google Cloud project, and working Google Play services on BOOX |
| AI connection | Your own OpenAI API access, or account access compatible with the custom ChatGPT sign-in route |

The Mac build was exercised with Xcode 26 / Swift 6.3. Other host toolchains,
BOOX models, firmware versions and partition layouts have not been established
as compatible. The installer rejects unsupported device configurations.

### A few terms used in this guide

- **ADB** is Android's command-line connection from your Mac to the tablet.
- **Root / Magisk** provides the elevated access needed by the native hooks.
- **AMS fix** is a firmware-specific Android framework patch used by this rooted configuration.
- **Vector** loads the companion's hooks inside the selected native apps.
- **Signing key** identifies the Android app you build. Keep it for future updates.
- **OAuth client** identifies an app to Google. It is separate from signing into your Google account.
- **Backup receipt** describes an actual device backup that the guarded installer checks before changing apps.

### Choose your route

For **AI only**, follow steps 1–3, the AI installation route in step 5, and step 6.
For **Notes sync**, follow steps 1–5 and 7–8. For **local Mac reading only**, build
and open the Mac app in step 2, then choose **Open local .note file…**; no root,
Android companion or Google setup is needed for that use.

If the tablet is not already rooted, step 3 is a separate advanced prerequisite.
The available root guide records the known device procedure and its gaps; it is
not a complete beginner bootloader-unlock or universal recovery procedure.

## 1. Get the source and prepare your Mac

Open Terminal. Commands below run from the repository root unless stated otherwise.

```sh
git clone https://github.com/jakezp/boox-local-connectors.git
cd boox-local-connectors
python3 --version
/usr/libexec/java_home -v 17
xcrun swift --version
```

Install any missing Python 3.10+, JDK 17 or Xcode command-line tools before
continuing. `xcode-select --install` opens Apple's command-line-tools installer.
If Swift compilation fails with your toolchain, compare it with the tested
version above before changing app code.

Create a local working area outside the checkout for dependency downloads,
installation plans and backups. Use a new directory for the first setup:

```sh
export BOOX_SETUP="$HOME/boox-setup"
mkdir -m 700 "$BOOX_SETUP"
mkdir -m 700 "$BOOX_SETUP/review"
export BOOX_REVIEW="$BOOX_SETUP/review"
export BOOX_DEPS="$BOOX_SETUP/dependencies"
export GRADLE_USER_HOME="$BOOX_SETUP/gradle-cache"
```

The dependency script first writes a plan, then downloads exactly the reviewed
archives and checks their sizes and hashes:

```sh
python3 tools/bootstrap_dependencies.py > "$BOOX_REVIEW/dependencies-plan.json"
cat "$BOOX_REVIEW/dependencies-plan.json"
python3 tools/bootstrap_dependencies.py --apply \
  --destination "$BOOX_DEPS" \
  --reviewed-plan "$BOOX_REVIEW/dependencies-plan.json"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$BOOX_DEPS/tools/android-sdk"
export BOOX_GRADLE="$BOOX_DEPS/tools/gradle-8.11.1/bin/gradle"
ln -s "$BOOX_DEPS/tools/android-sdk" tools/android-sdk
ln -s "$BOOX_DEPS/tools/platform-tools" tools/platform-tools
```

The two symlink commands are for a fresh checkout where those paths do not exist.
Keep existing tools intact when resuming a setup. Do not recreate the dependency
directory: the bootstrap intentionally refuses to overwrite it. Keep this Terminal
session open so later commands retain these environment variables.

**Ready to continue:** Python, Java and Swift report versions, and the dependency
bootstrap finishes successfully. See [dependency setup](docs/reproduction/README.md#1-acquire-the-host-tools)
for offline downloads and alternative paths.

## 2. Build the apps

Run only the builds for the features you want:

```sh
# AI connector
python3 openai-adapter/build.py

# Notes connector; also runs its Android unit tests
python3 notes-drive/android/build.py

# Mac reader/editor
bash notes-drive/macos/build.sh
```

| Build | Result |
| --- | --- |
| AI connector | `openai-adapter/build/boox-openai-setup.apk` |
| Notes connector | `notes-drive/android/app/build/outputs/apk/release/app-release.apk` |
| Mac reader/editor | `notes-drive/macos/build/BOOX Notes Reader.app` |

On a first build, each Android builder creates its own `local-signing.p12` in
its component directory. Preserve those files securely. On an update, use the
original keys before building; a newly generated key cannot update the existing
installation. Do not share your keys or Google client configuration in a fork.

The Notes build also writes `notes-drive/android/registration.json`. You will
use its `signing_certificate_sha1` in the Google setup step. Use your generated
value, not a fingerprint from a development log or someone else's build.

You can try the Mac reader immediately:

```sh
open 'notes-drive/macos/build/BOOX Notes Reader.app'
```

Choose **Open bundled test notebook** under **Disposable validation** to inspect
a generated example. This does not connect Google Drive or touch the tablet.

## 3. Prepare the BOOX tablet

Save your work and enable USB debugging in the tablet's developer settings.
Connect it with a data cable, approve its USB debugging prompt, then list devices:

```sh
tools/platform-tools/adb devices
export BOOX_SERIAL='<replace-with-the-serial-shown-by-adb>'
python3 tools/boox_doctor.py --serial "$BOOX_SERIAL"
python3 tools/boox_doctor.py --serial "$BOOX_SERIAL" --root-checks
```

`unauthorized` means the tablet still needs to approve the Mac. A missing device
usually means the cable, USB mode or debugging setting needs attention.
The doctor reads device metadata; it does not install or root anything.

Before installation, you need working root, the matching AMS overlay and Vector.
If any are missing, follow the [root and framework preparation](docs/reproduction/README.md#3-establish-root-ams-and-vector-prerequisites),
using the [root/recovery reference](docs/reproduction/ROOT-RECOVERY.md) and
[Magisk staging guide](docs/ROOT-STAGING.md). These require your own exact
original firmware inputs. Never use another device's partition offsets or
bypass a firmware/hash rejection.

The original procedure started with an already-unlocked bootloader. Its earlier
Magisk support-file repair and later upgrade were not fully recorded. If your
starting point depends on those missing steps, stop here rather than treating
this repository as a complete root installer.

**Ready to continue:** your device matches the supported baseline, root queries
work, the AMS overlay is active, and Vector is installed and enabled.

## 4. Set up Google Drive access

Skip this step for AI-only use. For Notes sync, you register the apps you built
in **your own Google Cloud project** before signing in through each app.

Follow [Google Drive setup](docs/GOOGLE-DRIVE-SETUP.md). It walks through:

1. Creating a project and enabling the Google Drive API.
2. Configuring the consent screen, audience and test users.
3. Creating an **Android** OAuth client for package `local.boox.notesdrive` and
   the certificate SHA-1 from your Notes build.
4. Creating a **Desktop app** OAuth client in the same project and downloading
   its JSON configuration for the Mac.

The apps request `drive.file`, which grants access to app-authorized files. A
folder URL alone does not give them access to an arbitrary existing directory.
Let the BOOX companion create its managed sync directory during step 7.

**Ready to continue:** Android registration matches your APK, and you have your
Desktop client JSON. Do not copy a browser token or another app's grant.

## 5. Install the Android companions

The installer has three phases: **plan → preflight → apply**. Planning checks the
built APKs locally. Preflight checks the connected device without installing.
Apply installs the selected companions and verifies the resulting state.

First save and close Notes, AI Assistant and NeoReader editors. Create a current
backup and a device-bound receipt following the
[required backup receipt](docs/reproduction/SETUP-CLI.md#required-backup-receipt).
The example JSON is a template, not a backup. The installer verifies supplied
files but does not create them. If you cannot produce a current backup and
review its restoration procedure, do not proceed to apply.

For a **new installation of both companions**, use:

```sh
python3 tools/boox_setup.py \
  --install notesdrive --first-install notesdrive \
  --install openai --first-install openai \
  --write-plan "$BOOX_REVIEW/first-install-plan.json"
cat "$BOOX_REVIEW/first-install-plan.json"
```

After reviewing that plan, replace the backup path below with your actual receipt:

```sh
export BOOX_BACKUP_RECEIPT='/absolute/path/to/current-backup/receipt.json'
python3 tools/boox_setup.py \
  --install notesdrive --first-install notesdrive \
  --install openai --first-install openai \
  --preflight --serial "$BOOX_SERIAL" --editors-closed \
  --registration-confirmed \
  --reviewed-plan "$BOOX_REVIEW/first-install-plan.json" \
  --backups "$BOOX_BACKUP_RECEIPT"
```

If preflight succeeds, install:

```sh
python3 tools/boox_setup.py \
  --install notesdrive --first-install notesdrive \
  --install openai --first-install openai \
  --apply --serial "$BOOX_SERIAL" --editors-closed \
  --registration-confirmed \
  --reviewed-plan "$BOOX_REVIEW/first-install-plan.json" \
  --backups "$BOOX_BACKUP_RECEIPT" \
  --report "$BOOX_REVIEW/first-install-result.json"
```

For **AI only**, omit both Notes flags and `--registration-confirmed` from all
three commands. For **Notes only**, omit both OpenAI flags. Choose the backup
roles matching your selection. The first-install flags require those companions
to be absent; for existing installations follow the
[update procedure](docs/reproduction/SETUP-CLI.md#plan-review-preflight-apply)
instead. Do not uninstall an existing app just to fit the first-install route.

The first-install and combined installer routes have automated offline tests;
the existing Notes companion update route has live device validation. A fresh
device installation has not been rehearsed end to end.

**Ready to continue:** the report shows installation completed. Its status
`installed_pending_acceptance` is intentional: account setup and the checks
below still need to succeed on your device.

## 6. Connect AI Assistant and NeoReader

Open **BOOX OpenAI Setup** on the tablet. If BOOX froze the app, unfreeze it in
the launcher first. Choose one connection mode:

| OpenAI API | ChatGPT subscription |
| --- | --- |
| Create an API key in your own OpenAI account. See the [official API quickstart](https://developers.openai.com/api/docs/quickstart). | Choose **Sign in with ChatGPT**. |
| Select **OpenAI API**, enter the key, and enter an API model ID available to your account. | Use **Copy code** and **Open OpenAI sign-in** to complete sign-in on OpenAI's page. Return to the setup app. |
| API usage is billed to the API account. | Use **Refresh available models** and select from the returned dropdown. |

ChatGPT mode is this project's custom integration with Codex subscription access;
it is not an official BOOX or OpenAI connector. Availability depends on your
account and the upstream service. It does not automatically fall back to API
billing when a subscription request fails.

Then:

1. Tap **Test selected connection** and check that it succeeds.
2. Tap **Use selected connection** to activate it.
3. Close and reopen the native **AI Assistant**. Ask a simple question, then a follow-up.
4. In **NeoReader**, open a document, select a passage and open its AI panel.
   Ask a question about that passage and check that the response uses its context.

The BOOX disclaimer text is hidden while the space below the input is retained.
This changes the static interface text; it does not remove words from AI replies.

## 7. Connect Notes and the Mac app

### On BOOX

1. Open **BOOX Notes Drive** and tap **Connect Google Drive**.
2. Sign into the Google account you configured as an allowed user.
3. Tap **Create BOOX Notes Sync directory**, or select an existing managed
   directory returned by the app. Reuse that same destination on other clients.
4. Tap **Test Drive round trip**. This checks transport using disposable data;
   it does not yet prove native notebook synchronization.
5. Enable **Automatically publish saved notebooks** and
   **Automatically apply incoming notebook edits**.

### On the Mac

1. Open the built **BOOX Notes Reader** app.
2. Expand **Google Drive connection** and choose **Import OAuth configuration…**.
   Select the Desktop client JSON from step 4.
3. Choose **Connect Google Drive…** and complete the app's own browser sign-in.
   Approve a local Keychain prompt if macOS asks.
4. Select the same managed sync directory created on BOOX. Check its identity,
   not just its name; two folders can have identical names.
5. Leave **Automatic library updates** enabled and the app running.

The Mac's Google authorization is independent of Android's. It keeps its saved
grant in Keychain. If a rebuilt app needs access again, use **Reconnect saved
grant** rather than importing tokens manually.

## 8. Check your first notebook round trip

Use a new notebook named something obvious such as **Sync test**:

1. Create it in native BOOX Notes. Save it and return to the library so its editor closes.
2. Wait for it to appear under **Native notebooks** on the Mac, then open it.
3. Choose **Pen**, draw a line, try **Undo** and **Redo**, then **Save to library**.
4. Wait for the Mac's saved-change queue to clear. On BOOX, open the same notebook
   and confirm the line is visible in stock Notes.
5. Save and close it on BOOX. Check that its returned version reaches the Mac.
6. With the Mac in Read mode, rename the notebook on BOOX and close the native
   editor. Confirm the open Mac document updates without a manual reopen.

Do this before relying on sync for your regular library. If a step fails, inspect
the status on both apps and use the troubleshooting table below. A successful
Google connection alone does not establish that native hooks are active.

## Everyday use

- **Write on BOOX:** save and leave the notebook editor to publish changes and
  allow incoming edits to apply. Device sleep can delay synchronization.
- **Edit on Mac:** choose a visible unlocked layer, use Pen or **Erase stroke**,
  then **Save to library**. Erasing removes whole normal pen strokes.
- **Work offline:** completed Mac gestures are kept as a draft; saved revisions
  are queued for later publication. Unsaved work is not a published revision.
- **Manage your library:** Mac controls support notebook/folder creation,
  rename/move, recoverable deletion and restoration. Folder deletion requires
  an empty folder. See the [Mac user guide](notes-drive/macos/README.md).
- **Handle conflicts:** concurrent edits remain separate versions. On Android,
  use **Review conflicting versions**. Do not delete Drive objects to choose a winner.
- **Pause sync:** turn off the relevant automatic controls. Turning off Mac
  automatic updates pauses both its automatic downloads and uploads.
- **Update apps:** retain signing keys, save/close editors, make a current backup,
  rebuild and follow the update installer route. Restart the Mac app after rebuilding.

Sync is automatic, but it is not simultaneous live co-editing. Incoming changes
wait for a safe editor state. The Mac must remain running; no background daemon
is installed. Keep the managed Drive directory for the protocol's files rather
than manually editing, renaming or deleting its payloads.

### Current limits

Notebooks are limited to **4 MiB per payload**, **200 Android-managed items**,
**1,000 Drive objects**, and **64 MiB per verified catalog refresh**. Retained
history consumes objects; automatic history pruning is not implemented.

The Mac supports normal pen editing and whole-stroke erasing. It does not provide
full native rendering parity, pressure-brush reproduction, rich-media editing,
or page/layer restructuring in existing notebooks. Unsupported content can be
preserved without being fully rendered. See [supported scope](docs/reproduction/GAPS.md).

## Troubleshooting

| What you see | What to check |
| --- | --- |
| No device / `unauthorized` in ADB | Data cable, USB debugging and the authorization prompt on BOOX. |
| Firmware, signature or scope rejection | Compare the device with the compatibility table; for updates restore the original signing identity. Do not bypass the guard. |
| Google sign-in fails on Android | The Cloud project, allowed test user, package name and SHA-1 must match the APK you installed. |
| No managed folder on Mac | Use the same Google account/project, create the directory through BOOX, then reconnect. A pasted folder ID is not authorization. |
| BOOX receives nothing | Enable incoming application, save/close the native editor, and inspect **Retry incoming updates** and conflict status. |
| Mac receives nothing | Keep it running with **Automatic library updates** enabled; use **Check library now** and read the reported error. |
| A Mac save remains queued | Check connectivity and destination access. Use **Send next queued edit**; review a conflict before publishing a branch. |
| Keychain prompt after rebuilding | Use **Reconnect saved grant** and approve access normally on the Mac. |
| API test works but native AI does not | Activate **Use selected connection**, reopen both native surfaces, and verify the Vector scopes in the [installer guide](docs/reproduction/SETUP-CLI.md). |
| Notebook will not render or publish | Check the payload limits and supported shape/layer types; retain the original export for investigation. |

For failed installations, use the report and
[recovery guidance](docs/reproduction/SETUP-CLI.md#failure-and-recovery-contract).
Do not erase queues, uninstall companions or restore an old whole library as a
first troubleshooting step.

## Technical brief

The implementation has three layers:

1. **Native integration.** Vector loads the Android hooks into Assistant,
   NeoReader and Notes. The AI connector handles the native conversation bridge;
   Notes hooks route saved snapshots and sync controls through the Drive companion.
2. **Immutable synchronization.** Drive stores checksummed notebook payloads and
   revision records with parent relationships. Clients validate those objects
   before accepting them. Concurrent revisions remain conflicts; timestamps do
   not select a winner.
3. **Native application and editing.** Android applies incoming snapshots through
   a journaled transaction when editors are idle, then verifies the native result.
   The Mac decodes native exports, preserves identities and unknown data during
   supported edits, and queues new revisions using its own Google authorization.

Read the [detailed technical brief](docs/TECHNICAL-BRIEF.md) for the root/framework
work, AI and NeoReader changes, Notes transaction design, Mac editor and
reproducible build tooling. [Validation evidence](docs/VALIDATION.md) records the
specific tests and historical checkpoints separately from these setup instructions.

The recorded automated suites pass **57 Android**, **162 Mac**, **137 host-tool**
and **19 protocol/prototype** checks. Separate source clones built the Android
APKs and Mac app. Live checks covered bidirectional pen edits, library lifecycle,
conflict handling, crash recovery and automatic Mac following. This does not
establish arbitrary-device compatibility or a fresh-device root installation.

### Source map and development

| Directory | Contents |
| --- | --- |
| `openai-adapter/` | AI connector, subscription/API transports and native UI hooks |
| `notes-drive/android/` | Google Drive companion, revision queues and native Notes integration |
| `notes-drive/macos/` | Swift app, native notebook decoder/writer and synthetic tests |
| `notes-drive/tests/`, `prototype/`, `probe/`, `apply-probe/` | Protocol checks, fixtures and retained research tools |
| `tools/` | Dependency bootstrap, builders, diagnostics, guarded installation and packaging |
| `docs/` | Setup references, architecture, validation and firmware-specific recovery guidance |

For all build/test commands, including optional instrumentation and research APKs,
see [build verification](docs/reproduction/README.md#2-build-apps-and-run-portable-checks).
See [third-party notices](docs/THIRD-PARTY-NOTICES.md) for interface attribution.
