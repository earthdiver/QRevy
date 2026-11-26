#!/usr/bin/env sh
# Validate the browser app from repository sources; Playwright owns its server.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT/webApp"
npm ci --ignore-scripts --no-audit --no-fund
../gradlew -p . test generateJavaScript --console=plain
npm run build
npm test
node scripts/check-browser.mjs "$@"
