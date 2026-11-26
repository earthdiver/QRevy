#!/usr/bin/env sh
# Validate the browser app from repository sources; Playwright owns its server.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT/webApp"
npm ci --ignore-scripts --no-audit --no-fund
npm run test:jvm
npm run build:engine
npm run build
npm test
npm run test:browser -- --workers="${QREVY_BROWSER_WORKERS:-1}" "$@"
