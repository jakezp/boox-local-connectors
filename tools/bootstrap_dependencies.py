#!/usr/bin/env python3
"""Plan pinned dependencies; explicitly materialize them in a NEW host directory.

No SDK tools, downloaded scripts, device commands or builds are executed.
Default plan needs no network. --apply requires an unchanged --reviewed-plan;
--offline --archive-root reuses opaque archives after exact size/SHA verification.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import urllib.parse
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/reproduction/acquisition.json"
DEFAULT = ("platform_tools", "android_build_tools", "android_platform", "gradle")
ALLOWED_HOSTS = {
    "dl.google.com", "services.gradle.org", "downloads.gradle.org",
    "github.com", "raw.githubusercontent.com", "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
}
MAX_EXPANDED = 1024 * 1024 * 1024
MAX_ENTRIES = 30000


class BootstrapError(ValueError):
    pass


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


def relative_path(value):
    if not isinstance(value, str) or len(value) > 500:
        raise BootstrapError("Invalid relative path")
    parts = value.split("/")
    if any(part in {"", ".", ".."} or not re.fullmatch(r"[A-Za-z0-9_.+-]+", part)
           for part in parts):
        raise BootstrapError("Invalid relative path")
    return PurePosixPath(value)


def check_url(url):
    if not isinstance(url, str):
        raise BootstrapError("Missing pinned HTTPS URL")
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS
            or parsed.username or parsed.password or parsed.port not in (None, 443)
            or parsed.fragment):
        raise BootstrapError("Unsupported download URL")


def plan_dependencies(manifest_path, selected):
    document = json.loads(Path(manifest_path).read_bytes())
    if document.get("schema") != 1:
        raise BootstrapError("Unsupported acquisition schema")
    catalog = {}
    for item in document["artifacts"]:
        if item["name"] in catalog:
            raise BootstrapError("Duplicate dependency name")
        catalog[item["name"]] = item
    if not selected or len(selected) != len(set(selected)):
        raise BootstrapError("Empty or duplicate dependency selection")
    artifacts = []
    destinations = set()
    for name in sorted(selected):
        item = catalog.get(name, {})
        bootstrap = item.get("bootstrap")
        if not bootstrap:
            raise BootstrapError("Selected dependency has no declared bootstrap recipe")
        check_url(item.get("source_url"))
        if (not isinstance(item.get("sha256"), str)
                or not re.fullmatch("[a-f0-9]{64}", item["sha256"])
                or type(item.get("bytes")) is not int
                or not 0 < item["bytes"] <= MAX_EXPANDED):
            raise BootstrapError("Selected dependency has no exact size/SHA pin")
        archive = str(relative_path(item["local_archive"]))
        if not archive.startswith("tools/"):
            raise BootstrapError("Archive is outside dependency tools tree")
        layouts = bootstrap.get("zip_layouts", [])
        if not isinstance(layouts, list):
            raise BootstrapError("Invalid archive layout")
        for layout in layouts:
            destination = str(relative_path(layout["destination"]))
            prefix = layout["archive_prefix"]
            if prefix:
                relative_path(prefix.rstrip("/"))
                if not prefix.endswith("/"):
                    raise BootstrapError("Archive prefix must end in slash")
            if not destination.startswith("tools/"):
                raise BootstrapError("Layout is outside dependency tools tree")
            for prior in destinations:
                if (destination.casefold() == prior.casefold()
                        or destination.startswith(prior + "/")
                        or prior.startswith(destination + "/")):
                    raise BootstrapError("Overlapping dependency layouts")
            destinations.add(destination)
        artifacts.append({
            "name": name, "version": item["version"], "source_url": item["source_url"],
            "sha256": item["sha256"], "bytes": item["bytes"],
            "local_archive": archive, "zip_layouts": layouts,
        })
    result = {
        "schema": 1, "status": "planned_not_acquired", "host": "macOS",
        "artifacts": artifacts,
        "unpinned_requirements_not_installed": [
            "JDK 17 acquisition", "Xcode/Swift acquisition", "Python/package environment",
            "Full Gradle transitive dependency lock", "Firmware/private signing/OAuth inputs",
        ],
        "boundary": "Only downloads/verifies/extracts declared dependencies; no commands, device operations, licenses accepted, builds or Git.",
        "bootstrap_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }
    result["plan_id"] = hashlib.sha256(encoded(result)).hexdigest()
    return result


class PinnedRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def checked_copy(source, destination, item):
    digest, count = hashlib.sha256(), 0
    with destination.open("xb") as target:
        while chunk := source.read(1024 * 1024):
            count += len(chunk)
            if count > item["bytes"]:
                raise BootstrapError("Archive exceeds declared size")
            digest.update(chunk)
            target.write(chunk)
    if count != item["bytes"] or digest.hexdigest() != item["sha256"]:
        raise BootstrapError("Archive size or SHA does not match")


def inspect_zip(path, prefix):
    """Validate every entry before creating any extracted payload."""
    members, seen, expanded = [], set(), 0
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > MAX_ENTRIES:
            raise BootstrapError("Too many ZIP entries")
        for info in archive.infolist():
            raw = info.filename.rstrip("/")
            relative_path(raw)
            mode = info.external_attr >> 16
            if stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise BootstrapError("ZIP contains a link or special file")
            if info.flag_bits & 1:
                raise BootstrapError("Encrypted ZIP entry")
            if info.filename.casefold() in seen:
                raise BootstrapError("Duplicate or case-colliding ZIP entries")
            seen.add(info.filename.casefold())
            expanded += info.file_size
            if expanded > MAX_EXPANDED:
                raise BootstrapError("ZIP exceeds expanded size limit")
            if prefix and raw == prefix.rstrip("/") and info.is_dir():
                continue
            if not info.filename.startswith(prefix):
                raise BootstrapError("ZIP layout differs from declared prefix")
            relative = info.filename[len(prefix):].rstrip("/")
            relative_path(relative)
            members.append((info, relative))
    if not members:
        raise BootstrapError("Empty ZIP layout")
    return members


def extract_zip(path, destination, prefix):
    members = inspect_zip(path, prefix)
    destination.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(path) as archive:
        for info, relative in members:
            target = destination / relative
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                target.chmod(0o755 if (info.external_attr >> 16) & 0o111 else 0o644)


def open_local(root, name):
    """Never follow symlinks inside the explicitly supplied archive root."""
    relative_path(name)
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        parts = name.split("/")
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=directory)
        stream = os.fdopen(fd, "rb")
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            stream.close()
            raise BootstrapError("Local archive is not a regular file")
        return stream
    finally:
        os.close(directory)


def materialize(plan, destination, archive_root=None, offline=False):
    # A new isolated tree is mandatory. Never merge/replace an existing SDK.
    destination.mkdir(mode=0o700, parents=False, exist_ok=False)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), PinnedRedirect())
    verified = []
    try:
        for item in plan["artifacts"]:
            target = destination / item["local_archive"]
            target.parent.mkdir(parents=True, exist_ok=True)
            if archive_root is not None:
                with open_local(archive_root, item["local_archive"]) as source:
                    checked_copy(source, target, item)
            elif offline:
                raise BootstrapError("Offline mode requires local archives")
            else:
                with opener.open(item["source_url"], timeout=60) as source:
                    check_url(source.geturl())
                    checked_copy(source, target, item)
            verified.append(item)
        # No extracted payload appears until all selected archives pass SHA checks.
        for item in verified:
            for layout in item["zip_layouts"]:
                inspect_zip(destination / item["local_archive"], layout["archive_prefix"])
        for item in verified:
            for layout in item["zip_layouts"]:
                extract_zip(destination / item["local_archive"],
                            destination / layout["destination"], layout["archive_prefix"])
        receipt = {"schema": 1, "status": "dependencies_materialized_not_executed",
                   "plan_id": plan["plan_id"], "artifacts": plan["artifacts"]}
        (destination / "dependency-receipt.json").write_bytes(encoded(receipt))
        return receipt
    except Exception:
        # Retain only this newly created partial tree for review; never delete or
        # alter an existing user's directory. Absence of receipt means incomplete.
        raise BootstrapError("Bootstrap incomplete; new partial directory retained") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--select", action="append", help="Repeat declared artifact names; default is Android host toolchain")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--reviewed-plan", type=Path)
    parser.add_argument("--archive-root", type=Path)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args(argv)
    if args.apply and (not args.destination or not args.reviewed_plan):
        parser.error("--apply requires --destination NEWDIR and --reviewed-plan")
    if not args.apply and any((args.destination, args.reviewed_plan, args.archive_root, args.offline)):
        parser.error("Materialization options require --apply")
    if args.offline and not args.archive_root:
        parser.error("--offline requires --archive-root")
    try:
        plan = plan_dependencies(args.manifest, args.select or DEFAULT)
        if args.apply:
            if json.loads(args.reviewed_plan.read_bytes()) != plan:
                raise BootstrapError("Reviewed plan differs")
            result = materialize(plan, args.destination, args.archive_root, args.offline)
        else:
            result = plan
        sys.stdout.write(encoded(result).decode())
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile):
        sys.stderr.write("Dependency bootstrap refused: invalid plan/input, digest/layout mismatch, "
                         "unsafe or existing destination, or acquisition failure. A new partial tree "
                         "may remain; no downloaded tools were executed.\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
