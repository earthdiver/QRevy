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

From the repository root, `./scripts/check-web.sh` installs locked npm dependencies, runs JVM tests, generates the engine, builds the static app, and runs Node and browser tests. Playwright starts and stops its own server on port 4175, independently of the development server. Set `QREVY_TEST_PORT` to use another port and `QREVY_BROWSER_WORKERS` to adjust browser-test concurrency (default: 1). To select a browser, pass a Playwright option such as `--project=desktop-chromium`.

For all repository checks, use `python3 -B scripts/check-changes.py --dry-run` followed by `python3 -B scripts/check-changes.py`. Full checks include the PWA.

## Source layout

The independent Gradle build uses the repository's verified wrapper. `shared-sources.txt` lists the existing `shared/` primitives and Android recovery, presentation, and session sources compiled directly. `shared-tests.txt` lists existing tests also compiled in this build. Browser-specific code and tests live under `src/`; `tests/` exercises generated JavaScript and browser behavior.

Four browser source variants in `src/main/kotlin/overrides/` adapt charset lookup and memory budgets. The build checks that they match the repository sources except for those explicit substitutions. Update both variants when changing the corresponding engine source. Android bitmap access and browser interop remain outside `shared/commonMain`.

The required ZXing source subset and its browser charset adaptations are retained under `vendor/zxing/` and `src/main/java/`. Charset tables and `web/native-strings.js` have generators in `scripts/`. After changing reused Android UI text, run `python3 -B scripts/extract-ui-strings.py` from `webApp/` and review the generated labels. Generated engine output, dependency directories, and test reports are ignored by Git.

## License and policies

Apache License 2.0. See the repository [license](../LICENSE), [notices](NOTICE.md), and [third-party notices](THIRD_PARTY_NOTICES.md).

[Privacy](PRIVACY.md) · [Security](SECURITY.md)
