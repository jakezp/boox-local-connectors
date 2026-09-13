# NeoReader and disclaimer validation — 2026-09-12

Subsequent layout update: v0.6 keeps the input disclaimer invisible rather than
removing its space. The full-screen input moved up 74 pixels and remains free of
disclaimer text. See [screenshot](tests/input-spacing-v0.6.png). The detailed
connector validation below records v0.5; its routing behavior is unchanged in v0.6.

Adapter **0.5 / version code 5** is installed on the BOOX NoteAir4C
(`DEVICE_SERIAL`, firmware `2026-04-28_17-50_4.2-rel_04282_555977efe`).
The saved model remains `gpt-4.1-nano`.

APK SHA256:
`93b9ace0a21cb0900e784a02370791468bf803b112c5e8de292404c01b0b9606`.
The installed APK matches the local build; v2/v3 signatures verify.

## Changes

- Hide the static BOOX reply and input disclaimer views identified by XML string
  resources `conversation_reply_tips` and `assistant_declaration_tips`. The hook
  keeps these views hidden when data binding changes visibility. Generated answer
  content is unchanged; quoted text is not stripped.
- Add `com.onyx.kreader/0` to the adapter's existing
  `com.onyx.aiassistant/0` Vector scope. The private bridge accepts only those
  installed package UIDs and its own UID.
- Route NeoReader's existing selected-text record through the OpenAI provider,
  including `highlightText`, `highlightAroundContext`, and `systemPrompt`.
- Handle `initReadingAssistant` locally with reading instructions. NeoReader's
  extracted introductory text is not forwarded by this call. Opening the panel
  does not generate an OpenAI request or upload a book.
- Load bounded topic pages for NeoReader's document-ID lookup. This prevents an
  older book conversation being missed after the first topic page.
- Tie request aborts to the native app session, so another app's lifecycle abort
  cannot stop the active reply.
- Filter already-present record IDs from native `ConversationAdapter.addData`.
  Opening a reader conversation full screen previously caused overlapping
  initial loads to display each stored message twice. Distinct regenerated
  answers remain visible.

The original Assistant, NeoReader, and ksync APKs were not patched or replaced.
Root/framework modules and OneNote settings were unchanged.

## Live verification

Only synthetic local text documents and prompts were used. Real OpenAI responses
were verified after the final production edits.

| Check | Observed result |
| --- | --- |
| Full-screen disclaimers | Existing answer text and actions remained; reply/input disclaimers disappeared. |
| Embedded selection/context | Selecting `imaginary` and asking for the nearby bridge year returned `1842`. The stored request included selected text, surrounding text, and reading instructions. |
| Follow-up | Asking for the same bridge's material returned `blue limestone`. |
| Regenerate | Added a second `blue limestone` answer associated with the original question; no extra question was stored. |
| Live Stop | Stopped a long essay while generating. The panel displayed `Stopped.` / `Generation stopped` and restored the input box. The canceled turn was not persisted. |
| Recovery after Stop | A follow-up recalled the bridge year as `1842`. |
| Force-stop/reopen | The same document conversation and completed messages returned. |
| Separate document | A second document opened with empty history. Its Deep Analysis explained the selected word in the context of Meadowhaven. A follow-up returned `1907` and `red granite`, matching that document. |
| Return to first document | Restored the first document's existing `1842` conversation without creating another topic for that book. |
| Expand to full screen | Shared the correct topic and displayed each saved record once after the overlap fix. |
| Floating panel | Existing history loaded with no disclaimers; a live request returned `Floating panel ready.` |
| Access control | An unprivileged shell `content call` was rejected with `SecurityException`. |
| Preservation | Original record arrays and topic fields were checked before cleanup; encrypted key/model preferences remained byte-for-byte identical to the pre-change backup. |

Screenshots: [expanded history](tests/neoreader-expanded-dedup.png),
[embedded Stop](tests/neoreader-stop.png),
[recovery](tests/neoreader-after-stop.png),
[Deep Analysis](tests/neoreader-deep-analysis.png), and
[floating reply](tests/floating-final-reply.png).
Synthetic saved request/response evidence is in
[live-neoreader-20260912.json](tests/live-neoreader-20260912.json).

## Deterministic checks

**18 passed, 0 failed** on the final installed build:
[results](tests/results-neoreader-20260912.txt).

The existing isolated regression runner now also verifies local-only reading
preflight, document lookup beyond the first topic page (125 synthetic topics),
and cancellation isolation between app sessions. The runner uses separate fixture
preferences, a synthetic encrypted credential, and fake HTTPS; it does not read
the user's API key or make paid API requests.

The earlier 15-check matrix and separate native error-UI test are recorded in
[the historical v0.3 report](VALIDATION.md). The error-UI test was not repeated in
this pass. Build/check commands remain in that report.

## Preservation and recovery

Before changes: `../backups/pre-neoreader-20260912-182353/` contains the v0.3
adapter source, setup/native Assistant data, original NeoReader APK and scoped
private data, prior Vector scope, and checksums. These are component backups;
they do not export Android Keystore secrets.

Final checkpoint: `../backups/post-neoreader-20260912/` contains the v0.5 APK/source,
history before test cleanup, restored original history, final scope, and checksums.
Intermediate footer-only v0.4 APK:
`../patch/boox-openai-footer-only-v0.4.apk`. Downgrade installation was not tested;
preserve signing identity and app data if using this checkpoint.

Cleanup verified all added records were the six known synthetic questions and
their answers/one regenerated alternative: 13 unique records in two document
topics. Two additional blank, empty topics created during UI testing were also
removed. The original three topics, two records, and selected topic were restored
byte-for-byte. Both tablet test files, fixture preferences, and the temporary
validation app were removed. Source fixtures remain in `tests/`.

Keep the existing setup app installed to preserve its encrypted key and history.
Use update installs, not uninstall/reinstall. After future hook updates restart
both `com.onyx.aiassistant` and `com.onyx.kreader`.

## Remaining boundaries

- This connects NeoReader's embedded selected-text AI panel. It does not implement
  null-record `readingInfo`, whole-book analysis, file attachments, images,
  meetings, or document parsing.
- Available source context comes from NeoReader. The live fixtures were text
  files; PDF/EPUB-specific selection and context extraction were not separately
  tested.
- Replies appear when complete; there is no token streaming. Output is capped at
  2,048 tokens. Existing history/prompt limits remain: last 20 records and a
  60,000-character current prompt, without a full token-budget policy.
- Only one OpenAI reply runs at a time across both apps. A concurrent request
  receives an instruction to wait or stop the running reply.
- Native output-length/retention controls and every native AI entry point are not
  implemented. These limits remain as documented in the v0.3 report.
- Firmware upgrades may change resource names or client classes. This build was
  validated only on the firmware above. Module rollback/OTA recovery was not
  exercised.
