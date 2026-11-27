#!/usr/bin/env python3
"""Select local validation from Git changes; unfamiliar paths require full checks."""

import argparse
import os
from pathlib import Path, PurePosixPath
import shlex
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
APP_PACKAGE = "androidApp/src/main/java/io/github/earthdiver/qrrepair/"
APP_TEST_PACKAGE = "androidApp/src/test/java/io/github/earthdiver/qrrepair/"
SHARED_TESTS = "shared/src/commonTest/"
# Browser compilation uses these repository sources and tests directly.
WEB_SHARED_PATHS = frozenset(
    line.strip()
    for name in ("shared-sources.txt", "shared-tests.txt")
    for line in (ROOT / "webApp" / name).read_text().splitlines()
    if line.strip() and not line.startswith("#")
)
# These originals are checked against browser adaptations before compilation.
WEB_OVERRIDE_ORIGINALS = frozenset(APP_PACKAGE + "core/" + name for name in (
    "PartialPayloadAnalyzer.kt", "QrPayloadTextDecoder.kt",
    "StrictQrPayloadValidator.kt", "RecoveryMemoryPolicy.kt",
))
WEB_ONLY_FILES = frozenset("webApp/" + name for name in (
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "package.json", "package-lock.json", "playwright.config.js",
    "PRIVACY.md", "PRIVACY.ja.md", "SECURITY.md", "NOTICE.md", "THIRD_PARTY_NOTICES.md",
))
ANDROID_ONLY_FILES = frozenset((
    "androidApp/build.gradle.kts", "androidApp/proguard-rules.pro",
    "androidApp/src/main/AndroidManifest.xml",
))
SCOPE_REASONS = {
    "docs": "documentation; review content, links, and paired translations",
    "app": "Android UI/session coverage, lint, and debug build",
    "android": "all Android unit tests, lint, and debug build; no browser checks",
    "web": "browser JVM tests, engine/static build, Node tests, and all browser projects; no Android checks",
    "shared-tests": "shared JVM tests",
    "full": "recovery, build/check policy, or an unclassified path",
    "quick": "development checks except the expanded geometry study; intermediate feedback only",
}


def scope_for(path: str) -> str:
    # Check policy files before the documentation rule. New source areas and
    # test fixtures default to full validation until their dependencies are reviewed.
    if path in {"AGENTS.md", "NOTICE.md"} or path in WEB_SHARED_PATHS or path in WEB_OVERRIDE_ORIGINALS:
        return "full"
    if path.startswith(("androidApp/src/main/java/com/google/zxing/qrcode/decoder/",
                        "androidApp/src/main/java/com/google/zxing/qrcode/detector/")):
        return "full"
    parts = PurePosixPath(path).parts
    if path in {"webApp/README.md", "webApp/README.ja.md"} or (
        path.endswith(".md") and (len(parts) == 1 or parts[0] in {"docs", "iosApp", "templates"})
    ):
        return "docs"
    if path in WEB_ONLY_FILES or path.startswith((
        "webApp/web/", "webApp/src/main/", "webApp/src/test/",
        "webApp/tests/", "webApp/scripts/", "webApp/vendor/zxing/",
    )):
        return "web"
    if path in ANDROID_ONLY_FILES or path.startswith("androidApp/src/test/resources/"):
        return "android"
    if path.startswith(SHARED_TESTS):
        return "shared-tests"
    for prefix in (APP_PACKAGE, APP_TEST_PACKAGE):
        if path.startswith(prefix) and path.endswith(".kt"):
            relative = PurePosixPath(path[len(prefix):])
            if relative.parent.as_posix() in {".", "ui", "session"}:
                return "app"
            if relative.parent.as_posix() == "core":
                return "android"
    if path.startswith("androidApp/src/main/res/"):
        resource = parts[4].split("-", 1)[0] if len(parts) > 5 else ""
        if resource in {"values", "layout", "drawable", "mipmap", "raw"}:
            return "app"
        if resource == "xml":
            return "android"
    return "full"


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=root)


def changed_paths(root: Path, reference: str | None) -> tuple[str, list[str]]:
    head = git(root, "rev-parse", "--verify", "HEAD").decode().strip()
    base = head
    if reference is not None:
        commit = git(root, "rev-parse", "--verify", "--end-of-options", reference + "^{commit}").decode().strip()
        base = git(root, "merge-base", head, commit).decode().strip()
    if git(root, "diff", "--name-only", "--diff-filter=U", "-z", "--"):
        raise ValueError("Resolve merge conflicts before selecting checks.")
    # Keep each layer, including staged changes later undone in the working tree.
    # --no-renames keeps both paths of a move, so moving core code cannot hide it.
    queries = (
        ("diff", "--name-only", "--no-renames", "-z", base, head, "--"),
        ("diff", "--cached", "--name-only", "--no-renames", "-z", "--"),
        ("diff", "--name-only", "--no-renames", "-z", "--"),
        ("ls-files", "--others", "--exclude-standard", "-z"),
    )
    paths = set()
    for query in queries:
        paths.update(p.decode("utf-8", "surrogateescape") for p in git(root, *query).split(b"\0") if p)
    return base, sorted(paths)


def commands_for(paths: list[str], base: str, full: bool, platform: str, *, quick: bool = False) -> tuple[str, list[list[str]]]:
    if full and quick:
        raise ValueError("Choose either full validation or quick feedback.")
    scopes = {scope_for(path) for path in paths}
    if full or quick or "full" in scopes:
        scopes = {"full"}
    if not scopes:
        return "none", []
    commands = [
        ["git", "diff", "--check", base, "HEAD", "--"],
        ["git", "diff", "--cached", "--check", "--"],
        ["git", "diff", "--check", "--"],
    ]
    if scopes == {"docs"}:
        return "docs", commands

    is_full = "full" in scopes
    shared = is_full or "shared-tests" in scopes
    android = is_full or bool(scopes & {"app", "android"})
    commands += [["./bootstrap-wrapper.sh"], ["./scripts/verify-wrapper.sh"],
                 ["./scripts/check-shared-boundary.sh"]]
    if is_full:
        commands.append(["python3", "-B", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"])
    gradle = ["./gradlew"]
    if shared:
        gradle.append(":shared:jvmTest")
    if android:
        test_scope = "quick" if quick else "full" if is_full or "android" in scopes else "app"
        gradle += [":androidApp:testDebugUnitTest", "-Pqr.testScope=" + test_scope,
                   ":androidApp:lint", ":androidApp:assembleDebug"]
    if is_full and platform == "darwin":
        gradle.append(":shared:assembleXCFramework")
    if is_full or "web" in scopes:
        commands.append(["./scripts/check-web.sh"])
    if len(gradle) > 1:
        commands.append(gradle)
    if "android" in scopes:
        scopes.discard("app")
    return "quick" if quick else "full" if is_full else "+".join(sorted(scopes - {"docs"})), commands


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", metavar="REF", help="include commits since the merge base with REF, plus local edits")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--full", action="store_true", help="run the full development checks even with no changes")
    mode.add_argument("--quick", action="store_true", help="run development checks except the expanded geometry study, even with no changes; not final validation")
    parser.add_argument("--dry-run", action="store_true", help="print the selected checks without running them")
    args = parser.parse_args(argv)
    try:
        base, paths = changed_paths(root, args.base)
        scope, commands = commands_for(paths, base, args.full, sys.platform, quick=args.quick)
        print("Change base: " + base[:12], flush=True)
        for path in paths:
            print(f"  [{scope_for(path)}] {shlex.quote(path)}", flush=True)
        print("Selected scope: " + scope, flush=True)
        forced = {"full"} if args.full else {"quick"} if args.quick else set()
        for selected in sorted({scope_for(path) for path in paths} | forced):
            print(f"  {selected}: {SCOPE_REASONS[selected]}", flush=True)
        if scope == "none":
            print("No changes found. Use --base REF for committed changes, or --full.")
            return 0
        if "app" in scope:
            print("Android checks omit only the two exhaustive geometry studies; all other Android tests remain enabled.", flush=True)
        if args.quick:
            print("Quick feedback retains the displacement sweep and all 48 default-width cases; only the expanded geometry study is omitted.", flush=True)
            print("Run without --quick, or with --full, for final validation.", flush=True)
        started = time.monotonic()
        for command in commands:
            print("$ " + shlex.join(command), flush=True)
            if not args.dry_run:
                command_started = time.monotonic()
                try:
                    environment = dict(os.environ)
                    if args.full and command == ["./scripts/check-web.sh"] and environment.get("QREVY_BROWSER_CACHE") != "off":
                        environment["QREVY_BROWSER_CACHE"] = "refresh"
                        print("Explicit full validation: rerun all browser tests.", flush=True)
                    subprocess.run(command, cwd=root, check=True, env=environment)
                finally:
                    print(f"Elapsed: {time.monotonic() - command_started:.1f}s ({command[0]})", flush=True)
        if args.dry_run:
            print("Plan only; no checks executed.", flush=True)
        elif args.quick:
            print("Quick feedback passed. Final validation is still required.", flush=True)
        else:
            print("Selected checks passed.", flush=True)
        if not args.dry_run:
            print(f"Total validation time: {time.monotonic() - started:.1f}s", flush=True)
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Change checks failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
