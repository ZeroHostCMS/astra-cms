#!/usr/bin/env bash
# Builds the Express demo's tenant pricing scripts to WebAssembly and copies them to wasm/modules,
# where scripts/demo-seed.sh picks them up. The .wasm files are committed, so this is only needed
# after changing the Rust sources.
set -euo pipefail
cd "$(dirname "$0")/../astra-apps/express/wasm"
TRIPLE=wasm32-unknown-unknown
rustup target add "$TRIPLE" >/dev/null
cargo build --release --target "$TRIPLE"
mkdir -p modules
for m in tet-promo runaway; do
  cp "target/${TRIPLE}/release/${m//-/_}.wasm" "modules/${m}.wasm"
  printf '%-10s %6d bytes\n' "$m" "$(wc -c < "modules/${m}.wasm")"
done
