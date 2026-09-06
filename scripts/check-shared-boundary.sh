#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC="$ROOT/shared/src/commonMain"
[ -d "$SRC" ] || { echo "shared/commonMain is missing" >&2; exit 1; }

forbidden='^[[:space:]]*import[[:space:]]+(android\.|java\.|javax\.|com\.google\.zxing\.|platform\.)'
if grep -R -n -E "$forbidden" "$SRC" --include='*.kt'; then
  echo "shared/commonMain contains a platform-specific import" >&2
  exit 1
fi

echo "shared/commonMain platform boundary: PASS"
