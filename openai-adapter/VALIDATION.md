# BOOX OpenAI adapter validation — 2026-09-12

Historical v0.3 report. The installed build is now v0.5; see
[NeoReader and disclaimer validation](NEOREADER-VALIDATION.md). That later pass
connects the embedded panel and expands scope to NeoReader. The test results and
remaining boundaries below describe the earlier v0.3 checkpoint.

Validation and repairs are complete for the core text-chat workflows. The installed
adapter is **0.3 / version code 3**, using the user's existing `gpt-4.1-nano`
configuration. This is not a claim that every native BOOX AI feature is supported.

Final APK SHA256:
`60c8ccf49a8fce074435e8876a3a3ab82bf8f3b956c55bfd7c42153cea4569c8`.
The installed APK hash matches the local build. APK v2/v3 signatures pass.

## Repairs

- Topic lists now mark only the actual selected topic.
- New-topic responses use the selection flag BOOX expects, so the native UI
  activates the new topic instead of continuing in the previous one.
- Blank new topics can acquire a title from the first question. Renaming an
  existing topic preserves the current selection and other metadata.
- Topic search and pagination are applied; unknown record cursors return an
  empty page instead of repeating the newest records.
- Regeneration reuses the original question ID, inserts one alternative answer
  beside the earlier answer, and excludes later conversation turns and the old
  answer from that request's context. Later normal requests use the latest
  adjacent alternative answer.
- Stop suppresses canceled queued requests as well as late replies. Cancellation
  remains active through the history-write boundary. Deleting an active topic
  or record also stops its request.
- Native Stop now receives a final UI event: “Stopped.” / “Generation stopped”
  replaces the stuck “Generating…” state, and the input box returns.
- Native error rendering preserves the adapter's sanitized explanation instead
  of replacing every error with BOOX's generic “Generation stopped” label.

The original assistant and supporting service APKs were not replaced. Root,
the firmware-specific AMS module, Vector scope, and OneNote configuration were
not modified.

## Verification

| Workflow | Evidence and result |
| --- | --- |
| Native text reply | Live responses displayed in the original full-screen UI. |
| Multi-turn context | A follow-up correctly recalled the synthetic codeword `ORCHID-742`. |
| New topic and auto-title | Native Create new topic switched to the new conversation; the first question supplied its title. |
| Regenerate | Native Regenerate added another answer with no duplicate question; source-level fixture checked IDs, placement, and request context. |
| Rename | Native rename to `Validation-Renamed` survived force-stop/reopen. |
| Record deletion | A reply deleted through the native UI remained deleted after restart. |
| Topic deletion | Native confirmation deleted the synthetic topic; another topic loaded after restart. |
| History and selection | Completed conversations and selection survived restarts; canceled output was absent from storage. |
| Live Stop | Canceled a long synthetic essay while in flight. UI returned to idle; a subsequent request returned `recovery after Stop works.` |
| Floating assistant | Live reply `floating assistant works.` displayed in the floating panel. |
| NeoReader floating handoff | Selected a word in a synthetic text file, used NeoReader's open-floating-assistant button, and received an OpenAI reply in BOOX Assistant. |
| Error UI | A separate UI test entered a 60,001-character synthetic prompt. No API request was needed; the native UI displayed the actionable length error and restored input. |
| Access control | A shell `content call` to the bridge was rejected with `SecurityException`. |
| Final installed build | After the final production edits, a live request returned `final connector validation passed.` |

There are **15 passing deterministic regression checks**, plus the separate native
error-UI check. See [test results](tests/results-20260912.txt) and
[final native reply screenshot](tests/final-native-reply.png).

The regression runner targets the setup app with a separately signed test APK.
It redirects history/configuration to fixture preferences and replaces HTTPS
with an in-process fake connection. The final runner uses a synthetic credential,
never reads the user's API key, and sends no network requests. It tests parser
errors, topic activation/selection, rename, search, paging, deletion, selected
text/context assembly, regeneration, unsupported input, cancellation ordering,
and deletion during requests. Earlier test runs used the existing vault internally
with the same fake transport; no credential was returned to the host.

The separate UI runner targets the test package and operates the visible native
assistant through Android UI Automation. Its first launch reported a test-process
failure while BOOX had automatically frozen the new test package; retry passed.
This was not a crash of the production adapter. The temporary test APK was
uninstalled after testing.

## Exact remaining boundaries

- **NeoReader's embedded selection/AI panel is not connected to this adapter.**
  Its views run in `com.onyx.kreader`; Vector scope remains exclusively
  `com.onyx.aiassistant/0`. No question was submitted through the embedded panel
  during this validation. Use its open-floating-assistant button for the tested
  OpenAI route.
- The tested handoff carries the selected text into the prompt field. It does
  not establish full-page, whole-book, document-analysis, or reading-summary support.
- Direct `readingInfo` chat with a null record remains unsupported.
- Images, attachments, meetings, document parsing, streaming, and importing
  original BOOX history remain unsupported.
- Context still uses the last 20 local records, with no complete token-budget
  policy. The current prompt has a 60,000-character limit; output is capped at
  2,048 tokens. Incomplete API responses remain rejected.
- Native output-length/retention controls are not fully implemented. The displayed
  retention value does not schedule deletion. Not every native setting, sorting
  option, or filtered-history query has been validated.
- Stop prevents a canceled reply being displayed/saved; disconnecting does not
  guarantee the server performed no work or incurred no usage.
- A stopped unsaved turn disappears on restart; its Regenerate button does not
  have a persisted question to reuse. Send the question again if needed.
- Module disable/re-enable rollback and OTA recovery were not exercised.

## Preservation and cleanup

Before changes:
`../backups/pre-validation-20260912-175358/` contains the source/build snapshot,
setup/native assistant app data, and SHA256 manifest. This is a component backup,
not a full image or an export of the Android Keystore key.

After validation:
`../backups/post-validation-20260912/` contains the validated APK, the history
checkpoint before removing test data, checksums, and a cleanup summary.

Before cleanup, all original records were checked unchanged and every extra record
was identified as a synthetic test. The original three conversations, two saved
records, and selected topic were restored; 15 remaining synthetic records were
removed. The synthetic NeoReader topic had already been deleted through the UI.
The temporary file was removed from the tablet; its source remains in `tests/`.

The encrypted key/model preferences were byte-for-byte identical to the
pre-validation backup. The chosen model and signing key remain intact.
BOOX's floating navigation ball was moved away from the Send button during testing.

## Re-running checks

From the `boox-work` directory:

```sh
python3 openai-adapter/build.py
python3 openai-adapter/tests/build.py
tools/platform-tools/adb -s DEVICE_SERIAL install --no-incremental -r openai-adapter/build/boox-openai-setup.apk
tools/platform-tools/adb -s DEVICE_SERIAL install --no-incremental -r openai-adapter/tests/build/validation.apk
tools/platform-tools/adb -s DEVICE_SERIAL shell am instrument -w local.boox.openai.validation/local.boox.openai.ValidationRunner
tools/platform-tools/adb -s DEVICE_SERIAL uninstall local.boox.openai.validation
```

For the separate UI error check, first open a synthetic test conversation, then run
`local.boox.openai.validation/local.boox.openai.UiValidationRunner` instead.
It temporarily adds a large unsaved prompt to the visible UI. Restart the native
assistant afterwards. Do not run it over an unfinished user draft.

API request shape was checked against the official
[Responses create reference](https://developers.openai.com/api/reference/resources/responses/methods/create).
The project keeps `store:false`, supplies explicit message history, and preserves
the selected model.
