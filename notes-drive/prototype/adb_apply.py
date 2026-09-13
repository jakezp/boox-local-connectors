"""ADB helpers for the explicit disposable-note experiment, not a sync daemon."""

import io
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tarfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import boox_ui


REMOTE = "/data/local/tmp/boox-notes-apply"


def quiesce():
    boox_ui.shell("am start -a android.settings.SETTINGS")
    for package in ("com.onyx.android.note", "com.onyx.android.ksync", "com.onyx"):
        boox_ui.shell("am force-stop " + package)


def run(action, job, extra=None, *, check=True):
    if action not in {"snapshot", "prepare", "apply", "recover", "ack", "status"}:
        raise ValueError("Unknown fixture operation")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", job):
        raise ValueError("Invalid fixture job")
    args = [action, job] + ([] if extra is None else [extra])
    command = f"CLASSPATH={REMOTE}/engine.dex app_process /system/bin local.boox.notesapply.ApplyMain "
    command += " ".join(shlex.quote(arg) for arg in args)
    return subprocess.run(boox_ui.ADB + ["shell", "/debug_ramdisk/su -c " + shlex.quote(command)],
                          text=True, capture_output=True, check=check)


def fetch(job, output):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", job):
        raise ValueError("Invalid fixture job")
    output = Path(output)
    if output.exists():
        raise ValueError("Fresh local output required")
    output.mkdir(parents=True, mode=0o700)
    command = f"tar -cf - -C {REMOTE}/{job} ."
    result = subprocess.check_output(boox_ui.ADB + ["exec-out", "/debug_ramdisk/su", "-c", command])
    with tarfile.open(fileobj=io.BytesIO(result)) as archive:
        for member in archive.getmembers():
            relative = Path(member.name)
            if relative.is_absolute() or ".." in relative.parts or member.issym() or member.islnk():
                raise ValueError("Unsafe snapshot entry")
            target = output / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                target.write_bytes(archive.extractfile(member).read())
                target.chmod(0o600)
            else:
                raise ValueError("Unsupported snapshot entry")


def push_job(source, job):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", job):
        raise ValueError("Invalid fixture job")
    result = boox_ui.shell(f"test -e {REMOTE}/{job} && echo exists || echo absent").strip()
    if result != "absent":
        raise ValueError("Fresh remote job required")
    subprocess.run(boox_ui.ADB + ["push", str(source), f"{REMOTE}/{job}"],
                   check=True, capture_output=True)
    # adb push copies files but omits empty directories, which are part of the
    # native component manifest and must exist before checksum verification.
    directories = [f"{REMOTE}/{job}/{path.relative_to(source)}"
                   for path in Path(source).rglob("*") if path.is_dir()]
    if directories:
        boox_ui.shell("mkdir -p -- " + " ".join(shlex.quote(path) for path in directories))
