# Native Google Drive sync in Notes Settings

Open the BOOX Notes library, choose **Notes Settings**, and find **Google Drive
Sync** directly below **ONYX Cloud**. The section uses the same native row,
switch and information-icon styling as the ONYX section.

- **Sync Switch** controls both automatic publication and incoming application.
  Turning it off pauses both directions while retaining queued work.
- The **ⓘ information icon** opens **BOOX Notes Drive** for Google sign-in,
  directory selection, status and conflict review.
- If setup is incomplete, turning sync on opens setup rather than pretending
  the device is connected. If a sync operation is busy, retry after it finishes.
- Returning to Notes Settings reloads the persisted switch state.

The companion describes BOOX-to-BOOX sync. Its normal status shows stored
notebooks, folders, pending uploads and conflicts. Optional disposable revision
tests are collapsed under **Show / hide sync diagnostics**. No Mac is required
to run the synchronization service.

## Link another BOOX

1. Prepare another supported device and install the same signed companion APK.
   If you build with a different signing key, register that certificate with Google.
2. Open **Notes Settings → Google Drive Sync → ⓘ**.
3. Sign into the same Google account and select the **existing managed sync
   directory**. Do not create a separate directory for each device.
4. Enable the native **Sync Switch**. In setup, both automatic options should be on.
5. Open Notes, keep the device awake and online, and leave notebook editors closed
   while incoming content is applied. Folders and supported notebooks are discovered
   from Drive even when the device has no local sync history.
6. Confirm the library has downloaded, then edit a disposable notebook and verify
   the return change on the original device before relying on the new pairing.

Use the same account/project and managed directory across the participating
clients. Google authorization is obtained independently on each device. The Mac
reader is an optional validation/client app; it is not a server or relay.

The existing limits still apply: unlocked supported notebooks up to 4 MiB,
200 Android-managed items, 1,000 Drive objects and 64 MiB per catalog refresh.
Conflicts require review; sleep and open editors can delay changes. This is
asynchronous bidirectional sync rather than simultaneous collaborative editing.

## Why the built-in export integration was not the whole solution

The third-party path was a starting point for the native serializer. Inspection
of `SyncNoteFileToThirdPartyNoteAction` showed format-5 `.note` export, followed
by `ExportNoteToFileAction` and an upload handoff. The connector reuses the
native snapshot machinery rather than treating a PDF export as editable sync.

That export path does not supply the protocol needed to download another
device's changes into the same notebook, retain identity and ancestry, detect
concurrent edits, or recover interrupted native application. Normal native
import also creates a new notebook identity. Repeatedly importing exported
files would therefore not provide reliable same-notebook synchronization.

The companion supplies those missing responsibilities: its own Google grant,
a shared directory, immutable revisions, durable queues and a native apply
journal. ONYX Cloud uses a different replication system, so replacing its URL
with a Drive folder would not implement the same behavior. See the
[technical brief](TECHNICAL-BRIEF.md) and [protocol](../notes-drive/PROTOCOL.md).

## Firmware and installation details

The main Notes Settings page is hosted by `com.onyx`, launcher version **56737**.
The notebook editor remains `com.onyx.android.note`, version **45326**. This
integration targets those inspected versions on the documented firmware.

The shared Notes/Reader Drive companion needs these three Vector scopes:

```text
com.onyx.android.note/0
com.onyx/0
com.onyx.kreader/0
```

For a new installation, the setup CLI includes all three. For an existing installation
with only the editor scope, save/close editors and preserve a current backup,
then explicitly migrate the scope before the normal reviewed update flow:

```sh
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli scope set local.boox.notesdrive com.onyx.android.note/0 com.onyx/0 com.onyx.kreader/0"'
```

The installer checks the launcher version and exact scopes. It refreshes the
Notes hook and restarts the launcher, verifying the current build's markers in
all three processes. This briefly returns the tablet to its launcher. A package
installation alone does not prove that either hook has loaded.

The launcher receives a settings-only hook. Because it shares Android's system
UID, the bridge permits that UID only the `syncSettings` and `readerSettings` operations on the
supported package versions. That operation exposes switch state and configuration
changes, not notebook bytes, account identifiers or credentials. All other bridge
operations retain the existing dedicated-Notes authorization checks.

## Validation

On the supported device, the section appeared immediately below ONYX Cloud;
the info icon opened setup; off/on changed both automatic settings; and the
state refreshed after returning. The original third-party export controls remain
in their own section. The UI uses native adapter models rather than overlaid
screen coordinates or a replacement settings activity.

The Android suite includes an empty-local-state test discovering the current
heads for multiple notebooks and a folder without any pre-existing local branch.
This is not a complete physical second-BOOX acceptance test. The latter remains
the final check when another device is linked. No existing library was erased
or replaced with an empty one to simulate that test.

Reading sync has a separate switch in native Library Settings. See
[books and reading data setup](READER-DRIVE.md) for pairing and the additional
NeoReader version requirement.
