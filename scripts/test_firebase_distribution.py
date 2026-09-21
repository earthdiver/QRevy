"""Behavior checks for the Python guards executed by Firebase distribution CI."""
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/firebase-distribution.yml'
BLOCKS = [compile('\n'.join(line[10:] for line in block.splitlines()), str(WORKFLOW), 'exec')
          for block in re.findall(r"          python3 - <<'PYTHON'\n(.*?)          PYTHON", WORKFLOW.read_text(), re.S)]


class DistributionNotesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.notes = self.root / 'RELEASE_NOTE.txt'
        self.payload = b'synthetic APK download'
        self.env = dict(GITHUB_WORKSPACE=str(self.root), RUNNER_TEMP=str(self.root),
                        GITHUB_STEP_SUMMARY=str(self.root / 'summary'),
                        GITHUB_REPOSITORY='example/private', RELEASE_TAG='v-test',
                        APK_ASSET='test.apk', FIREBASE_APP_ID='1:123:android:abc',
                        FIREBASE_GROUPS='testers', APK_SIGNER_SHA256='ab' * 32,
                        WIF_PROVIDER='provider', SERVICE_ACCOUNT='account', RELEASE_NOTES='')

    def run_guard(self):
        release = dict(draft=False, assets=[dict(id=1, name='test.apk', state='uploaded',
                       digest='sha256:' + hashlib.sha256(self.payload).hexdigest())])
        def download(args, stdout, check):
            stdout.write(self.payload)
        with patch.dict(os.environ, self.env), patch('subprocess.check_output', return_value=json.dumps(release).encode()), patch('subprocess.run', side_effect=download):
            exec(BLOCKS[0], {})
        return (self.root / 'firebase-distribution/notes.txt').read_bytes()

    def test_root_file_wins_and_preserves_multiline_text(self):
        content = b'Title\n\nFirst line\nSecond line\n'
        self.notes.write_bytes(content)
        self.env['RELEASE_NOTES'] = 'ignored input'
        self.assertEqual(self.run_guard(), content)

    def test_windows_newlines_and_optional_utf8_bom(self):
        content = 'Title\r\n\r\nDetails\r\n'.encode()
        self.notes.write_bytes(b'\xef\xbb\xbf' + content)
        self.assertEqual(self.run_guard(), content)

    def test_missing_file_uses_multiline_input(self):
        self.env['RELEASE_NOTES'] = 'Title\n\nDetails\n'
        self.assertEqual(self.run_guard(), self.env['RELEASE_NOTES'].encode())

    def test_missing_file_and_blank_input_fail(self):
        with self.assertRaises(SystemExit):
            self.run_guard()

    def test_empty_file_does_not_silently_use_input(self):
        self.notes.write_text(' \n')
        self.env['RELEASE_NOTES'] = 'fallback'
        with self.assertRaises(SystemExit):
            self.run_guard()

    def test_invalid_utf8_fails(self):
        self.notes.write_bytes(b'\xff')
        with self.assertRaises(UnicodeDecodeError):
            self.run_guard()

    def test_directory_fails(self):
        self.notes.mkdir()
        with self.assertRaises(SystemExit):
            self.run_guard()

    def test_symlink_fails(self):
        self.notes.symlink_to(self.root / 'missing')
        with self.assertRaises(SystemExit):
            self.run_guard()


class ApkSignerTest(unittest.TestCase):
    def check(self, certificates, count=1, package='io.github.earthdiver.qrrepair', debug=False):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'firebase-distribution'
            directory.mkdir()
            (directory / 'signature.txt').write_text(f'Number of signers: {count}\n' + certificates)
            (directory / 'signer.txt').write_text('ab' * 32)
            (directory / 'badging.txt').write_text(f"package: name='{package}' versionCode='46'\n" + ('application-debuggable\n' if debug else ''))
            with patch.dict(os.environ, RUNNER_TEMP=temp):
                exec(BLOCKS[1], {})

    def test_legacy_and_sdk37_certificate_labels(self):
        for label in ['Signer #1', 'V3.0 Signer:', 'V3.1 Signer:']:
            with self.subTest(label=label):
                self.check(f'{label} certificate SHA-256 digest: ' + 'ab' * 32 + '\n')

    def test_repeated_same_certificate_across_schemes(self):
        self.check(''.join(f'V{scheme} Signer: certificate SHA-256 digest: ' + 'ab' * 32 + '\n' for scheme in ['2.0', '3.0']))

    def test_wrong_or_missing_certificate_and_multiple_signers_fail(self):
        for text, count in [('', 1), ('V3.0 Signer: certificate SHA-256 digest: ' + 'cd' * 32 + '\n', 1), ('Signer #1 certificate SHA-256 digest: ' + 'ab' * 32 + '\n', 2)]:
            with self.subTest(text=text, count=count), self.assertRaises(SystemExit):
                self.check(text, count)

    def test_other_package_and_debuggable_build_fail(self):
        text = 'V3.0 Signer: certificate SHA-256 digest: ' + 'ab' * 32 + '\n'
        for options in [dict(package='other.app'), dict(debug=True)]:
            with self.subTest(options=options), self.assertRaises(SystemExit):
                self.check(text, **options)


if __name__ == '__main__':
    unittest.main()
