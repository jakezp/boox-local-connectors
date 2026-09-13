# Parent-reported native creation acceptance

The parent reports successful native apply and semantic re-export/readback of
the Mac-generated blank fixture. This is Android-grant transport and native
application evidence, not Mac own-OAuth or interactive editor validation.

| Item | Value |
| --- | --- |
| Native document ID | `a21e60175a514e84b136b279055a04bb` |
| Sync ID | `boox-a21e60175a514e84b136b279055a04bb` |
| Mac source payload SHA-256 | `6a31831b150b9689afbeac89bebda4c7518741191f8d3d887e95a276b65b24b2` |
| Applied revision | `c7f86d2cadf0e8c3d8d49c7c054fe21966ceac4b522cfe3d0b5672a23d1c3608` |
| Native re-export SHA-256 | `d147d6405c2cb1aac811c3363d026e1686f8b59f69c90840845078b484cc9826` |
| Readback | 1 page, 0 pens |
| Android checks reported | 57 passing |

The parent implemented a narrow allowance for previously absent generated
`extra/pb/extra`: known fields only, document version 1, app version 45326,
matching native document ID. Existing extra records remain byte-exact, and
unknown fields or arbitrary extra assets remain rejected. No Mac source
fixture change was needed.

The frozen lifecycle app and original Mac creation fixture hashes were
reverified unchanged after this report. Lifecycle handoff:
`LIFECYCLE-HANDOFF.md`. Synthetic test preparation:
`SYNTHETIC-TESTS.md` and `SYNTHETIC-VALIDATION.json`.

Remaining parent checks include interactive Mac editing/lifecycle and current
own-Mac-Keychain publication after unlock/reconnect. The Mac agent has not
launched the app, accessed the grant, retried an obsolete probe request, rebuilt
the frozen bundle, cleaned the repository or pushed changes.
