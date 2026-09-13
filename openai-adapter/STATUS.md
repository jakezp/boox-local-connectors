BOOX OpenAI integration status — 2026-09-12

CURRENT: OAuth v0.8 installed; the return-from-browser problem is fixed.
The ChatGPT configuration tab is remembered, login runs outside the activity,
and its encrypted pending code survives process restart. Copy code is available.
Browser return and force-stop/reopen retained the same real code on the tablet.
All 48 isolated checks pass. API credentials/model and history are unchanged.
The temporary test app was removed.

Installed/local SHA256:
1eecf6d48ede641a2579a8eb35de851bbc72c9acf0b47d87e1d3dea809696ad5

The user still needs to finish private sign-in and the setup connection test.
No real subscription reply is verified yet. API mode remains active.
See [OAUTH-VALIDATION.md](OAUTH-VALIDATION.md).

Historical v0.7 checkpoint, superseded:

OAuth v0.7 candidate installed; private user sign-in/live validation
pending. A real device authorization code was obtained on the BOOX.
The reviewed source includes API/subscription mode selection, encrypted refreshable
tokens, account model discovery, Codex SSE, and native routing. All 43 isolated
tests pass (18 existing + 25 OAuth). No real subscription reply is verified yet.

Installed: 9adab0a4f75dadbd6579dca94aecae1789728b91c55310dcb0923a4f69160e2d
Reviewed local build: 71fa064a6479f3192649f9dd594e36c965b1e782883950d572907d78c558c9ff
The reviewed APK is held until the running private login completes or ends.
See [OAUTH-VALIDATION.md](OAUTH-VALIDATION.md) before continuing.

The following records the completed v0.6 checkpoint:

LATEST: Adapter 0.6/code6 is installed. The input disclaimer is now invisible
while retaining its original space; reply disclaimers remain removed.
Full-screen UI confirms the input moved up 74 pixels and the text remains hidden.
The user is manually testing. This layout-only change passed build/signature
checks and live UI inspection; the 18 regression checks were last run on v0.5.
SHA256: 66317251ab977e065b61e29f25f6f526e5de9f1034035080ae5e18e7d29fb83c

ChatGPT subscription OAuth research and the implementation plan are ready in
[OAUTH-PLAN.md](OAUTH-PLAN.md). OAuth is not implemented or signed in yet.

The following records the v0.5 checkpoint:

Adapter 0.5/code5 is installed and validated. Both BOOX disclaimer footer
resources are hidden in full-screen, floating, and NeoReader embedded chat.
NeoReader's embedded AI panel now uses the saved OpenAI configuration and receives
selected/nearby text. Live checks cover Deep Analysis, factual extraction,
follow-ups, Regenerate, Stop/recovery, document-specific history, reopen, and
full-screen/floating handoffs. Overlapping history loads no longer duplicate
displayed records. All 18 deterministic regression checks pass.

Scope: com.onyx.aiassistant/0 and com.onyx.kreader/0.
APK SHA256: 93b9ace0a21cb0900e784a02370791468bf803b112c5e8de292404c01b0b9606
Encrypted key/model settings and original history/selection are preserved.
See [NEOREADER-VALIDATION.md](NEOREADER-VALIDATION.md) for evidence and boundaries.
Whole-book analysis, attachments/images, streaming, and null-record readingInfo
remain unsupported. One API request can run at a time across both apps.

The following records the earlier v0.3 checkpoint:

Adapter 0.3/code3 was installed and validated. Topic selection/creation,
rename, search/paging, regeneration, and Stop/UI recovery were repaired. Fifteen
regression checks plus a separate native error-UI check pass. Live full-screen
chat, multi-turn context, regeneration, Stop/recovery, floating chat, and NeoReader
selection handoff to floating chat were verified. The final installed build
received a real API reply after its final edits. See [VALIDATION.md](VALIDATION.md).

NeoReader's embedded AI panel remains outside the adapter's one-app scope.
Streaming, attachments, direct null-record readingInfo, and full-document support
remain incomplete. Original conversations/selection were restored after test
cleanup; encrypted key/model preferences are unchanged.

The following records the earlier v0.2 checkpoint:

CURRENT: Native OpenAI text chat is installed and tested. User explicitly approved
Vector/Zygisk; both are active and the adapter is scoped only to
com.onyx.aiassistant/0. A real reply appeared in BOOX's native UI using the saved
gpt-4.1-nano model. History persistence and ordering are verified. See
[../HANDOVER.md](../HANDOVER.md) for full implementation details, limitations,
backup hashes and rollback instructions. Final Stop handling is implemented but
not live-tested; streaming/document/full reading workflows remain incomplete.

The following is the superseded pre-framework checkpoint, retained as history:

The setup APK is built, signed, installed on device DEVICE_SERIAL and opened successfully.
BOOX automatically disabled the newly installed app; `pm enable local.boox.openai`
restored launchability. Response parser fixture checks passed on the device.

The user can enter the API key directly in BOOX OpenAI Setup and press Test OpenAI
connection. No live authenticated API request has been verified yet. The key is
encrypted using Android Keystore, and Android app backups are disabled. This is
a setup/test app only: the native BOOX assistant has not been redirected.

Pre-change backups and SHA256 manifest are in ../backups/pre-openai-20260912/.
They include both relevant system APKs, app data and Magisk configuration, and the
current rooted boot_b image. These are scoped backups, not a full tablet image.

Native integration is pending explicit approval to install Vector and enable
Zygisk. Automatic approval review rejected the previous Vector installation
because it is a persistent framework with system-wide hooking capability and
boot/security risk. No Vector installation or Zygisk change was performed.
The proposed adapter would be scoped to com.onyx.aiassistant; that adapter still
needs implementation and device verification after framework authorization.

Removing the setup app is reversible via normal Android uninstall; this removes
its saved key. The original BOOX system APKs have not been changed for this work.
