#!/usr/bin/env python3
"""Recover tar streams polluted by toybox's absolute-path notices in adb exec-out.

Only the exact known notice at a tar header boundary is removed. File data is
never searched/replaced. Every header checksum, payload extent and EOF is checked.
The original file is preserved. New backups should use `tar -C /` and relative paths.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

NOTICE = b"removing leading '/' from member names\n"


def recover(data):
    offset, chunks, notices, headers = 0, [], [], 0
    ended = False
    while offset < len(data):
        if data.startswith(NOTICE, offset):
            notices.append(offset)
            offset += len(NOTICE)
            continue
        block = data[offset:offset + 512]
        if len(block) != 512:
            raise ValueError("Incomplete tar header")
        if not any(block):
            tail = data[offset:]
            if len(tail) < 1024 or len(tail) % 512 or any(tail):
                raise ValueError("Invalid or polluted tar end marker")
            chunks.append(tail)
            ended = True
            break
        info = tarfile.TarInfo.frombuf(block, "utf-8", "surrogateescape")
        if info.size < 0:
            raise ValueError("Negative tar member size")
        end = offset + 512 + ((info.size + 511) // 512) * 512
        if end > len(data):
            raise ValueError("Truncated tar member")
        chunks.append(data[offset:end])
        headers += 1
        offset = end
    if not ended:
        raise ValueError("Missing tar end marker")
    repaired = b"".join(chunks)
    with tarfile.open(fileobj=io.BytesIO(repaired)) as archive:
        members = archive.getmembers()
        files = {m.name: hashlib.sha256(archive.extractfile(m).read()).hexdigest()
                 for m in members if m.isfile()}
    return repaired, dict(removed_notice_offsets=notices, checked_headers=headers,
                          members=len(members), file_sha256=files)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.destination.exists():
        raise SystemExit("Destination already exists; original/recovered evidence will not be overwritten")
    data = args.source.read_bytes()
    fixed, report = recover(data)
    args.destination.write_bytes(fixed)
    args.destination.chmod(0o600)
    report.update(original_sha256=hashlib.sha256(data).hexdigest(),
                  recovered_sha256=hashlib.sha256(fixed).hexdigest())
    report_path = args.destination.with_suffix(".verification.json")
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    report_path.chmod(0o600)
    print(json.dumps({key: value for key, value in report.items() if key != "file_sha256"}, indent=2))


if __name__ == "__main__":
    main()
