"""Prepare a bounded native replacement from captured import maps.

Only the saved pen/link fixtures are supported. This research utility rewrites
equal-length UUIDs in copied native components; it is not a general .note editor.
All work is local. ApplyMain separately checks the unchanged native baseline.
"""

from copy import deepcopy
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import re
import shutil
import sqlite3
import sys
from uuid import UUID, uuid4
from zipfile import ZipFile

from native_fixture import inspect_archive
from sync_protocol import Revision


PARTS = ("database", "document", "point")
CONTENT_COLUMNS = (
    "notePageInfo", "noteBackground", "pageCount", "pageNameList",
    "richTextPageNameList", "removePageList", "pageOriginWidth",
    "pageOriginHeight", "miniRequiredVersion", "version",
)


def tree_hash(root):
    root = Path(root)
    entries = {}

    def visit(path, relative):
        if path.is_symlink():
            raise ValueError("Linked native component")
        if path.is_dir():
            entries[relative + "/"] = "directory"
            for child in path.iterdir():
                visit(child, f"{relative}/{child.name}" if relative else child.name)
        else:
            entries[relative] = sha256(path.read_bytes()).hexdigest()

    visit(root, "")
    return sha256("".join(f"{key}={value}\n" for key, value in sorted(entries.items())).encode()).hexdigest()


def replacements(target, staged):
    if target["source"] != staged["source"]:
        raise ValueError("Different source lineages")
    if set(target["pages"]) != set(staged["pages"]):
        raise ValueError("Page addition/deletion is outside this fixture experiment")
    if not set(target["shapes"]).issubset(staged["shapes"]):
        raise ValueError("Shape deletion is outside this fixture experiment")
    pages = dict(target["pages"])
    shapes = {source: target["shapes"].get(source, str(uuid4()))
              for source in staged["shapes"]}
    mapping = {staged["destination"]: target["destination"]}
    for incoming, local in ((staged["pages"], pages), (staged["shapes"], shapes)):
        mapping.update({native: local[source] for source, native in incoming.items()})
    for old, new in mapping.items():
        if str(UUID(old)) != old or str(UUID(new)) != new:
            raise ValueError("Only canonical 36-byte UUIDs supported")
    if len(set(mapping.values())) != len(mapping):
        raise ValueError("Identity collision")
    return mapping, {**target, "pages": pages, "shapes": shapes}


def prepare(target_dir, staged_dir, target_map, staged_map, payload, output):
    target_dir, staged_dir, output = map(Path, (target_dir, staged_dir, output))
    target = json.loads(Path(target_map).read_text())
    staged = json.loads(Path(staged_map).read_text())
    mapping, identities = replacements(target, staged)
    if not target["title"].startswith("GDrive-Sync-Probe-Target("):
        raise ValueError("Target fixture required")
    if not staged["title"].startswith("GDrive-Sync-Probe-Staged("):
        raise ValueError("Staged fixture required")
    if len(staged["title"].encode()) != len(target["title"].encode()):
        raise ValueError("This fixture rewrite requires equal-length titles")
    mapping[staged["title"]] = target["title"]
    binary = {key.encode(): value.encode() for key, value in mapping.items()}
    pattern = re.compile(b"|".join(re.escape(key) for key in binary))

    def rewrite(data):
        return pattern.sub(lambda match: binary[match.group()], data)

    def rewrite_file(data, name):
        if name.endswith(".zip"):
            out = BytesIO()
            with ZipFile(BytesIO(data)) as source, ZipFile(out, "w") as destination:
                for entry in source.infolist():
                    content = source.read(entry.filename)
                    entry.filename = rewrite(entry.filename.encode()).decode()
                    destination.writestr(entry, rewrite(content))
            return out.getvalue()
        return rewrite(data)

    if output.exists():
        raise ValueError("Use a fresh output directory")
    output.mkdir(parents=True)
    for part in ("document", "point"):
        source = staged_dir / part
        destination = output / "new" / part
        destination.mkdir(parents=True)
        for path in sorted(source.rglob("*")):
            relative = rewrite(str(path.relative_to(source)).encode()).decode()
            new = destination / relative
            if path.is_dir():
                new.mkdir(exist_ok=True)
            elif path.is_file() and not path.is_symlink():
                new.write_bytes(rewrite_file(path.read_bytes(), path.name))
            else:
                raise ValueError("Unsupported fixture entry")
    database_path = output / "new/database"
    shutil.copyfile(staged_dir / "database", database_path)
    connection = sqlite3.connect(database_path)
    try:
        if connection.execute("PRAGMA integrity_check").fetchone() != ("ok",):
            raise ValueError("Damaged staged database")
        unsupported = connection.execute("""
            SELECT COUNT(*) FROM NewShapeModel
            WHERE shapeType NOT IN (2,33) OR
              (resourceId IS NOT NULL AND resourceId != '') OR
              (richText IS NOT NULL AND richText != '') OR points IS NOT NULL
        """).fetchone()[0]
        if unsupported:
            raise ValueError("Unsupported native content")
        tables = [row[0] for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
        for table in tables:
            if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", table):
                raise ValueError("Unexpected table name")
            columns = [row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')]
            for row in connection.execute(f'SELECT rowid,* FROM "{table}"').fetchall():
                changed = {column: rewrite(value.encode()).decode()
                           for column, value in zip(columns, row[1:])
                           if isinstance(value, str) and rewrite(value.encode()) != value.encode()}
                if changed:
                    assignments = ",".join(f'"{column}"=?' for column in changed)
                    connection.execute(f'UPDATE "{table}" SET {assignments} WHERE rowid=?',
                                       (*changed.values(), row[0]))
        connection.commit()
        if connection.execute("PRAGMA integrity_check").fetchone() != ("ok",):
            raise ValueError("Replacement database verification failed")
    finally:
        connection.close()
    before = json.loads((target_dir / "note.json").read_text())
    after = deepcopy(before)
    staged_row = json.loads((staged_dir / "note.json").read_text())
    for column in CONTENT_COLUMNS:
        value = staged_row[column]
        after[column] = rewrite(value.encode()).decode() if isinstance(value, str) else value
    # Revision ancestry, not device wall-clock time, determines ordering.
    after["updatedAt"] = before["updatedAt"] + 1
    payload_data = Path(payload).read_bytes()
    archive = inspect_archive(payload)
    if archive["document_id"] != target["source"]:
        raise ValueError("Payload identity differs from captured import maps")
    base_payload = Path(__file__).resolve().parents[1] / "tests/artifacts/B1.note"
    base = Revision("fixture-notebook", "fixture-source", (), sha256(base_payload.read_bytes()).hexdigest())
    revision = Revision("fixture-notebook", "fixture-source", (base.id,), sha256(payload_data).hexdigest())
    plan = {
        "document": target["destination"], "expectedRow": before, "afterRow": after,
        "expectedHashes": {part: tree_hash(target_dir / part) for part in PARTS},
        "newHashes": {part: tree_hash(output / "new" / part) for part in PARTS},
        "baseRevision": base.id, "incomingRevision": revision.id,
        "payloadSha256": sha256(payload_data).hexdigest(),
    }
    (output / "plan.json").write_text(json.dumps(plan, indent=2))
    (output / "identities.json").write_text(json.dumps(identities, indent=2))
    return plan


if __name__ == "__main__":
    prepare(*sys.argv[1:])
