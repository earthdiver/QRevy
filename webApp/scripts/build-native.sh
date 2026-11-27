#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Activate Emscripten 4.0.20 before invoking this optional rebuild command.
emcc --version | head -n 1 | grep -F '4.0.20' >/dev/null
revision=287c85df6f961c8efbfb5ffd736cd9457b8b890e
source_dir="$PWD/../build/zxing-cpp-source"
if [[ ! -d "$source_dir/.git" ]]; then
  git init "$source_dir"
  git -C "$source_dir" remote add origin https://github.com/zxing-cpp/zxing-cpp.git
  git -C "$source_dir" fetch --depth 1 origin "$revision"
fi
git -C "$source_dir" checkout --detach "$revision"
test "$(git -C "$source_dir" rev-parse HEAD)" = "$revision"
test -z "$(git -C "$source_dir" status --porcelain)"
emcmake cmake -S native -B ../build/zxing-wasm -DCMAKE_BUILD_TYPE=Release -DZXING_SOURCE_DIR="$source_dir"
cmake --build ../build/zxing-wasm --parallel 2
mkdir -p web/native
cp ../build/zxing-wasm/boundary.js ../build/zxing-wasm/boundary.wasm web/native/
cp "$source_dir/LICENSE" web/native/ZXING_LICENSE
