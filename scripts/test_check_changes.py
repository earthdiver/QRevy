"""Selection and Git integration checks for local change-based validation."""

import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("check-changes.py")
SPEC = importlib.util.spec_from_file_location("check_changes", SCRIPT)
checks = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checks)
APP = "androidApp/src/main/java/io/github/earthdiver/qrrepair/"
TEST = "androidApp/src/test/java/io/github/earthdiver/qrrepair/"
CORE = APP + "core/RecoveryEngine.kt"
SHARED = "shared/src/commonMain/kotlin/io/github/earthdiver/qrrepair/core/QrTypes.kt"
SHARED_TEST = "shared/src/commonTest/kotlin/io/github/earthdiver/qrrepair/core/SharedCoreSmokeTest.kt"


class CheckSelectionTest(unittest.TestCase):
    def plan(self, paths, full=False, platform="linux", quick=False):
        return checks.commands_for(paths, "base-commit", full, platform, quick=quick)

    def test_documentation_does_not_start_a_build(self):
        scope, commands = self.plan(["README.md", "README.ja.md", "docs/USER_GUIDE.en.md"])
        self.assertEqual(scope, "docs")
        self.assertTrue(commands)
        self.assertTrue(all(command[:3] == ["git", "diff", "--check"] or
                            command[:4] == ["git", "diff", "--cached", "--check"] for command in commands))

    def test_ui_sessions_and_localized_resources_keep_android_checks(self):
        paths = [APP + "AppStyle.kt", APP + "MainActivity.kt", APP + "ui/QrEditorView.kt",
                 APP + "session/RecoverySessionCodec.kt", TEST + "session/RecoverySessionStorageTest.kt",
                 "androidApp/src/main/res/values-ja/strings.xml", "androidApp/src/main/res/raw/privacy.txt"]
        for path in paths:
            with self.subTest(path=path):
                scope, commands = self.plan([path])
                self.assertEqual(scope, "app")
                gradle = commands[-1]
                for required in (":androidApp:testDebugUnitTest", "-Pqr.testScope=app",
                                 ":androidApp:lint", ":androidApp:assembleDebug"):
                    self.assertIn(required, gradle)
                self.assertNotIn(":shared:jvmTest", gradle)

    def test_shared_test_edits_do_not_require_android_but_combine_with_app_edits(self):
        scope, commands = self.plan([SHARED_TEST])
        self.assertEqual(scope, "shared-tests")
        self.assertEqual(commands[-1], ["./gradlew", ":shared:jvmTest"])
        scope, commands = self.plan([SHARED_TEST, APP + "AppStyle.kt", "README.md"])
        self.assertEqual(scope, "app+shared-tests")
        self.assertIn(":shared:jvmTest", commands[-1])
        self.assertIn("-Pqr.testScope=app", commands[-1])

    def test_recovery_fixtures_build_privacy_configuration_and_unknown_paths_require_full_checks(self):
        paths = [CORE, SHARED, TEST + "core/GeometryRecoveryCache.kt",
                 TEST + "core/GeometryNeighborhoodExpandedRegressionTest.kt",
                 "androidApp/src/test/resources/new-fixture.txt", "androidApp/src/main/AndroidManifest.xml",
                 "androidApp/src/main/res/xml/backup_rules.xml", "androidApp/build.gradle.kts",
                 "gradle/libs.versions.toml", "scripts/check-changes.py", "AGENTS.md",
                 ".github/workflows/android.yml", "docs/examples/new-fixture.png",
                 APP + "newArea/Unknown.kt", "unknown-file", "shared/src/iosMain/New.kt"]
        for path in paths:
            with self.subTest(path=path):
                scope, commands = self.plan(["README.md", APP + "AppStyle.kt", path])
                self.assertEqual(scope, "full")
                self.assertIn(":shared:jvmTest", commands[-1])
                self.assertIn("-Pqr.testScope=full", commands[-1])
                self.assertNotIn("-Pqr.testScope=app", commands[-1])
                self.assertTrue(any("unittest" in command for command in commands))

    def test_clean_tree_requires_an_explicit_full_request_and_macos_checks_the_framework(self):
        self.assertEqual(self.plan([]), ("none", []))
        scope, commands = self.plan([], full=True, platform="darwin")
        self.assertEqual(scope, "full")
        self.assertIn(":shared:assembleXCFramework", commands[-1])
        self.assertIn("-Pqr.testScope=full", commands[-1])
        self.assertNotIn(":shared:assembleXCFramework", self.plan([], full=True)[1][-1])

    def test_quick_feedback_keeps_all_full_checks_except_the_android_geometry_scope(self):
        for paths in ([], ["README.md"], [APP + "AppStyle.kt"], [SHARED_TEST], [CORE], ["unknown-file"]):
            for platform in ("linux", "darwin"):
                with self.subTest(paths=paths, platform=platform):
                    scope, commands = self.plan(paths, platform=platform, quick=True)
                    self.assertEqual(scope, "quick")
                    self.assertIn("-Pqr.testScope=quick", commands[-1])
                    self.assertEqual(
                        [[arg.replace("-Pqr.testScope=quick", "-Pqr.testScope=full") for arg in command]
                         for command in commands],
                        self.plan(paths, full=True, platform=platform)[1],
                    )

    def test_full_validation_cannot_be_combined_with_quick_feedback(self):
        with self.assertRaises(ValueError):
            self.plan([CORE], full=True, quick=True)


class GitChangeSelectionTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="qr-change-check-test-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.git("init", "-q", "-b", "main")
        # These disposable fixture commits are synthetic test data, not maintainer commits.
        self.git("config", "user.name", "QRevy test")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", "/dev/null")
        self.git("config", "core.autocrlf", "false")
        self.write("README.md", "Documentation\n")
        self.write(CORE, "original core\n")
        self.write(APP + "AppStyle.kt", "original style\n")
        self.write(".gitignore", "ignored.txt\n")
        self.commit()
        self.initial = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.PIPE, text=True)

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def commit(self):
        self.git("add", "--all")
        self.git("commit", "-q", "-m", "Synthetic change-check fixture")

    def changes(self, base=None):
        return checks.changed_paths(self.root, base)

    def run_main(self, *args):
        output = io.StringIO()
        real_run = subprocess.run

        def capture_process_output(*command, **kwargs):
            kwargs.setdefault("stdout", subprocess.PIPE)
            kwargs.setdefault("stderr", subprocess.PIPE)
            return real_run(*command, **kwargs)

        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output), \
                patch.object(checks.subprocess, "run", side_effect=capture_process_output):
            result = checks.main(list(args), root=self.root)
        return result, output.getvalue()

    def test_collects_staged_unstaged_and_untracked_paths_without_ignored_files(self):
        self.write(APP + "AppStyle.kt", "staged style\n")
        self.git("add", "--", APP + "AppStyle.kt")
        self.write("README.md", "Working documentation\n")
        unusual = "docs/new file\nwith a tab\t.ja.md"
        self.write(unusual, "Synthetic documentation\n")
        self.write("ignored.txt", "ignored\n")
        base, paths = self.changes()
        self.assertEqual(base, self.initial)
        self.assertEqual(set(paths), {APP + "AppStyle.kt", "README.md", unusual})

    def test_staged_core_change_is_preserved_when_working_copy_restores_original_content(self):
        self.write(CORE, "staged core change\n")
        self.git("add", "--", CORE)
        self.write(CORE, "original core\n")
        self.assertEqual(self.changes()[1], [CORE])
        self.assertEqual(checks.commands_for(self.changes()[1], self.initial, False, "linux")[0], "full")

    def test_rename_to_documentation_keeps_the_deleted_core_path(self):
        self.git("mv", "--", CORE, "recovery-notes.md")
        self.assertEqual(set(self.changes()[1]), {CORE, "recovery-notes.md"})
        self.assertEqual(checks.commands_for(self.changes()[1], self.initial, False, "linux")[0], "full")

    def test_branch_base_uses_the_merge_base_and_includes_local_edits(self):
        self.git("checkout", "-q", "-b", "feature")
        self.write(CORE, "feature core\n")
        self.commit()
        self.git("checkout", "-q", "main")
        self.write("README.md", "unrelated main documentation\n")
        self.commit()
        self.git("checkout", "-q", "feature")
        self.write(APP + "AppStyle.kt", "local style\n")
        base, paths = self.changes("main")
        self.assertEqual(base, self.initial)
        self.assertEqual(set(paths), {CORE, APP + "AppStyle.kt"})

    def test_committed_changes_require_a_base_or_full_request(self):
        self.write(CORE, "committed core\n")
        self.commit()
        self.assertEqual(self.changes()[1], [])
        self.assertEqual(self.changes("HEAD~1")[1], [CORE])
        result, output = self.run_main("--dry-run")
        self.assertEqual(result, 0)
        self.assertIn("No changes found", output)

    def test_dry_run_prints_the_full_plan_without_executing_missing_build_scripts(self):
        self.write(CORE, "changed core\n")
        result, output = self.run_main("--dry-run")
        self.assertEqual(result, 0)
        self.assertIn("Selected scope: full", output)
        self.assertIn("-Pqr.testScope=full", output)
        self.assertIn("Plan only; no checks executed.", output)
        self.assertFalse((self.root / "build").exists())

    def test_quick_dry_run_distinguishes_feedback_from_required_full_validation(self):
        self.write(CORE, "changed core\n")
        self.commit()
        result, output = self.run_main("--base", "HEAD~1", "--quick", "--dry-run")
        self.assertEqual(result, 0)
        self.assertIn("[full] " + CORE, output)
        self.assertIn("Selected scope: quick", output)
        self.assertIn("-Pqr.testScope=quick", output)
        self.assertIn("Run without --quick, or with --full, for final validation.", output)
        self.assertIn("Plan only; no checks executed.", output)
        self.assertFalse((self.root / "build").exists())

    def test_quick_feedback_runs_even_on_a_clean_tree(self):
        result, output = self.run_main("--quick", "--dry-run")
        self.assertEqual(result, 0)
        self.assertIn("Selected scope: quick", output)
        self.assertIn(":shared:jvmTest", output)
        self.assertIn("-Pqr.testScope=quick", output)
        self.assertNotIn("No changes found", output)

    def test_cli_rejects_quick_and_full_together(self):
        with self.assertRaises(SystemExit) as error:
            self.run_main("--quick", "--full", "--dry-run")
        self.assertEqual(error.exception.code, 2)

    def test_successful_quick_feedback_still_requires_final_validation(self):
        for name in ("bootstrap-wrapper.sh", "scripts/verify-wrapper.sh",
                     "scripts/check-shared-boundary.sh", "gradlew"):
            self.write(name, "#!/bin/sh\nexit 0\n")
            (self.root / name).chmod(0o755)
        self.write("scripts/test_fixture.py", "import unittest\nfrom pathlib import Path\n\n"
                   "class WorkspaceTest(unittest.TestCase):\n"
                   "    def test_uses_repository_as_working_directory(self):\n"
                   "        self.assertTrue(Path('gradlew').is_file())\n")
        self.commit()
        result, output = self.run_main("--quick")
        self.assertEqual(result, 0, output)
        self.assertIn("Quick feedback passed. Final validation is still required.", output)
        self.assertNotIn("Selected checks passed", output)

    def test_failed_whitespace_check_stops_before_bootstrapping_or_running_tests(self):
        self.write(APP + "AppStyle.kt", "trailing whitespace  \n")
        result, output = self.run_main()
        self.assertNotEqual(result, 0)
        self.assertNotIn("$ ./bootstrap-wrapper.sh", output)
        self.assertNotIn("Selected checks passed", output)

    def test_documentation_validation_succeeds_without_android_or_gradle(self):
        self.write("README.md", "Updated documentation\n")
        result, output = self.run_main()
        self.assertEqual(result, 0)
        self.assertIn("Selected scope: docs", output)
        self.assertIn("Selected checks passed", output)
        self.assertNotIn("$ ./gradlew", output)

    def test_failed_bootstrap_stops_before_verification_and_gradle(self):
        self.write("bootstrap-wrapper.sh", "#!/bin/sh\nexit 7\n")
        (self.root / "bootstrap-wrapper.sh").chmod(0o755)
        self.commit()
        self.write(APP + "AppStyle.kt", "updated style\n")
        result, output = self.run_main()
        self.assertNotEqual(result, 0)
        self.assertIn("$ ./bootstrap-wrapper.sh", output)
        self.assertNotIn("$ ./scripts/verify-wrapper.sh", output)
        self.assertNotIn("$ ./gradlew", output)

    def test_deleted_files_remain_in_the_plan(self):
        (self.root / CORE).unlink()
        self.assertEqual(self.changes()[1], [CORE])

    def test_invalid_base_cannot_succeed_with_an_empty_selection(self):
        result, output = self.run_main("--base", "refs/heads/missing", "--dry-run")
        self.assertNotEqual(result, 0)
        self.assertNotIn("Selected checks passed", output)
        self.assertNotIn("No changes found", output)

    def test_merge_conflicts_stop_selection(self):
        self.git("checkout", "-q", "-b", "feature")
        self.write(CORE, "feature core\n")
        self.commit()
        self.git("checkout", "-q", "main")
        self.write(CORE, "main core\n")
        self.commit()
        with self.assertRaises(subprocess.CalledProcessError):
            self.git("merge", "feature")
        result, output = self.run_main("--dry-run")
        self.assertNotEqual(result, 0)
        self.assertIn("Resolve merge conflicts", output)


if __name__ == "__main__":
    unittest.main()
