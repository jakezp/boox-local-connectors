# Supplemental Mac own-grant transport validation

**Private-project validation utility.** This CLI intentionally pins the Desktop
client registered for this development project. It is not a general public
publisher and will refuse an unrelated installed-client configuration. Public
distribution should disable/omit this validation CLI or add a separately
reviewed saved-client/own-Keychain registration mechanism. Do not bypass this
check with client, token, account or destination overrides; exact saved binding,
scope and current-head checks remain required.

Version 0.4.1 adds an opt-in command to the app executable. It is intended for
the parent to exercise the Mac's own saved Google grant while interactive
desktop testing is unavailable. **This agent has not run the live command.**
Automated tests use a synthetic grant provider and fake HTTP.

The CLI exits before creating ReaderModel, a window or the live LocalStore.
The running reader can keep its process lock. Its journal, preferences, selection,
device identity and cache are never written by this command.

## Parent command

Use the frozen bundle recorded in `evidence/probe-cli-build-validation.json`,
or the standard path below. Help reads no saved state and does not access Keychain:

```sh
BOOX_PROBE_EXE='/PATH/TO/Documents/New project/boox-work/notes-drive/macos/build/BOOX Notes Reader.app/Contents/MacOS/BOOXNotesReader'
"$BOOX_PROBE_EXE" --validate-probe-help
```

First obtain the **current complete revision hash** from verified Drive state
and prepare/review an edited `.note` of that same disposable notebook. The
earlier `c0249a…`/`9962bc…` hashes are historical, not suggested current bases.
The command does not generate, merge or rebase edits.

```sh
"$BOOX_PROBE_EXE" --validate-probe-publish \
  --fixture '/absolute/path/to/reviewed-edited-GDrive-Sync-Probe.note' \
  --expected-base 'REPLACE_WITH_CURRENT_FULL_LOWERCASE_REVISION_SHA256' \
  --journal-dir '/PATH/TO/Documents/New project/boox-work/notes-drive/macos/evidence/live-own-mac-probe-attempt-01'
```

The journal directory must **not exist**; its parent must exist. Do not create the
journal directory beforehand. It is created privately with an independent lock.
The CLI refuses any path inside/above live app state, an alias to it, or any app
bundle. Candidate and downloaded base copies go only into that new directory.
The originally supplied fixture is read in place and never overwritten.

On an unknown upload result or silent-grant failure, retry the same request:

```sh
"$BOOX_PROBE_EXE" --validate-probe-retry \
  --expected-base 'SAME_FULL_REVISION_SHA256_AS_THE_ORIGINAL_ATTEMPT' \
  --journal-dir '/PATH/TO/Documents/New project/boox-work/notes-drive/macos/evidence/live-own-mac-probe-attempt-01'
```

Retry uses the exact saved account, folder, device, payload, revision and parents.
It can verify a previous remote commit without writing duplicate objects. It
cannot redirect or rebase a stale unpublished request. A fresh edit against a
newer base needs a new journal directory.

## Authentication and scope

The command reads only the existing `connection.json`, `device-id` and imported
Desktop config under the normal app support directory (or bundled config when
none is imported). It rechecks those bytes during the operation. The Desktop
client must exactly match the `desktopClientID` binding in `connection.json`.
There is no personal project/client constant. After upgrading a legacy selection,
reconnect the saved grant in the GUI once; its verified account and exact saved
directory are retained and bound to the imported client. The CLI never migrates
or writes the live selection itself and refuses unbound or mismatched config.

Authentication uses the existing `GoogleAuthorization.token(allowInteraction:false)`
path and this app's Keychain service. There are no token, account, folder or config
overrides, no browser/session token access, no consent browser and no fallback
to Android credentials. Existing scope validation permits only `drive.file`.
Any normal refresh-token rotation follows that existing silent Keychain path.
Tokens and the OAuth secret are not printed or copied into the isolated journal.

The standalone process also disables legacy Keychain interaction because the
installed SDK documents that the modern LAContext gate alone does not cover
legacy-keychain prompts. The intentionally deprecated compatibility call is
confined to this CLI process; it does not change the running reader. If the grant,
ACL or locked Keychain needs UI, validation fails and leaves the request intact.
The parent must handle any later foreground reconnection after unlocking.

Google's actual account permission ID must equal the saved account. The exact
saved directory is checked for identity, protocol marker, writable capability
and trash state; it is neither discovered anew nor created.

## Publication checks and output

- Candidate and remote base must both decode as the same native notebook with
  title exactly `GDrive-Sync-Probe` or starting `GDrive-Sync-Probe-`.
- Their native document/page identities, title, page dimensions and layers must
  match. Pen content must differ; same-payload and container-only changes fail.
- The complete verified catalog must have the explicit base as its sole head.
  A second head check follows payload upload before revision publication.
- The exact request is fsynced before authenticated network activity. Payload
  upload/readback precedes revision upload/readback and final catalog verification.
- All ordinary protocol/archive limits apply, including a 4 MiB candidate,
  16 KiB record, 1,000 objects and 64 MiB per catalog refresh. The private request
  JSON is capped at 6 MiB and the whole process has a 300-second deadline.
- `request.json`, `candidate.note`, hash-named base copies and `receipt.json`
  live only in the isolated directory. An orphan payload after a race does not
  constitute a notebook publication.

Stdout on completion is a token-free JSON receipt with Desktop client, account
permission ID, folder, actual persisted Mac device ID, notebook/base/revision/
payload hashes, observed heads and:

```json
{"interactiveUIValidated":false,"nativeApplyValidated":false}
```

Exit statuses:

| Code | Meaning |
| --- | --- |
| 0 | Exact publication verified; its revision is the observed sole head. |
| 1 | Rejected, unavailable grant/state, failed request or unknown outcome. Retain the isolated request. |
| 2 | Exact publication verified, but observed heads changed; explicit review required. |
| 124 | Bounded deadline reached; the immutable request can be retried. |

A race after the final pre-publication check remains possible in this immutable
protocol. The receipt preserves and reports observed heads; it never selects a
winner or claims native/interactive success. Android native application and the
interactive Mac editor remain separate parent-owned validations.
