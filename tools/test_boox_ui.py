"""Offline checks for explicit UI-helper device selection."""
import os
import unittest
from unittest import mock

import boox_ui


class UiSelectionTests(unittest.TestCase):
    def test_missing_or_invalid_serial_fails_before_subprocess(self):
        for value in ("", " ", "-bad", "two devices", "bad\nserial"):
            with mock.patch.dict(os.environ, {"BOOX_SERIAL": value}), \
                    mock.patch.object(boox_ui.subprocess, "check_output") as process:
                with self.assertRaisesRegex(RuntimeError, "Set BOOX_SERIAL"):
                    boox_ui.shell("true")
                process.assert_not_called()

    def test_selection_comes_from_current_environment(self):
        for value in ("SYNTHETIC_SERIAL", "192.0.2.1:5555"):
            with mock.patch.dict(os.environ, {"BOOX_SERIAL": value}), \
                    mock.patch.object(boox_ui.subprocess, "check_output", return_value="ok") as process:
                self.assertEqual(boox_ui.shell("true"), "ok")
                self.assertEqual(process.call_args.args[0][-4:], ["-s", value, "shell", "true"])


if __name__ == "__main__":
    unittest.main()
