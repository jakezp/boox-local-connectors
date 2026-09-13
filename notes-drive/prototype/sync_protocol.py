"""Offline feasibility model, not an Android connector or Drive implementation.

Treat notebook exports as opaque bytes. Publish immutable payloads before immutable
revision records. Derive current revisions from ancestry, never wall-clock time.
The eventual Drive adapter must handle paging, permissions and durable retries.
"""

from dataclasses import asdict, dataclass
from hashlib import sha256
import json
import re
from typing import Iterable


class IncompleteSync(ValueError):
    """Do not apply a remote notebook until its required data is verified."""


def digest(data: bytes) -> str:
    return sha256(data).hexdigest()


@dataclass(frozen=True)
class Revision:
    notebook: str
    device: str
    parents: tuple[str, ...]
    payload: str | None
    deleted: bool = False
    schema: int = 1

    def __post_init__(self):
        for identifier in (self.notebook, self.device):
            if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", identifier):
                raise ValueError("Invalid notebook/device identifier")
        if type(self.schema) is not int or self.schema != 1:
            raise ValueError("Unsupported schema")
        if type(self.deleted) is not bool or self.deleted != (self.payload is None):
            raise ValueError("Only deletion revisions omit the notebook payload")
        if not isinstance(self.parents, tuple) or len(self.parents) > 64:
            raise ValueError("Invalid parent list")
        hashes = self.parents + (() if self.payload is None else (self.payload,))
        if any(not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value) for value in hashes):
            raise ValueError("Invalid hash")
        if tuple(sorted(set(self.parents))) != self.parents:
            raise ValueError("Parents must be unique and sorted")

    def encode(self) -> bytes:
        return json.dumps(asdict(self), sort_keys=True, separators=(",", ":")).encode()

    @property
    def id(self) -> str:
        return digest(self.encode())

    @classmethod
    def decode(cls, data: bytes) -> "Revision":
        if len(data) > 16384:
            raise ValueError("Revision record too large")
        value = json.loads(data)
        expected = {"notebook", "device", "parents", "payload", "deleted", "schema"}
        if not isinstance(value, dict) or set(value) != expected or not isinstance(value["parents"], list):
            raise ValueError("Invalid revision record")
        value["parents"] = tuple(value["parents"])
        return cls(**value)


class MemoryStore:
    """A deliberately small stand-in for immutable Drive objects.

    Dictionaries model logical IDs, not Drive filenames: Drive can contain
    duplicate names. The real adapter must group and validate every matching ID.
    """

    def __init__(self):
        self.payloads: dict[str, bytes] = {}
        self.records: dict[str, bytes] = {}

    def publish(
        self, notebook: str, device: str, payload: bytes | None,
        parents: Iterable[str] = (), *, stop_after_payload: bool = False,
    ) -> str:
        revision = Revision(
            notebook=notebook,
            device=device,
            parents=tuple(sorted(set(parents))),
            payload=None if payload is None else digest(payload),
            deleted=payload is None,
        )
        for parent in revision.parents:
            raw = self.records.get(parent)
            if raw is None:
                raise IncompleteSync("Cannot publish against a missing parent")
            if digest(raw) != parent or Revision.decode(raw).notebook != notebook:
                raise IncompleteSync("Invalid or foreign parent")
        if payload is not None:
            self.payloads.setdefault(revision.payload, payload)
        if not stop_after_payload:
            # This is the publication point. A retry reuses the same logical ID.
            self.records.setdefault(revision.id, revision.encode())
        return revision.id


class Catalog:
    def __init__(self, store: MemoryStore, notebook: str):
        self.revisions: dict[str, Revision] = {}
        for identifier, raw in store.records.items():
            revision = Revision.decode(raw)
            if digest(raw) != identifier:
                raise IncompleteSync("Revision checksum mismatch")
            if revision.notebook == notebook:
                self.revisions[identifier] = revision
        for revision in self.revisions.values():
            if any(parent not in self.revisions for parent in revision.parents):
                raise IncompleteSync("Missing ancestry; do not replace local data")
            if revision.payload is not None:
                payload = store.payloads.get(revision.payload)
                if payload is None or digest(payload) != revision.payload:
                    raise IncompleteSync("Missing or damaged notebook payload")
        parent_ids = {parent for revision in self.revisions.values() for parent in revision.parents}
        self.heads = frozenset(self.revisions) - parent_ids

    def is_ancestor(self, ancestor: str, descendant: str) -> bool:
        pending = [descendant]
        seen = set()
        while pending:
            current = pending.pop()
            if current == ancestor:
                return True
            if current not in seen:
                seen.add(current)
                pending.extend(self.revisions[current].parents)
        return False

    def action(self, applied_revision: str | None, *, local_dirty: bool = False) -> str:
        if not self.heads:
            # An empty/incomplete Drive listing never means "delete local notes".
            return "upload-local" if local_dirty else "no-change"
        if applied_revision is not None and applied_revision not in self.revisions:
            raise IncompleteSync("Applied revision missing from the remote catalog")
        if len(self.heads) > 1:
            return "keep-conflict-copies"
        head = next(iter(self.heads))
        if head == applied_revision:
            return "upload-local" if local_dirty else "no-change"
        if local_dirty or (applied_revision is not None and not self.is_ancestor(applied_revision, head)):
            return "keep-conflict-copies"
        if self.revisions[head].deleted:
            return "review-deletion"
        return "stage-and-verify-import"
