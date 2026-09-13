import runpy
import sys
from pathlib import Path
import libusb_package
import usb.backend.libusb1
usb.backend.libusb1.get_backend(find_library=lambda _: libusb_package.get_library_path())
edl_dir = Path(__file__).resolve().parent / "edl"
sys.path.insert(0, str(edl_dir))
runpy.run_path(str(edl_dir / "edl.py"), run_name="__main__")
