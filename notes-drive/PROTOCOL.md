# BOOX Notes Drive revision protocol v1

This is the shared Android/macOS validation protocol. It transports native `.note`
snapshots as opaque bytes; it does not make either client a general native editor.
The reference revision encoding is `prototype/sync_protocol.py`.

## Folder extension

Folder revisions use the existing immutable envelope with notebook identity
`folder-<native-id>`. The payload is UTF-8 canonical JSON with exactly these keys,
in this order: `id`, `kind`, `parent`, `schema`, `title`. Example:
`{"id":"example-folder","kind":"folder","parent":null,"schema":1,"title":"Ideas"}`.
Escape quote, backslash and controls only; use short escapes for backspace, form
feed, newline, carriage return and tab, lowercase `\u00xx` for other controls.
Preserve all other Unicode and do not escape slash. Reject malformed Unicode,
extra/duplicate keys, invalid IDs and self-parenting. Clients retain missing/cyclic
parent relationships for review; native apply requires available unlocked parents.
An empty folder therefore has its own revision history.

Null-payload tombstones use the same envelope for notebooks and folders. Native
application moves an item into the Recycle Bin and retains its files. A later
descendant carrying content restores it. Folder deletion waits for active children
to move or be deleted. Clocks never choose a conflict winner.

## Directory and immutable objects

Use a writable, non-trashed Drive directory with private app property
`booxNotesProtocol=1`, authorized to the client's own OAuth identity. Android and
Desktop OAuth clients belong to Google project `GOOGLE_PROJECT_ID`.
The current managed directory ID is `DRIVE_FOLDER_ID`.
Knowing that ID alone is not authorization: each client must validate access.

All objects are direct children of the selected directory and have these private
app properties:

| Property | Value |
| --- | --- |
| `booxNotesProtocol` | `1` |
| `booxObjectType` | `payload` or `revision` |
| `booxObjectSha256` | Lowercase SHA-256 of the exact object bytes |

Payload names are `payload-<sha256>.note`, revision names
`revision-<sha256>.json`. Names are descriptive, never unique Drive identities.
Group by type/hash and verify all matching Drive IDs. Duplicates are acceptable
only when their downloaded bytes all match the advertised hash. Do not edit,
trash or delete revision/payload objects in this first implementation.

Upload the payload first, verify returned metadata/parent/size/MD5 and download
SHA-256, then publish the revision. The verified revision record is the publication
point. Before retrying a write with an unknown result, list and validate existing
objects with the same logical identity. A retained payload without a revision is
an incomplete publication, not a notebook update.

Automatic clients may reuse previously verified media only after a fresh listing
confirms the same account/folder, Drive ID, Drive version, properties, parent,
size and MD5. Recheck cached bytes against their advertised SHA-256 and MD5.
A new duplicate ID or changed version must be downloaded and verified separately.
Cache reuse does not waive complete listing, ancestry or resident-size limits.

## Revision encoding

Exactly these six fields, in this order, without whitespace:

```json
{"deleted":false,"device":"device-id","notebook":"sync-notebook-id","parents":[],"payload":"64-lowercase-hex-characters","schema":1}
```

Identifiers match `[A-Za-z0-9_-]{1,128}`. The schema is integer `1`, deleted is a
JSON boolean, parents is a sorted unique array of up to 64 lowercase SHA-256
strings. Payload is a lowercase SHA-256 string, or null exactly when deleted=true.
Revision ID is SHA-256 of its canonical encoding. Reject unknown/missing fields,
wrong types, duplicate JSON keys, noncanonical encoding and mismatching hashes.

Parents must exist, belong to the same notebook, and have valid complete ancestry.
Every referenced payload must be available and verified before a catalog is usable.
Derive heads from ancestry, never timestamps or filenames. Concurrent heads are
conflicts and must remain visible; do not automatically choose one. Empty listings
do not delete local content. A deletion revision is a request for review, not
permission to delete a user's local notebook.

## Bounded first implementation

Limits: 4 MiB per payload, 16 KiB per revision, 1,000 unique Drive object IDs,
100 listing pages, and 64 MiB total downloaded object bytes per refresh. Reject
incomplete searches, repeated page tokens, mismatching parents/properties,
unexpected object types, invalid hashes, cycles, missing ancestry, corrupt objects,
or limit overflows before applying/acknowledging remote state.

Persist outgoing payload and revision bytes before any network operation.
Persist their account permission ID, folder ID, device ID and captured parent
revision IDs. A retry must preserve these bytes and parents. Reauthorization to
a different account or folder cannot silently redirect queued data.
Report pending/error/verified publication separately. Do not mark a native
notebook applied merely because an upload/download succeeded.

## Disposable interoperability exercise

Use notebook sync ID `boox-validation-notebook-v1`. Each installation has its own
persisted random device ID. Android publishes its bundled `Target-after.note`.
The Mac downloads, verifies and renders it; the Mac may publish an existing
synthetic fixture as a descendant for transport validation. Android refreshes
and verifies that descendant without importing it over a user notebook.

Publishing a fixture is an explicit validation action. Automatic library sync
and automatic native application remain disabled until their separate recovery
and fidelity requirements are met. The Mac replaces the proposed second BOOX
device for this round of validation; other firmware compatibility is untested.

## Explicit conflict selection

Android conflict review follows the same immutable ancestry rule as the Mac:
a user selects one complete current version. The resolution revision copies that
version's payload (or tombstone) and lists every reviewed head as a parent. A
fresh complete catalog must still contain exactly those heads, and the clean local
branch must be represented in their ancestry. The connector never overwrites
remote objects or merges strokes by timestamp. Later concurrent publications
remain visible as conflicts. Applying the resulting single descendant still uses
the native journal and readback checks. Losing a network response retries the
same durable revision identity.
