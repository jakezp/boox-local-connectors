# BOOX Notes Reader for macOS

Read and edit supported BOOX `.note` exports on an Apple Silicon Mac, or connect
to the same Google Drive library as BOOX Notes Drive. Local reading needs no
Google account. Shared editing requires the Android companion and your own
Google OAuth client registrations.

Start with the [project setup guide](../../README.md) for the full Android/Mac
installation. This guide covers the Mac app once its build prerequisites exist.

## Build and open

From the repository root:

```sh
bash notes-drive/macos/build.sh
open 'notes-drive/macos/build/BOOX Notes Reader.app'
```

The tested build uses Xcode 26 / Swift 6.3 on Apple Silicon. The app targets
macOS 13+ and runs its bundled notebook decoder with `/usr/bin/python3` from the
installed developer tools. It is ad-hoc signed, not notarized for distribution.

The default build bundles generated test notebooks and no Google configuration.
`--output-dir DIR` selects another output tree. The advanced `--oauth-config FILE`
option embeds a configuration explicitly; normal use imports it through the UI.
Do not distribute an app containing your private runtime configuration.

Builds preserve the previous bundle under `build/previous-reader.*/`. They do
not quit or launch the app, read Keychain, or change your saved app state.
Restart the app yourself to activate the new executable.

## Open a notebook locally

Choose **Open local .note file…** or open a `.note` file from Finder. For a generated
example, expand **Disposable validation → Open bundled test notebook**.
Use page navigation and zoom controls to inspect the notebook. **Inspect hidden
layers** reveals recorded hidden content for inspection.

The reader displays supported pen samples, color, width and transforms. It shows
notebook/page identity and content information for diagnosis. Unsupported shapes
may appear as placeholders or produce an explicit preview error; a successful
open does not imply perfect native rendering fidelity.

The originally opened file is not overwritten. Use **Export .note copy…** to
write a separate edited export.

## Connect Google Drive

First complete [Google registration](../../docs/GOOGLE-DRIVE-SETUP.md), including
the Android client, Desktop client and an Android-created managed sync directory.

1. Expand **Google Drive connection**.
2. Choose **Import OAuth configuration…** and select your Desktop client JSON.
3. Choose **Connect Google Drive…** and sign into the same Google account used
   on BOOX. Complete any local Keychain prompt normally.
4. Select the same managed directory and choose **Check library now**.
5. Leave **Automatic library updates** enabled.

The app saves its own Google grant in Keychain. It does not import Android or
browser tokens. After rebuilding, macOS may require approval again; choose
**Reconnect saved grant**. **Forget this Mac’s Google grant** removes the local
Keychain grant; it is not a Drive-history deletion command.

A folder name is not unique and a folder ID is not authorization. If no writable
managed directory appears, check the account, Google project and Android
connection. Saved edits remain bound to their original account/destination.

## Edit and save

1. Under **Native notebooks**, open the current verified version of a notebook.
2. Choose **Pen**, a visible unlocked layer, color and width. Draw with a mouse
   or tablet. **Erase stroke** removes an entire supported normal pen stroke.
3. Use **Undo** and **Redo** for completed gestures.
4. Choose **Save to library** to encode and queue a revision.
5. Watch **Saved changes**. Automatic updates publish queued edits; **Send next
   queued edit** explicitly retries the next item.
6. Save/close the native BOOX editor to allow incoming application, then open the
   notebook there to confirm the result.

Completed gestures are saved as a durable draft. A gesture still being dragged
is not yet durable. Saving captures exact revision bytes and starts a new draft
history; Undo does not retract an already queued or published revision.

The queue holds 16 saves. A full queue preserves the draft and earlier work.
Offline saves form a parent chain. Turning off automatic updates also pauses
automatic uploads. **Discard draft** removes draft gestures, not queued snapshots.
**Open latest queued snapshot** lets you inspect saved work before publication.

A local export can join a verified library only if its exact base matches the
sole current head, or if that native notebook is absent from the catalog. For
an existing shared notebook, open its library version before editing.

## Create and organize notebooks

Connect and obtain a verified catalog in the current app session first. Existing
drafts/queues recover on an offline launch, but creation and metadata dialogs
need a verified catalog to validate destination folders.

| Action | How it works |
| --- | --- |
| **New notebook…** | Choose a title, parent folder and 1, 2, 5, 10, 20 or 32 blank pages. New pages are 1860 × 2480 with one unlocked pen layer. |
| **Rename or move…** | Retains native identity and content, changing the selected title/parent. |
| **Request deletion…** | Queues a recoverable deletion for native Recycle Bin application. It does not erase Drive history. |
| **Restore notebook…** | Restores from retained ancestry under the same native identity; choose a new parent if necessary. |
| **New folder…** | Creates a native folder record with the selected parent. |
| Folder **Request deletion…** | Available only when the folder has no live children. |
| **Restore folder…** | Restores a retained folder record and identity. |

Save or discard the affected notebook's pen draft/history before changing its
metadata or deleting it. Even a fully undone draft can retain redo history.
Unrelated notebook drafts survive library management. Queued lifecycle changes
remain visible as pending work, distinct from verified Drive versions.

## Automatic following and conflicts

While the app runs, healthy refreshes begin about 12 seconds after the previous
operation finishes. Failures back off to a maximum five-minute interval. There
is no app-independent daemon; quitting the Mac app stops its automatic work.

An open library document follows a single verified descendant and preserves its
page and zoom. Active editing, draft history, pending work, conflicts or deletion
states prevent unreviewed replacement. A local file opened independently is not
automatically replaced by a remote notebook.

Concurrent versions remain accessible in the library. A queued save whose base
advanced pauses for review. **Publish saved branch with conflict** explicitly
retains its captured parent and preserves both branches; it does not merge them.
Use Android's **Review conflicting versions** for its explicit resolution action.

A failed refresh keeps the prior verified library. Read the sidebar's status and
queue message before retrying. Do not delete cache, journals or Drive files to
choose a conflict winner.

## Limits and preservation

Supported editing is limited to normal pens in declared pages and visible
unlocked layers. The app does not restructure pages/layers in existing notebooks,
edit rich text/media/templates, perform pixel erasing, reproduce pressure brushes,
or edit infinite-canvas metadata. New coordinates must stay within a declared page.

Untouched archive member contents and unknown protobuf fields are preserved when
supported edits are written. Preserving opaque bytes does not imply the app can
render or edit that content. Notebook identity, embedded ancestor folder rows
and existing native IDs are retained.

Limits include 4 MiB base/output payloads, 1,000 Drive objects, 64 MiB per refresh,
16 queued saves and a 100 MiB local journal budget. History growth consumes Drive
objects. There is no automatic history pruning, notebook chunking or general
migration for oversized libraries.

## Check the installation and develop

Complete the [first notebook round trip](../../README.md#8-check-your-first-notebook-round-trip)
to verify your own account, destination and native application. For the synthetic
suite, run from the repository root:

```sh
bash notes-drive/macos/test.sh
```

The recorded current suite has 162 mandatory checks. It uses generated notebooks
and disposable local state, not your live Google grant. See
[synthetic tests](SYNTHETIC-TESTS.md) for explicit optional corpus checks,
[the technical brief](../../docs/TECHNICAL-BRIEF.md) for architecture and
[validation evidence](../../docs/VALIDATION.md) for the tested live behavior.
