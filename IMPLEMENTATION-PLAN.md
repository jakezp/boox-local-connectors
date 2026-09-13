# Implementation scope and future work

The [README](README.md) is the installation and usage entry point. The
[technical brief](docs/TECHNICAL-BRIEF.md) explains the completed implementation;
[validation evidence](docs/VALIDATION.md) records what has been exercised.

## Implemented scope

- API and custom ChatGPT connections for native AI Assistant and NeoReader,
  with selected reading context and explicit connection/model selection.
- Static BOOX disclaimer hiding while retaining input spacing.
- Automatic publication of saved native Notes content to Google Drive.
- Verified incoming native application with idle-editor checks, durable journals,
  recovery and readback.
- Mac notebook reading, normal pen editing, durable drafts/queues and automatic
  library following through independent Google authorization.
- Supported notebook/folder lifecycle operations and explicit conflict handling
  that retains revision ancestry.
- Synthetic fixtures, portable tests, pinned dependency acquisition, app builds,
  diagnostics and a guarded companion installer.

## Development boundaries

Preserve native notebook/page/stroke identities and unknown content when changing
supported records. Publication, native commit and visible rendering are distinct
acceptance stages. Use disposable notebooks for tests and retain interrupted
work for diagnosis. Never resolve concurrent branches by wall-clock ordering.

Each app obtains its own grant. Updates retain signing identities. Firmware and
framework guards identify the tested baseline and must not be weakened merely
to run on another device.

## Areas requiring further work

These are limitations, not scheduled or completed features:

- Additional BOOX models/firmware and a rehearsed fresh-device setup path.
- A complete bootloader-unlock, recovery/OTA and root-repair procedure.
- Greater native rendering/editing fidelity and existing page/layer editing on Mac.
- Large-notebook support, larger-library scaling and retained-history management.
- Unattended multi-day authorization renewal/revocation behavior.
- Full first-install and combined-wrapper live acceptance.

For an implementation change, identify the affected boundary, add meaningful
checks, exercise the relevant native/Drive/Mac path, then update the user guide
and technical record. See [supported scope](docs/reproduction/GAPS.md) before
claiming that a gap has been closed.
