#!/usr/bin/env python3
"""Archive EVERY workspace entry, ignoring no files and following no symlinks.

Default is metadata-only planning. --create writes a private, unencrypted,
resumable split PAX tar OUTSIDE the source; --gzip compresses before splitting.
--verify checks chunks and every
archived file against the embedded inventory. --reassemble joins verified parts
in manifest order to a NEW tar output. No upload, encryption, Git,
Keychain access, device commands or extraction is performed.
"""

import argparse
from contextlib import contextmanager, nullcontext
from decimal import Decimal
import fcntl
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
import zlib


ROOT = Path(__file__).resolve().parents[1]
MIB = 1024 * 1024
DEFAULT_CHUNK = 1024 * MIB
BLOCK = MIB
METADATA_LIMITATIONS = [
    "No ACLs, extended attributes/resource forks, birthtime or filesystem flags",
    "External symlink referents are outside the workspace and are not copied",
    "Not an atomic filesystem snapshot; quiesce writers before creation/resume",
]


class ArchiveError(ValueError):
    pass


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=True) + "\n").encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def same_metadata(first, second, recheck_content=False):
    """ctime can change for hard links/xattrs without changing archived data."""
    if not recheck_content:
        return first == second
    if len(first) != len(second):
        return False
    for left, right in zip(first, second):
        left = {k: v for k, v in left.items() if k != "ctime_ns"}
        right = {k: v for k, v in right.items() if k != "ctime_ns"}
        if left != right:
            return False
    return True


def metadata(path, root):
    s = path.lstat()
    if stat.S_ISREG(s.st_mode):
        kind = "file"
    elif stat.S_ISDIR(s.st_mode):
        kind = "directory"
    elif stat.S_ISLNK(s.st_mode):
        kind = "symlink"
    else:
        raise ArchiveError("Special filesystem entry: archive refuses silent omission")
    result = {
        "path": path.relative_to(root).as_posix(), "kind": kind,
        "mode": stat.S_IMODE(s.st_mode), "size": s.st_size,
        "uid": s.st_uid, "gid": s.st_gid,
        "mtime_ns": s.st_mtime_ns, "ctime_ns": s.st_ctime_ns,
        "device": s.st_dev, "inode": s.st_ino,
    }
    if kind == "symlink":
        target = os.readlink(path)
        result["link_target"] = target
        # Lexical classification only: do not inspect an external referent.
        resolved = Path(os.path.normpath(os.path.join(str(path.parent), target)))
        result["external_link"] = not resolved.is_relative_to(root)
    return result


def scan(root):
    """No ignore rules, permission-error skipping, or symlink traversal."""
    root = Path(root).absolute()
    entries = []

    def walk(path):
        entry = metadata(path, root)
        entries.append(entry)
        if entry["kind"] == "directory":
            with os.scandir(path) as directory:
                children = sorted(item.name for item in directory)
            for name in children:
                walk(path / name)

    if root.is_symlink() or not root.is_dir():
        raise ArchiveError("Source must be a real directory")
    walk(root)
    return sorted(entries, key=lambda row: row["path"])


def summary(entries):
    files = [row for row in entries if row["kind"] == "file"]
    unique = {(row["device"], row["inode"]): row["size"] for row in files}
    return {
        "entries": len(entries),
        "regular_files": len(files),
        "directories_including_root": sum(row["kind"] == "directory" for row in entries),
        "symlinks": sum(row["kind"] == "symlink" for row in entries),
        "direct_external_symlinks": sum(row.get("external_link", False) for row in entries),
        "logical_file_bytes": sum(row["size"] for row in files),
        "unique_inode_file_bytes": sum(unique.values()),
        "files_over_100_MiB": sum(row["size"] > 100 * MIB for row in files),
        "exclusions": [],
        "metadata_limitations": METADATA_LIMITATIONS,
    }


def write_new(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def replace_state(directory, value):
    fd, name = tempfile.mkstemp(prefix=".state-", dir=directory)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(encoded(value))
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, directory / "state.json")
    finally:
        if os.path.exists(name):
            os.unlink(name)


def file_hash(path):
    h, size = hashlib.sha256(), 0
    with regular_open(path) as source:
        while block := source.read(BLOCK):
            h.update(block)
            size += len(block)
    return size, h.hexdigest()


@contextmanager
def regular_open(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise ArchiveError("Expected a regular archive file")
        yield source


def control_bytes(path):
    with regular_open(path) as source:
        if os.fstat(source.fileno()).st_size > 512 * MIB:
            raise ArchiveError("Control file exceeds size limit")
        return source.read()


def part_name(index):
    return f"part-{index:06d}.tarpart"


def validate_chunks(directory, chunks):
    for index, chunk in enumerate(chunks, 1):
        if chunk["name"] != part_name(index):
            raise ArchiveError("Invalid chunk order or name")
        if file_hash(directory / chunk["name"]) != (chunk["bytes"], chunk["sha256"]):
            raise ArchiveError("Chunk checksum mismatch")


class SplitWriter:
    """Replay retained chunks on resume; never rewrite completed chunk bytes."""

    def __init__(self, directory, state, chunk_size):
        self.directory, self.state, self.chunk_size = directory, state, chunk_size
        self.index = 1
        self.position = self.total = 0
        self.whole = hashlib.sha256()
        self.current = hashlib.sha256()
        self.output = None
        self.reusing = False
        self.aborted = False

    def start(self):
        final = self.directory / part_name(self.index)
        partial = self.directory / (part_name(self.index) + ".partial")
        if partial.exists() or partial.is_symlink():
            if partial.is_symlink() or not partial.is_file():
                raise ArchiveError("Unsafe partial chunk")
            # Only the next tool-owned partial under the exclusive writer lock.
            partial.unlink()
        self.reusing = final.exists() or final.is_symlink()
        if self.reusing:
            if final.is_symlink() or not final.is_file():
                raise ArchiveError("Unsafe completed chunk")
        else:
            fd = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            self.output = os.fdopen(fd, "wb")

    def commit(self):
        record = {"name": part_name(self.index), "bytes": self.position,
                  "sha256": self.current.hexdigest()}
        final = self.directory / record["name"]
        if self.reusing:
            if file_hash(final) != (record["bytes"], record["sha256"]):
                raise ArchiveError("Replayed chunk differs; source or output changed")
        else:
            self.output.flush()
            os.fsync(self.output.fileno())
            self.output.close()
            self.output = None
            partial = self.directory / (record["name"] + ".partial")
            os.link(partial, final)  # Refuses replacement; crash-safe complete file.
            partial.unlink()
        previous = self.state["chunks"]
        if self.index <= len(previous):
            if previous[self.index - 1] != record:
                raise ArchiveError("Resume ledger differs")
        else:
            previous.append(record)
            replace_state(self.directory, self.state)
        self.index += 1
        self.position = 0
        self.current = hashlib.sha256()

    def write(self, data):
        try:
            return self.write_bytes(data)
        except BaseException:
            self.aborted = True
            raise

    def write_bytes(self, data):
        # tarfile may retry its buffered bytes while unwinding an exception.
        # Discard cleanup writes after abort; retained complete chunks stay valid.
        if self.aborted:
            return len(data)
        view = memoryview(data)
        offset = 0
        while offset < len(view):
            if self.position == 0:
                self.start()
            count = min(self.chunk_size - self.position, len(view) - offset)
            piece = view[offset:offset + count]
            self.current.update(piece)
            self.whole.update(piece)
            if self.output is not None:
                self.output.write(piece)
            self.position += count
            self.total += count
            offset += count
            if self.position == self.chunk_size:
                self.commit()
        return len(data)

    def finish(self):
        if self.position:
            self.commit()
        if self.index - 1 != len(self.state["chunks"]):
            raise ArchiveError("Unexpected trailing chunk ledger")

    def close_partial(self):
        if self.output is not None:
            self.output.close()
            self.output = None


@contextmanager
def source_file(root, entry, recheck_content=False):
    """Open through pinned directory descriptors; no symlink substitution."""
    parts = entry["path"].split("/")
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    stream = None
    try:
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
        source = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        stream = os.fdopen(source, "rb")
        current = os.fstat(stream.fileno())
        if (not stat.S_ISREG(current.st_mode)
                or (current.st_dev, current.st_ino, current.st_size,
                    current.st_mtime_ns)
                != (entry["device"], entry["inode"], entry["size"],
                    entry["mtime_ns"])
                or (not recheck_content and current.st_ctime_ns != entry["ctime_ns"])):
            raise ArchiveError("Source changed before read")
        yield stream
        after = os.fstat(stream.fileno())
        if ((current.st_size, current.st_mtime_ns) != (after.st_size, after.st_mtime_ns)
                or (not recheck_content and current.st_ctime_ns != after.st_ctime_ns)):
            raise ArchiveError("Source changed during read")
    finally:
        if stream:
            stream.close()
        os.close(fd)


class HashReader:
    def __init__(self, stream):
        self.stream, self.hash = stream, hashlib.sha256()

    def read(self, size=-1):
        data = self.stream.read(size)
        self.hash.update(data)
        return data


def tar_info(entry):
    name = "workspace" if entry["path"] == "." else "workspace/" + entry["path"]
    info = tarfile.TarInfo(name)
    info.mode, info.uid, info.gid = entry["mode"], entry["uid"], entry["gid"]
    info.mtime = entry["mtime_ns"] // 1_000_000_000
    info.pax_headers = {"mtime": format(Decimal(entry["mtime_ns"]) / 1_000_000_000, ".9f")}
    if entry["kind"] == "directory":
        info.type = tarfile.DIRTYPE
    elif entry["kind"] == "symlink":
        info.type, info.linkname = tarfile.SYMTYPE, entry["link_target"]
    else:
        info.size = entry["size"]
    return info


@contextmanager
def abort_before_tar_cleanup(writer):
    try:
        yield
    except BaseException:
        writer.aborted = True
        raise


def stream_archive(root, entries, writer, compress=False, recheck_content=False):
    completed, inodes = [], {}
    transport = (gzip.GzipFile(fileobj=writer, mode="wb", filename="", mtime=0, compresslevel=6)
                 if compress else nullcontext(writer))
    with transport as target, abort_before_tar_cleanup(writer), \
            tarfile.open(fileobj=target, mode="w|", format=tarfile.PAX_FORMAT) as archive, \
            abort_before_tar_cleanup(writer):
        for entry in entries:
            if not same_metadata([metadata(root / entry["path"], root)], [entry], recheck_content):
                raise ArchiveError("Source metadata changed")
            info, record = tar_info(entry), dict(entry)
            if entry["kind"] == "file":
                identity = entry["device"], entry["inode"]
                if identity in inodes:
                    first = inodes[identity]
                    info.type, info.size = tarfile.LNKTYPE, 0
                    info.linkname = "workspace/" + first["path"]
                    record["hardlink_to"] = first["path"]
                    record["sha256"] = first["sha256"]
                    archive.addfile(info)
                else:
                    with source_file(root, entry, recheck_content) as source:
                        reader = HashReader(source)
                        archive.addfile(info, reader)
                        record["sha256"] = reader.hash.hexdigest()
                    inodes[identity] = record
            else:
                archive.addfile(info)
            completed.append(record)
        if not same_metadata(scan(root), entries, recheck_content):
            raise ArchiveError("Workspace changed; no complete receipt will be issued")
        if recheck_content:
            # A complete second read proves that metadata-only churn did not hide
            # changed bytes, including edits whose size and mtime were restored.
            for record in completed:
                if record["kind"] != "file":
                    continue
                with source_file(root, record, recheck_content=True) as source:
                    actual = hashlib.sha256()
                    while block := source.read(BLOCK):
                        actual.update(block)
                if actual.hexdigest() != record["sha256"]:
                    raise ArchiveError("Source contents changed during archive creation")
            if not same_metadata(scan(root), entries, recheck_content=True):
                raise ArchiveError("Workspace changed during content verification")
        inventory = encoded({"schema": 1, "summary": summary(entries),
                             "entries": completed, "exclusions": []})
        info = tarfile.TarInfo("__archive__/inventory.json")
        info.mode, info.size, info.mtime = 0o600, len(inventory), 0
        archive.addfile(info, io.BytesIO(inventory))
    writer.finish()
    return digest(inventory)


def create(root, directory, chunk_size=DEFAULT_CHUNK, resume=False, compress=False,
           recheck_content=False):
    root, directory = Path(root).absolute(), Path(directory).absolute()
    if directory.resolve().is_relative_to(root.resolve()) or root.resolve().is_relative_to(directory.resolve()):
        raise ArchiveError("Archive output must be outside source and cannot contain it")
    if not 1024 <= chunk_size <= DEFAULT_CHUNK:
        raise ArchiveError("Chunk size must be at most one GiB")
    if resume:
        if directory.is_symlink() or not directory.is_dir():
            raise ArchiveError("Resume requires the existing real archive directory")
    else:
        directory.mkdir(mode=0o700, exist_ok=False)
    lock = os.open(directory / ".archive.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    writer = None
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        entries = scan(root)
        index = encoded({"schema": 1, "entries": entries})
        if len(index) > 512 * MIB:
            raise ArchiveError("Source index exceeds resumable control-file limit")
        compression = ({"codec": "gzip", "level": 6, "mtime": 0,
                        "zlib_runtime": zlib.ZLIB_RUNTIME_VERSION} if compress else None)
        if resume:
            state = json.loads(control_bytes(directory / "state.json"))
            saved_index = control_bytes(directory / "source-index.json")
            saved_entries = json.loads(saved_index)["entries"]
            if (state["index_sha256"] != digest(saved_index)
                    or state["chunk_bytes"] != chunk_size
                    or state.get("compression") != compression
                    or state.get("recheck_content", False) != recheck_content
                    or not same_metadata(entries, saved_entries, recheck_content)):
                raise ArchiveError("Resume source/index/options differ; start a new snapshot")
            # Retain the original index so replay and embedded metadata stay
            # deterministic; every source byte is checked again in this mode.
            entries = saved_entries
            validate_chunks(directory, state["chunks"])
            if state["status"] == "complete" and not recheck_content:
                return verify(directory)
        else:
            write_new(directory / "source-index.json", index)
            state = {"schema": 1, "status": "in_progress", "index_sha256": digest(index),
                     "chunk_bytes": chunk_size, "compression": compression,
                     "recheck_content": recheck_content, "chunks": []}
            replace_state(directory, state)
        writer = SplitWriter(directory, state, chunk_size)
        inventory_hash = stream_archive(root, entries, writer, compress, recheck_content)
        manifest = {
            "schema": 1, "status": "complete_unencrypted_local_archive",
            "format": "split-gzip-pax-tar" if compress else "split-pax-tar",
            "compression": compression, "chunk_bytes": chunk_size,
            "archive_bytes": writer.total, "archive_sha256": writer.whole.hexdigest(),
            "inventory_sha256": inventory_hash, "chunks": state["chunks"],
            "source_content_second_pass": recheck_content,
            "summary": summary(entries), "exclusions": [],
        }
        target = directory / "archive-manifest.json"
        if target.exists():
            if control_bytes(target) != encoded(manifest):
                raise ArchiveError("Existing final manifest differs")
        else:
            write_new(target, encoded(manifest))
        state["status"] = "complete"
        replace_state(directory, state)
        if resume and recheck_content:
            return verify(directory)
        return {"status": manifest["status"], "summary": manifest["summary"],
                "chunks": len(state["chunks"]), "archive_bytes": writer.total,
                "archive_sha256": manifest["archive_sha256"]}
    finally:
        if writer:
            writer.close_partial()
        os.close(lock)


class JoinedReader:
    def __init__(self, directory, chunks):
        self.directory, self.chunks, self.index = directory, chunks, 0
        self.stream = None

    def read(self, size):
        result = bytearray()
        while len(result) < size:
            if self.stream is None:
                if self.index == len(self.chunks):
                    break
                path = self.directory / self.chunks[self.index]["name"]
                fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                self.stream = os.fdopen(fd, "rb")
                if not stat.S_ISREG(os.fstat(self.stream.fileno()).st_mode):
                    self.close()
                    raise ArchiveError("Expected regular chunk")
                self.index += 1
            block = self.stream.read(size - len(result))
            if not block:
                self.stream.close()
                self.stream = None
            result.extend(block)
        return bytes(result)

    def close(self):
        if self.stream:
            self.stream.close()


def verify(directory):
    directory = Path(directory)
    manifest = json.loads(control_bytes(directory / "archive-manifest.json"))
    if manifest.get("schema") != 1 or manifest.get("status") != "complete_unencrypted_local_archive":
        raise ArchiveError("No complete archive manifest")
    validate_chunks(directory, manifest["chunks"])
    whole, length = hashlib.sha256(), 0
    for chunk in manifest["chunks"]:
        with regular_open(directory / chunk["name"]) as source:
            while block := source.read(BLOCK):
                whole.update(block)
                length += len(block)
    if length != manifest["archive_bytes"] or whole.hexdigest() != manifest["archive_sha256"]:
        raise ArchiveError("Whole archive checksum mismatch")
    reader = JoinedReader(directory, manifest["chunks"])
    seen, inventory = {}, None
    try:
        with tarfile.open(fileobj=reader, mode="r|*") as archive:
            for member in archive:
                if inventory is not None:
                    raise ArchiveError("Unexpected member after inventory")
                if member.name == "__archive__/inventory.json":
                    if not member.isfile() or member.size > 512 * MIB:
                        raise ArchiveError("Invalid inventory member")
                    data = archive.extractfile(member).read()
                    if digest(data) != manifest["inventory_sha256"]:
                        raise ArchiveError("Inventory checksum mismatch")
                    inventory = json.loads(data)
                    continue
                if member.name != "workspace" and not member.name.startswith("workspace/"):
                    raise ArchiveError("Unexpected member path")
                relative = "." if member.name == "workspace" else member.name[len("workspace/"):]
                if relative in seen:
                    raise ArchiveError("Duplicate archive member")
                record = {"mode": member.mode, "uid": member.uid, "gid": member.gid,
                          "mtime_ns": int(Decimal(member.pax_headers.get("mtime", str(member.mtime)))
                                          * 1_000_000_000)}
                if member.isfile():
                    content = archive.extractfile(member)
                    h = hashlib.sha256()
                    while block := content.read(BLOCK):
                        h.update(block)
                    record.update(kind="file", size=member.size, sha256=h.hexdigest())
                elif member.isdir():
                    record["kind"] = "directory"
                elif member.issym():
                    record.update(kind="symlink", link_target=member.linkname)
                elif member.islnk():
                    target = member.linkname.removeprefix("workspace/")
                    if not member.linkname.startswith("workspace/") or target not in seen:
                        raise ArchiveError("Invalid hard-link target")
                    record.update(kind="file", hardlink_to=target,
                                  size=seen[target]["size"], sha256=seen[target]["sha256"])
                else:
                    raise ArchiveError("Unexpected special archive member")
                seen[relative] = record
    finally:
        reader.close()
    if not inventory or len(inventory["entries"]) != len(seen):
        raise ArchiveError("Missing or incomplete file inventory")
    if {entry["path"] for entry in inventory["entries"]} != set(seen):
        raise ArchiveError("Duplicate or missing inventory paths")
    for entry in inventory["entries"]:
        if entry["path"] not in seen:
            raise ArchiveError("Inventory member absent")
        for key, value in seen[entry["path"]].items():
            if entry.get(key) != value:
                raise ArchiveError("Per-entry inventory check failed")
    return {"status": "verified_all_chunks_and_inventory", "entries": len(seen),
            "archive_sha256": whole.hexdigest(), "chunks": len(manifest["chunks"]),
            "metadata_limitations": METADATA_LIMITATIONS}


def reassemble(directory, destination):
    directory, destination = Path(directory), Path(destination)
    if destination.resolve().is_relative_to(directory.resolve()):
        raise ArchiveError("Reassembled output must be outside the archive directory")
    checked = verify(directory)
    manifest = json.loads(control_bytes(directory / "archive-manifest.json"))
    if manifest["archive_sha256"] != checked["archive_sha256"]:
        raise ArchiveError("Manifest changed after verification")
    validate_chunks(directory, manifest["chunks"])
    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    whole, count = hashlib.sha256(), 0
    with os.fdopen(fd, "wb") as output:
        for chunk in manifest["chunks"]:
            with regular_open(directory / chunk["name"]) as source:
                while data := source.read(BLOCK):
                    output.write(data)
                    whole.update(data)
                    count += len(data)
        output.flush()
        os.fsync(output.fileno())
    if whole.hexdigest() != checked["archive_sha256"] or count != manifest["archive_bytes"]:
        raise ArchiveError("Chunks changed during reassembly; incomplete output retained")
    return {"status": "reassembled_and_verified", "archive_sha256": whole.hexdigest(),
            "archive_bytes": count, "format": manifest["format"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--create", type=Path, metavar="NEW_OUTPUT_DIR")
    modes.add_argument("--verify", type=Path, metavar="ARCHIVE_DIR")
    modes.add_argument("--reassemble", type=Path, metavar="ARCHIVE_DIR")
    parser.add_argument("--tar-output", type=Path, help="NEW output file for --reassemble")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--gzip", action="store_true",
                        help="Stream deterministic gzip level 6 before chunking; repeat on resume")
    parser.add_argument("--recheck-content", action="store_true",
                        help="Rehash every source file after writing; tolerate ctime-only churn")
    parser.add_argument("--chunk-mib", type=int, default=1024)
    args = parser.parse_args(argv)
    if args.resume and not args.create:
        parser.error("--resume requires --create")
    if args.gzip and not args.create:
        parser.error("--gzip requires --create; verification auto-detects compression")
    if args.recheck_content and not args.create:
        parser.error("--recheck-content requires --create")
    if bool(args.reassemble) != bool(args.tar_output):
        parser.error("--reassemble and --tar-output must be used together")
    if not 1 <= args.chunk_mib <= 1024:
        parser.error("--chunk-mib must be between 1 and 1024")
    try:
        if args.verify:
            result = verify(args.verify)
        elif args.reassemble:
            result = reassemble(args.reassemble, args.tar_output)
        elif args.create:
            result = create(args.root, args.create, args.chunk_mib * MIB,
                            args.resume, args.gzip, args.recheck_content)
        else:
            result = {"status": "metadata_only_no_payloads_read", **summary(scan(args.root))}
        sys.stdout.write(encoded(result).decode())
        return 0
    except (ArchiveError, OSError, ValueError, KeyError, TypeError, tarfile.TarError):
        # Never print a source filename, symlink target, file content or OS path.
        sys.stderr.write("Archive operation stopped: unsafe/changed/unreadable input, "
                         "unsupported entry, invalid state, checksum failure or existing output. "
                         "Source files were not modified; incomplete output is not a backup.\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
