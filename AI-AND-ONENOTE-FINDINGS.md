# BOOX AI assistant and OneNote investigation

> Historical investigation. The user subsequently chose a native API adapter, now installed and tested. See [HANDOVER.md](HANDOVER.md) for current state. Recommendations and “not modified” statements below describe the earlier investigation only.

Inspected NoteAir4C DEVICE_SERIAL running 4.2-rel_04282_555977efe.

## Recommendation

Use the official ChatGPT Android app signed into the user's existing account, with a small handoff app and (only where needed) a narrowly scoped hook for BOOX's explicit assistant entry points. Start with text selection and a launcher/navigation shortcut, then add reading context and user-selected images. This retains ChatGPT's own account sign-in, history, model selection and response UI. Answers would appear in ChatGPT, not inside the original BOOX conversation panel.

This is a proposed design, not an installed or tested integration. ChatGPT is not installed on the tablet at inspection time. Its current exported sharing activities, accepted MIME types and text-prefill behavior need checking before implementing the adapter. A clipboard-and-open fallback can preserve text if direct sharing is not accepted. Returning generated responses automatically into BOOX has not been established.

Do not assume that Android's default assistant setting redirects BOOX's AI buttons: the BOOX SDK explicitly targets its own package and service. A standalone app cannot intercept those explicit intents simply by declaring the same action. Replacing the signed system APK would introduce signing, permission and update problems. Prefer a reversible adapter and minimal app-scoped hook if preserving those exact buttons is required.

A fully native replacement backend that preserves BOOX's chat panel would need an adapter for input, streaming results, history, quotas and document processing. The documented general OpenAI API uses Platform API credentials and billing; the existing ChatGPT subscription is not a drop-in API key. No supported general-purpose ChatGPT-subscription backend for this Android app was found. Codex subscription sign-in is a distinct documented product flow and is not evidence of such a backend.

Official source: https://learn.chatgpt.com/docs/auth (subscription sign-in versus Platform API billing; general API calls use Platform keys).

## Evidence from the tablet's APK

Backed up system/app/ai-assistant-release/ai-assistant-release.apk; version 30403 - 4d0d0d078fd.
JADX generated local source for the inspected classes, although the full decompilation reported errors for some unrelated methods. Conclusions here use specific readable methods and the APK manifest; this was not a complete source audit.

- com.onyx.aiassistant.ui.MainActivity: exported launcher entry point.
- com.onyx.aiassistant.service.AIAssistantFloatingService: exported floating entry point.
- AIAssistantTileService.onClick(): calls AIAssistantClient.openAssistantFloatingWindow().
- AIAssistantClient.openAssistantFloatingWindow(): explicit BOOX package/service component.
- AssistantReadingInfoReceiver: exported receiver for com.onyx.action.NEW_ASSISTANT; extracts the parcelable under readingAssistant, then starts the full-screen activity or floating service.
- AssistantReadingInfo: question, currentPageData, bookName, author, filePath, imgUrl, currentPage and other reading metadata. Actual content populated depends on the invoking app/action; field availability does not guarantee text/images are present.
- AIAssistantRemoteServiceConnection: binds explicitly to com.onyx.android.ksync/com.onyx.android.ksync.service.KAssistantService.
- AIAssistantInputArgs: content, prompt, customPrompt, historyMessages, readingInfo, recordBean, targetLang, outputFormat and contentType.
- ConversationViewModel.sendQuestion(): builds a conversation record and routes it through the BOOX client. Merely changing a server hostname would not replace this integration.

No AI settings or system APKs were modified, and no document content was submitted to an AI service during this investigation.

## OneNote

Installed: com.microsoft.office.onenote, version 16.0.20326.20108, versionCode 1807294107.
Read the saved BOOX MMKV configuration after installation. The OneNote app-profile records show:

- noteConfig.enable = true
- noteConfig.supportNoteConfig = true
- drawViewKey = com.microsoft.office.airspace.AirspaceInkLayer
- globalStrokeStyle.enable = true
- repaintLatency = 500

The native handwriting profile is already enabled. The repaintLatency parameter must not be interpreted as measured pen-to-ink latency. No changes were made just to lower that number. A physical stylus test is needed to determine whether the current OneNote version is actually using the accelerated path and whether redraw delay or stroke rendering needs tuning. The user was asked to test while the AI code was inspected.

Configuration backup before inspection: patch/onyx-mmkv-onenote-installed.tar.
Original configuration before OneNote installation: backups/DEVICE_SERIAL-4.2-04282/onyx-mmkv-before.tar.
