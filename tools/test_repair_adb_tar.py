import io
import tarfile
import unittest
from repair_adb_tar import NOTICE, recover


class RepairTests(unittest.TestCase):
    def archive(self):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            for name, data in [("first", NOTICE + b"belongs inside the file"), ("second", b"second")]:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        return stream.getvalue()

    def test_boundary_notices_removed_and_file_content_preserved(self):
        good = self.archive()
        bad = NOTICE + good[:1024] + NOTICE + good[1024:]
        fixed, report = recover(bad)
        self.assertEqual(good, fixed)
        self.assertEqual(2, len(report["removed_notice_offsets"]))
        self.assertEqual(2, report["members"])

    def test_unknown_pollution_and_truncation_rejected(self):
        good = self.archive()
        with self.assertRaises((ValueError, tarfile.HeaderError)):
            recover(good[:1024] + b"other warning" + good[1024:])
        with self.assertRaises(ValueError):
            recover(good[:1200])


if __name__ == "__main__":
    unittest.main()
