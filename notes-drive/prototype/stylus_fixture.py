"""Draw synthetic strokes only in the disposable BOOX sync fixture.

Uses the observed Note Air4C Wacom event device because Android's virtual stylus
events do not enter this firmware's native stroke recorder. Not a sync component.
"""

from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import boox_ui


SHAPES = {
    "box": [(400, 500, 1000, 500), (1000, 500, 1000, 1100),
            (1000, 1100, 400, 1100), (400, 1100, 400, 500)],
    "line": [(400, 1400, 1100, 1400)],
    "check": [(500, 1600, 700, 1800), (700, 1800, 1100, 1300)],
}


def draw(shape):
    if not any(n.get("resource-id", "").endswith("/note_name") and
               n.get("text", "").startswith("GDrive-Sync-Probe")
               for n in boox_ui.dump()):
        raise RuntimeError("Open a disposable GDrive-Sync-Probe notebook first")
    capabilities = boox_ui.shell('/debug_ramdisk/su -c "getevent -p /dev/input/event5"')
    if not all(value in capabilities for value in ("Wacom I2C Digitizer", "max 20832", "max 15624")):
        raise RuntimeError("Stylus device does not match the inspected tablet")
    commands = []

    def event(kind, code, value):
        commands.append(f"sendevent /dev/input/event5 {kind} {code} {value}")

    for x1, y1, x2, y2 in SHAPES[shape]:
        event(1, 0x142, 1)
        event(3, 0x19, 0)
        for step in range(31):
            x = x1 + (x2 - x1) * step / 30
            y = y1 + (y2 - y1) * step / 30
            event(3, 0, round(y * 20832 / 2480))
            event(3, 1, round((1860 - x) * 15624 / 1860))
            event(3, 0x18, 1600)
            event(1, 0x14A, 1)
            event(0, 0, 0)
            commands.append("sleep 0.015")
        event(3, 0x18, 0)
        event(1, 0x14A, 0)
        event(0, 0, 0)
        event(1, 0x142, 0)
        event(3, 0x19, 30)
        event(0, 0, 0)
        commands.append("sleep 0.1")
    subprocess.run(boox_ui.ADB + ["shell", "/debug_ramdisk/su -c sh"],
                   input="\n".join(commands) + "\n", text=True, check=True)


if __name__ == "__main__":
    draw(sys.argv[1])
