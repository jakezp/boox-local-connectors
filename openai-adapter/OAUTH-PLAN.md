# ChatGPT subscription connection — implementation plan

Implementation update: the user approved this plan and v0.7 code is now written.
See [OAUTH-VALIDATION.md](OAUTH-VALIDATION.md) for the installed v0.8 return-to-Setup
repair, 48 passing checks, the pending private sign-in step, and remaining live validation.
The original plan below is retained as the design reference.

Prepared 2026-09-12 after installing the v0.6 input-spacing fix.
This is a plan; no OAuth implementation, account login, or credential import has
been performed. The user is manually testing the tablet.

## Intended result

BOOX OpenAI Setup offers two connection modes:

| Mode | Account setup | Model setting | Usage |
| --- | --- | --- | --- |
| OpenAI API | Existing saved API key | Existing API model; preserve `gpt-4.1-nano` | API account billing |
| ChatGPT subscription | Sign in with ChatGPT through Codex OAuth | A model available to that connection | Account's Codex access and limits |

The chosen connection supplies answers in the same full-screen, floating, and
NeoReader embedded UI. Switching modes preserves local conversation history.
The current API configuration remains available. Exhausted subscription access
must never trigger an automatic paid API fallback.

Subscription access here means the Codex model connection. It is not a sync of
ChatGPT web conversations, memory, custom GPTs, or every ChatGPT feature.

## What the research establishes

OpenAI's current Codex authentication documentation distinguishes ChatGPT
subscription login from API-key access. It documents browser login and device-code
login; device-code login is currently described as beta and may require enabling
it in account security settings or workspace permissions. Its App Server
documentation exposes managed browser/device-code authentication, model listing,
account status, rate limits, and externally managed ChatGPT tokens.

OpenClaw's current documentation supports ChatGPT/Codex subscription OAuth and
describes PKCE browser authorization, token exchange, account-ID extraction, and
refresh-token ownership. Its provider naming/runtime arrangement has changed over
time; we should follow the protocol rather than assume an old configuration
example is current.

The inspected Pi implementation has both device-code and browser flows, token
refresh, and a separate Codex Responses transport. Its direct transport uses
`https://chatgpt.com/backend-api/codex/responses`, an OAuth bearer token,
account-ID headers, and streamed events. Its request builder uses `store:false`
and `stream:true`, with reading/system instructions supplied separately.

These sources establish a practical implementation reference. They do not by
themselves verify a standalone Android client's compatibility with this user's
account. There is also no generic third-party ChatGPT OAuth registration contract
established by the documentation reviewed. The initial milestone must confirm
the public-client/redirect contract and successful use on this tablet, with the
upstream identity represented honestly in the sign-in flow.

## Recommended sequence

### 1. Prove standalone sign-in and one reply

Start with a small isolated native Android implementation of the Codex device-code
flow. The setup app displays the short code and opens OpenAI's sign-in page in the
system browser. The user can also complete the code flow on another device.
Password entry happens on OpenAI's page.

Implement bounded polling, the server's polling interval, expiry, cancellation,
authorization-code exchange, and sanitized failures. Verify the expected issuer,
public client, redirect URI, and response fields against pinned upstream source.
Do not take tokens from the Mac's Codex app, browser cookies, or another tool's
credential files.

Acceptance: the user completes login; setup can make one small text request with
an eligible model and display the reply using only the subscription credential.
No native BOOX routing changes are needed for this first proof.

Device code is the preferred starting point because it avoids a local redirect
server on Android. If unavailable for this account, assess browser PKCE with a
loopback-only callback, state validation, a short listener lifetime, and no
embedded password form. Do not silently replace this with a Mac-dependent service.
The documented Codex App Server is an alternative architecture if the direct
native route proves unsuitable; it would need a separate decision about where
that runtime runs.

### 2. Store and refresh credentials safely

Add an `OAuthVault` with its own Android Keystore alias and private preference
file. Keep access token, refresh token, expiry, and account identity together in
an encrypted record. Retain the current API `KeyVault` unchanged.

Use a single refresh operation per account, persist rotated credentials atomically,
and invalidate stale refresh results after logout or account replacement.
Refresh before expiry and permit one bounded authentication recovery when needed.
An invalid grant requires sign-in again. Do not replay a request after response
output has begun, or retry quota errors as authentication failures.

Sign out removes only subscription credentials. Removing the API key must not
remove the OAuth encryption key. Logs and native bridge responses must never
contain tokens, passwords, raw authentication bodies, or authorization codes.

### 3. Add a connection layer without changing reading behavior

Introduce a small cancellable client interface implemented by the existing
`ResponsesClient` and a new `CodexResponsesClient`.

`AssistantProvider` currently constructs the API client directly, stores it in a
concrete `ResponsesClient` field, and reads model settings directly from
`KeyVault`. Replace those dependencies with an explicit connection configuration.
Capture connection, account, and model once at request start so a settings change
cannot mislabel or redirect an in-flight reply.

Keep history assembly, selected/nearby text, document association, regeneration,
and the guarded history commit shared. Only request encoding, authentication,
transport, and provider-specific errors should differ.

The subscription client needs a bounded SSE parser: tolerate split UTF-8
characters and event boundaries, recognize completion/failure/incomplete events,
and support Stop promptly. Initially accumulate text and return the completed
answer to the existing UI. Visible token streaming can remain a later feature.
Do not copy all API request parameters into the subscription request; validate
which fields its protocol accepts.

Preserve one active reply across both native apps and session-specific cancellation.
Do not store partial or canceled output as a successful answer.

### 4. Expose clear controls and model selection

Update setup with:

- Connection selector: **OpenAI API** / **ChatGPT subscription**.
- Subscription sign-in, cancel sign-in, account status, test, and sign-out actions.
- Separate saved model choices for each connection.
- A reauthentication action and readable subscription-limit messages.

Discover or validate subscription models for the signed-in account; do not assume
the existing API model is available there. Resolve the concrete first-test model
during implementation rather than pinning a possibly unavailable model now.

Update native model/config responses and `NativeHook.updateSubTitle`, whose
current text always says “OpenAI API · billed separately.” The subscription mode
should instead identify ChatGPT/Codex access. New answer records should retain
the actual connection/model used; old records and topic IDs remain intact.

The input footer stays blank with its original space. No recurring disclaimer is
needed to communicate connection choice: the setup state and native model label
already provide the appropriate place.

### 5. Validate and release

Add deterministic cases for login pending/denied/expired/canceled, malformed token
responses, token rotation, concurrent refresh, logout during refresh, expired
credentials, SSE framing/failure, cancellation, and no fallback to the other
billing mode. Keep fixture credentials separate from the user's account.

Retain the existing 18 provider regression checks. Live validation should cover:

1. User-driven browser/device login and one setup reply.
2. Full-screen, floating, and embedded NeoReader answers.
3. Selected/nearby text, follow-up, Regenerate, and Stop/recovery.
4. Force-stop/reopen, expired-token recovery, and sign-out.
5. Switching back to the saved API configuration with original history intact.

Use synthetic documents, back up before installation, and preserve the signing
key. Successful account login alone is not completion: a subscription-backed
reply and native workflow checks are required.

## Sources and reproducibility

Fetched sources are saved locally in `../patch/oauth-research-20260912/`;
`sources.json` records their addresses. These contain public documentation/source,
not user credentials.

- OpenAI Codex authentication:
  `https://developers.openai.com/codex/auth/`
- OpenAI Codex App Server:
  `https://developers.openai.com/codex/app-server/`
- OpenClaw OpenAI provider and OAuth:
  `https://docs.openclaw.ai/providers/openai.md`
  `https://docs.openclaw.ai/concepts/oauth.md`
- Pi source, pinned commit `71dca871bc80b6bc97be37f0ca3189399d651fff`:
  `packages/ai/src/auth/oauth/openai-codex.ts` and
  `packages/ai/src/api/openai-codex-responses.ts` in
  `https://github.com/earendil-works/pi`.

Recheck protocol details at implementation time. Preserve upstream license
requirements if code is adapted; the plan above does not copy implementation code.
