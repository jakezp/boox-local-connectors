# Maintainer orientation

For a new installation, read [README.md](README.md). This file is an entry point
for contributors, not a sequence of device commands or a historical task log.

## Read in this order

1. [User setup and everyday use](README.md).
2. [Detailed technical brief](docs/TECHNICAL-BRIEF.md).
3. [Validation evidence](docs/VALIDATION.md) and [supported scope](docs/reproduction/GAPS.md).
4. [Build/install reference](docs/reproduction/README.md) and the component you will change.
5. [AGENTS.md](AGENTS.md) for repository-specific contribution instructions.

## Current implementation

The Android AI companion supports API and custom ChatGPT transports in native
Assistant and NeoReader. The static disclaimer is hidden with input spacing
preserved. The Notes companion and Mac v0.6 editor share the immutable Drive
protocol, including supported pen and library lifecycle changes, retained
conflicts, automatic publication/following and journaled native application.

The supported native baseline is NoteAir4C firmware
`2026-04-28_17-50_4.2-rel_04282_555977efe`, Notes version 45326. Live Mac/BOOX
round-trip and automatic-follow acceptance are complete for the recorded
configuration. Root/unlock and fresh-device installer boundaries remain explicit
in the linked guides. Do not reinterpret historical fixture hashes as the
current head of a user's notebook.

## Continuing development

Work from the user's actual current device/library state. Preserve signing
identities for updates, use each client's own authorization, and retain queued
edits and conflicts. Do not reroot, flash firmware or restore an old whole library
merely to resume development. Reproduce issues with clearly named disposable
notebooks and distinguish local encoding, Drive publication, native application
and visible UI acceptance in reports.

After changes, run the relevant checks, update the user-facing guide and technical
record, and regenerate `docs/reproduction/source-inventory.json`. Keep credentials,
notebooks, firmware and device backups out of publishable source.
