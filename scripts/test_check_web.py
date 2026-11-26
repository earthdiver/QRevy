"""Exercise browser validation ordering and failure propagation without a build."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class BrowserValidationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="qr-web-check-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        (self.root / "scripts").mkdir()
        (self.root / "webApp").mkdir()
        (self.root / "bin").mkdir()
        shutil.copyfile(Path(__file__).with_name("check-web.sh"), self.root / "scripts/check-web.sh")
        self.log = self.root / "commands.jsonl"
        stub = f"#!{sys.executable}\n" + '''import json, os, pathlib, sys
command = [pathlib.Path(sys.argv[0]).name, *sys.argv[1:]]
with open(os.environ["CHECK_WEB_LOG"], "a") as log:
    log.write(json.dumps({"command": command, "cwd": os.getcwd()}) + "\\n")
sys.exit(9 if command == json.loads(os.environ.get("CHECK_WEB_FAIL", "null")) else 0)
'''
        for name in ("bin/npm", "bin/node", "gradlew"):
            target = self.root / name
            target.write_text(stub)
            target.chmod(0o755)

    def run_checks(self, *args, fail=None):
        env = {**os.environ, "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
               "CHECK_WEB_LOG": str(self.log), "CHECK_WEB_FAIL": json.dumps(fail)}
        result = subprocess.run(["sh", str(self.root / "scripts/check-web.sh"), *args],
                                cwd=self.root, env=env, capture_output=True, text=True)
        records = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertTrue(all(record["cwd"] == str(self.root / "webApp") for record in records))
        return result, [record["command"] for record in records]

    def test_all_browser_stages_run_with_one_gradle_invocation_and_forwarded_options(self):
        result, commands = self.run_checks("--workers=1", "--project=desktop-chromium", "file with spaces.spec.js")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(commands, [
            ["npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund"],
            ["gradlew", "-p", ".", "test", "generateJavaScript", "--console=plain"],
            ["npm", "run", "build"],
            ["npm", "test"],
            ["node", "scripts/check-browser.mjs", "--workers=1", "--project=desktop-chromium", "file with spaces.spec.js"],
        ])

    def test_failed_stage_stops_validation_before_consuming_stale_outputs(self):
        for failure in (["npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund"],
                        ["gradlew", "-p", ".", "test", "generateJavaScript", "--console=plain"],
                        ["npm", "run", "build"], ["npm", "test"],
                        ["node", "scripts/check-browser.mjs"]):
            with self.subTest(failure=failure):
                self.log.unlink(missing_ok=True)
                result, commands = self.run_checks(fail=failure)
                self.assertEqual(result.returncode, 9)
                self.assertEqual(commands[-1], failure)


if __name__ == "__main__":
    unittest.main()
