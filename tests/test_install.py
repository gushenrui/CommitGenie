"""Run install.sh against temporary directories and a fake build, never the real IDE."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class InstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="commitgenie-install-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ide = self.root / "Custom IDE.app"
        self.plugins = self.root / "user plugins"
        shutil.copy(Path(__file__).resolve().parents[1] / "install.sh", self.root / "install.sh")
        wrapper = self.root / "gradlew"
        wrapper.write_text('''#!/usr/bin/env python3
import json, pathlib, sys, zipfile
pathlib.Path("build-args.json").write_text(json.dumps(sys.argv[1:]))
pathlib.Path("build/distributions").mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile("build/distributions/test.zip", "w") as archive:
    archive.writestr("CommitGenie/lib/test.jar", "test plugin")
''')
        wrapper.chmod(0o755)
        self.env = dict(os.environ, COMMITGENIE_IDE_PATH=str(self.ide),
                        COMMITGENIE_PLUGIN_DIR=str(self.plugins), WATCHDOG_SECONDS="1")

    def run_install(self, *args):
        return subprocess.run(["bash", "install.sh", *args], cwd=self.root,
                              env=self.env, capture_output=True, text=True, timeout=10)

    def test_missing_user_directory_created_and_custom_ide_forwarded(self):
        result = self.run_install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.plugins / "CommitGenie/lib/test.jar").exists())
        args = json.loads((self.root / "build-args.json").read_text())
        self.assertIn("-PidePath=" + str(self.ide), args)

    def test_existing_user_directory_supported(self):
        self.plugins.mkdir()
        (self.plugins / "unrelated.txt").write_text("keep")
        result = self.run_install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("keep", (self.plugins / "unrelated.txt").read_text())

    def test_invalid_target_rejected_before_build(self):
        self.plugins.write_text("not a directory")
        self.assertNotEqual(0, self.run_install().returncode)
        self.assertFalse((self.root / "build-args.json").exists())

    @unittest.skipIf(os.geteuid() == 0, "root can write despite mode bits")
    def test_unwritable_target_rejected_before_build(self):
        self.plugins.mkdir()
        self.plugins.chmod(0o500)
        try:
            self.assertNotEqual(0, self.run_install().returncode)
            self.assertFalse((self.root / "build-args.json").exists())
        finally:
            self.plugins.chmod(0o700)

    def test_missing_system_directory_is_not_created(self):
        self.assertNotEqual(0, self.run_install("--system").returncode)
        self.assertFalse((self.ide / "Contents/plugins").exists())
        self.assertFalse((self.root / "build-args.json").exists())

    def test_build_only_needs_no_product_info_or_install_directory(self):
        result = self.run_install("--build-only")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(self.plugins.exists())

    def test_help_needs_no_ide(self):
        self.assertEqual(0, self.run_install("--help").returncode)
        self.assertFalse((self.root / "build-args.json").exists())


if __name__ == "__main__":
    unittest.main()
