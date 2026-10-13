"""Reject partial wrapper updates before bootstrap can overwrite them."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class BootstrapWrapperTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copy2(ROOT / "bootstrap-wrapper.sh", self.root)
        wrapper = self.root / "gradle/wrapper"
        wrapper.mkdir(parents=True)
        self.properties = wrapper / "gradle-wrapper.properties"
        shutil.copy2(ROOT / "gradle/wrapper/gradle-wrapper.properties", self.properties)
        shutil.copy2(ROOT / "gradle/wrapper/gradle-wrapper.jar", wrapper)
        (self.root / "gradlew").touch()
        script = (self.root / "bootstrap-wrapper.sh").read_text()
        self.version = re.search(r'^GRADLE_VERSION="([^"]+)"', script, re.MULTILINE)[1]
        self.cache = self.root / "cache"
        gradle = self.cache / f"qr-repair-gradle-{self.version}/gradle-{self.version}/bin/gradle"
        gradle.parent.mkdir(parents=True)
        # Stand in for the already verified distribution, without accessing the network.
        gradle.write_text('#!/bin/sh\nset -eu\ntouch bootstrap-invoked\n')
        gradle.chmod(0o755)

    def bootstrap(self):
        return subprocess.run(
            ["sh", str(self.root / "bootstrap-wrapper.sh")],
            cwd=self.root,
            env={**os.environ, "TMPDIR": str(self.cache)},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

    def assert_rejected_without_bootstrapping(self):
        before = self.properties.read_bytes()
        result = self.bootstrap()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("distribution pins disagree", result.stdout)
        self.assertFalse((self.root / "bootstrap-invoked").exists())
        self.assertEqual(before, self.properties.read_bytes())

    def test_matching_distribution_pins_allow_bootstrap(self):
        result = self.bootstrap()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertTrue((self.root / "bootstrap-invoked").exists())

    def test_partial_version_update_is_rejected_without_overwriting_it(self):
        self.properties.write_text(self.properties.read_text().replace(
            f"gradle-{self.version}-bin.zip", "gradle-0.0.0-bin.zip"))
        self.assert_rejected_without_bootstrapping()

    def test_partial_checksum_update_is_rejected_without_overwriting_it(self):
        self.properties.write_text(re.sub(
            r"(?m)^distributionSha256Sum=.*$", "distributionSha256Sum=" + "0" * 64,
            self.properties.read_text()))
        self.assert_rejected_without_bootstrapping()


if __name__ == "__main__":
    unittest.main()
