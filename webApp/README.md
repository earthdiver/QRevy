# QRevy PWA

English | [Japanese](README.ja.md)

QRevy for iPhone, Android, and desktop browsers. Adjust images, edit unreadable cells, and compare recovery candidates. Photos and QR contents are processed on your device. The app works offline after its initial download. Recovery does not guarantee the original content.

See the [User Guide](docs/USER_GUIDE.en.md) for installation, operation, and saving work.

## Build and run

Requires JDK 17, Node.js 22 or later, and a checkout of the complete QRevy repository. The PWA build does not require the Android SDK. Initial dependency downloads require a connection.

From the repository root:

```sh
./bootstrap-wrapper.sh
./scripts/verify-wrapper.sh
cd webApp
npm ci --ignore-scripts
npm run build:engine
npm run build
npm run dev
```

Open [the local app](http://127.0.0.1:4173). To use another device, serve `webApp/dist/` over HTTPS. Apply `dist/_headers` or equivalent response headers on your host. The headers enable cooperative cancellation; without cross-origin isolation, stopping recovery terminates its worker and discards that run's unfinished candidates. Existing edits and earlier results are retained.

## Validation

Install the test browsers once from `webApp/`:

```sh
npx playwright install --with-deps webkit chromium firefox
```

From the repository root, `./scripts/check-web.sh` installs locked npm dependencies, runs JVM tests and engine generation in one Gradle invocation, builds the static app, and runs Node and browser tests. Playwright starts and stops its own server on port 4175, independently of the development server. Set `QREVY_TEST_PORT` to use another port. Browser tests use half the available CPUs (rounded down, minimum 1, maximum 4 workers), with independent browser contexts and storage per test. All five browser projects remain enabled. This default also applies to `npm run test:browser`; set `QREVY_BROWSER_WORKERS=1` for serial execution, or another positive count or percentage to adjust concurrency. An explicit Playwright `--workers` option takes precedence. To select a browser, pass an option such as `--project=desktop-chromium`.

For repository checks, use `python3 -B scripts/check-changes.py --dry-run` followed by `python3 -B scripts/check-changes.py`. Documentation-only changes run whitespace checks. PWA-only changes run browser checks without Android; Android-only changes run Android checks without browsers. Android-only recovery and configuration changes keep all Android unit tests, while UI/session changes omit only the two exhaustive geometry studies. Shared inputs and repository-wide build/check policy changes require full checks on both platforms. Shared inputs include the browser source/test allowlists, the four Android originals of browser variants, and the shared Java decoder directory, even though some live under `androidApp/`. Mixed changes combine their checks. The runner prints elapsed time for each command and the total validation time.

Failure traces retain actions and DOM snapshots without continuous screenshots. Explicit screenshots taken by tests remain available.

On Debian-family Linux, the wrapper can reuse a complete browser success for 12 hours when the built app, tests, tooling, installed browser files, host packages, boot, and execution environment are unchanged. Receipts are local to `build/browser-validation/`; all preceding checks still run. CI, filtered commands, custom browser/font/library overrides, and `check-changes.py --full` always run browser tests. Use `QREVY_BROWSER_CACHE=refresh ./scripts/check-web.sh` to force a run and update the receipt, or `QREVY_BROWSER_CACHE=off` to disable caching. Direct `npm run test:browser` always runs tests. Changes limited to the two browser README files require documentation checks; bundled PWA privacy and license documents require browser checks. The bundled root `NOTICE.md` requires full checks.

To reset the cache after forcibly terminating a run, remove `build/browser-validation/` after all validation processes have stopped.

## Source layout

The independent Gradle build uses the repository's verified wrapper. `shared-sources.txt` lists the existing `shared/` primitives and Android recovery, presentation, and session sources compiled directly. `shared-tests.txt` lists existing tests also compiled in this build. Browser-specific code and tests live under `src/`; `tests/` exercises generated JavaScript and browser behavior.

Four browser source variants in `src/main/kotlin/overrides/` adapt charset lookup and memory budgets. The build checks that they match the repository sources except for those explicit substitutions. Update both variants when changing the corresponding engine source. Android bitmap access and browser interop remain outside `shared/commonMain`.

The required ZXing source subset and its browser charset adaptations are retained under `vendor/zxing/` and `src/main/java/`. Charset tables and `web/native-strings.js` have generators in `scripts/`. After changing reused Android UI text, run `python3 -B scripts/extract-ui-strings.py` from `webApp/` and review the generated labels. Generated engine output, dependency directories, and test reports are ignored by Git.

## License and policies

Apache License 2.0. See the repository [license](../LICENSE), [notices](NOTICE.md), and [third-party notices](THIRD_PARTY_NOTICES.md).

[Privacy](PRIVACY.md) · [Security](SECURITY.md)
