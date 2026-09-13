# BOOX OpenAI / ChatGPT connector v0.9

This Android companion stores the selected provider configuration and supplies
responses to BOOX AI Assistant and NeoReader's embedded AI panel through narrowly
scoped Vector hooks. The original applications retain their conversations and
reading context.

Supported modes:

- OpenAI API key, with the configured API model.
- ChatGPT subscription sign-in through the implemented device authorization and
  Codex transport, with the available-account model dropdown.

Subscription transport availability depends on the account and upstream service;
it is separate from ordinary Platform API credentials and billing. This project
does not convert a ChatGPT subscription into a general-purpose API key.

The native hook removes response disclaimer footers. It hides the input-area
disclaimer without collapsing its layout space. NeoReader passes supported
selected/nearby text and retains document-specific conversation history.
Attachments, images and whole-book processing are outside the demonstrated path.

## Build and install

Acquire the pinned SDK/JDK tools described in the
[reproduction guide](../docs/reproduction/README.md), then run:

```sh
python3 openai-adapter/build.py
python3 openai-adapter/tests/build.py
```

The production APK is `build/boox-openai-setup.apk`. The separate instrumentation
APK is `tests/build/validation.apk`. Builds create a private `local-signing.p12`
only when absent; preserve the existing key to update an installed connector.
Neither keys nor generated APKs belong in Git.

Install using the [guarded setup workflow](../docs/reproduction/SETUP-CLI.md).
The demonstrated Vector scope is exactly `com.onyx.aiassistant/0` and
`com.onyx.kreader/0`; grant the companion's required root/module configuration as
described there. Enter the API key or complete ChatGPT sign-in in **BOOX OpenAI
Setup**, select the provider/model and run its connection test.

## Validation and maintenance

The historical v0.9 checkpoint passed 48 isolated checks and real subscription
responses in Setup, AI Assistant and NeoReader. Current source-only builds of
both APKs pass; the instrumentation app is not automatically installed or run by
the build command.

Detailed evidence and limits:

- [API/native UI validation](VALIDATION.md)
- [NeoReader and disclaimer-spacing validation](NEOREADER-VALIDATION.md)
- [Subscription authorization and model selection](OAUTH-VALIDATION.md)
- [Historical chronology](STATUS.md)

Keep grants and API keys in their app-owned encrypted storage. Do not dump
credential-bearing preferences or reuse another application's grant for tests.
Before firmware changes, recheck the native methods and component versions
documented in the reproduction guide.
