# ChatGPT OAuth implementation — v0.9 live validation

Updated 2026-09-12. **v0.9 is installed**, the user is signed in, and
**GPT-5.6 Luna is active** in ChatGPT subscription mode. Real subscription-backed
responses passed in Setup, native AI Assistant, and NeoReader's embedded panel.
The latest change replaces the ChatGPT model text input with an account-populated
dropdown. The v0.8 return-to-Setup and pending-code recovery repairs remain.

## Exact build state

- Installed and local build: **0.9/code9**, SHA256
  `aab74d3be5bd44ece6674bd0645304cfa9bf09da85b8ab4ed99b199bfce25331`.
  Hashes match; APK v2/v3 signatures pass.
- All prior account/session guards are included. There is no longer a separate
  reviewed build awaiting installation.
- **18 existing + 30 OAuth checks = 48** pass.
- Previously on v0.8, opening the sign-in browser and returning to Setup
  preserved the exact same code and ChatGPT tab. Force-stopping/reopening the
  setup app also restored the same code. API mode remained active.

Current checkpoints: `../backups/pre-model-picker-20260912/` and
`../backups/post-model-picker-20260912/`. Earlier v0.8 return-fix and v0.7
implementation checkpoints remain available.

## Model dropdown and live checks

Setup fetches the signed-in account's catalog automatically on each activity
opening, and offers manual refresh. It displays a native Spinner rather than a
text-entry/autocomplete field. The live catalog returned Astra, Sol, Terra, Luna,
and GPT-5.5. Luna is labeled `quick lookups` when actually present in the catalog.
Refreshing does not save a model, and a missing saved model leaves an explicit
choice rather than automatically selecting the first, potentially heavier model.
The API model field remains unchanged.

Luna passed the setup check (`BOOX connection working.`), was activated, and
survived force-stop/reopen with the catalog loaded again and the login intact.
Full-screen AI Assistant returned `Luna lookup ready.` with a Luna model label.
In a synthetic NeoReader passage, it correctly extracted **1842 / blue limestone**
from surrounding text and answered a follow-up with **yellow paper flowers**.
Both embedded responses persisted with `connectionMode=chatgpt` and the Luna label.

Two identified synthetic topics/six records were removed after validation.
All other record arrays, including the user's new chat since the pre-change
backup, were preserved. API key/model preference bytes are unchanged. The
temporary test APK and reading fixture were removed; Setup is left open.

## Return-to-Setup repair

The previous activity initialized the configuration dropdown from the active
billing mode and canceled its login worker in `onDestroy()`. This lost the
pending code when Setup was recreated and displayed the API controls again.

The selected configuration tab now has its own preference. `LoginCoordinator`
owns login at application scope; activities attach/detach without canceling it.
The device authorization ID, displayed code, polling interval, original expiry,
and revision are encrypted as a pending record in `OAuthVault`. Reopening after
process death restores the same unexpired code without extending its lifetime.
Cancel, replacement, completion, and expiry remove the pending record.
Refreshing an existing credential does not accidentally remove a pending login.

`SetupActivity` uses `singleTask` launch mode and provides **Copy code**, with
the clipboard marked sensitive. Browser return and full restart were tested with
a real code; only boolean outcomes were saved, not the code:
[return checks](tests/oauth-return-v0.8.json).

## Implemented behavior

- Setup has separate API and ChatGPT connection modes. API remains the active
  default. Selecting the configuration dropdown alone does not change routing.
- Device-code login uses the public Codex OAuth client. It displays a short code
  and opens OpenAI's sign-in page; no password is collected by the setup app.
  Polling, cancellation, expiry, denial, code exchange, and token refresh are
  implemented. Browser PKCE is a possible future fallback, not implemented here.
- OAuth tokens are encrypted together in private `chatgpt_oauth` preferences with
  a separate Android Keystore alias:
  `boox-chatgpt-oauth-v1-local.boox.openai`.
  Existing API preferences and their alias are unchanged.
- Refresh is serialized; rotated credentials are persisted atomically. Logout
  and account replacement invalidate in-flight sessions and prevent stale
  credential/history commits.
- The account's Codex model catalog is fetched from the subscription backend.
  Available models are listed separately from the saved API model.
- The setup test must succeed for the account/model before **Use selected
  connection** can activate ChatGPT mode. Native request validation repeats that
  account/model check in the reviewed candidate.
- `ReplyClient` separates the existing API transport from the new Codex
  subscription transport. The provider retains shared history, selected/nearby
  text, regeneration, cancellation, and history-write guards.
- Subscription requests use the Codex Responses endpoint and SSE. The parser
  validates completion, bounds input, preserves split UTF-8, and rejects failed,
  incomplete, refused, truncated, or canceled output. The native UI still receives
  a completed answer rather than visible token streaming.
- Request configuration captures mode/model/account. Switching settings cannot
  redirect an existing request into another account or billing mode.
- ChatGPT quota/auth failures produce local instructions; there is no automatic
  paid API fallback. Native subtitle/model labels identify the chosen connection.
- The v0.6 input spacing and hidden disclaimer text are preserved.

The new transport does not implement file/image uploads, whole-book analysis,
ChatGPT web-history synchronization, memory, or custom GPTs.

## Verification so far

| Check | Status |
| --- | --- |
| Build and APK v2/v3 signing | Passed; installed/local v0.9 hashes match |
| Existing provider regression suite | 18 passed |
| OAuth/transport/provider suite | 30 passed |
| Real device-code request on BOOX | Passed; code displayed privately |
| Browser return / full app restart | Same code and ChatGPT configuration tab restored |
| User completes OpenAI authorization | Completed privately; login retained |
| Account-specific model catalog | Five live models; dropdown and automatic reload passed |
| Real subscription connection test | Luna passed and activated |
| Full-screen / NeoReader subscription replies | Passed live with Luna |
| Embedded follow-up | Passed using prior passage context |
| Setup restart | Login, active mode and Luna selection retained |
| Floating, Regenerate, Stop/recovery, logout | Not repeated live in subscription mode; applicable isolated checks pass |
| API credentials/model and user history | API bytes unchanged; all non-test records preserved |

Results:
[API/provider checks](tests/results-api-model-picker-20260912.txt),
[OAuth checks](tests/results-oauth-model-picker-20260912.txt), and
[live evidence](tests/model-picker-v0.9.json).
The first back-to-back OAuth instrumentation launch reported a process crash
without test results. A separate rerun passed all 30 cases.

The test APK now includes a snapshot of production transport/provider code and
instruments **itself**, not the installed setup app. Its fake HTTPS blocks actual
network calls; synthetic credentials, preferences, and Keystore keys are isolated
under the test app's UID. This allows running checks while real login continues
without interrupting it or reading production credentials.

## Continue from here

1. The user can select another available ChatGPT model, test it, and use it.
   Luna is already tested and active; no new sign-in is needed.
2. If broader live subscription validation is requested, finish floating,
   Regenerate, Stop/recovery and API-mode switching using synthetic content.
3. Keep the real login intact; isolated tests already exercise logout and
   account replacement. Do not restore old whole-history backups over newer chats.
4. Rebuild/reinstall the isolated test app if code changes require further checks.

Before changes: `../backups/pre-oauth-20260912/` contains the v0.6 APK/source,
encrypted app preference backup, and checksums. API key/model preferences and
history were hash-compared unchanged after the initial implementation/tests.
No API key, password, or OAuth token was printed or imported from another app.

## Source reference

See [OAUTH-PLAN.md](OAUTH-PLAN.md) and the saved public references in
`../patch/oauth-research-20260912/`. The models endpoint version parameter uses
schema compatibility `0.154.0`, matched to the inspected official Codex release.
Requests identify this app as `boox_openai` / `boox-openai/0.9 (Android)`.
The sign-in UI explicitly identifies Codex subscription access.
