#!/usr/bin/env bash
# Builds the HRM demo's tenant payroll formulas to WebAssembly and copies them to modules/, where
# scripts/demo-seed.sh and the tests pick them up. The .wasm files are committed, so this is only needed
# after changing the Rust sources.
set -euo pipefail
cd "$(dirname "$0")"
TRIPLE=wasm32-unknown-unknown
rustup target add "$TRIPLE" >/dev/null
cargo build --release --target "$TRIPLE"
mkdir -p modules
for m in courier-kpi runaway-kpi; do
  cp "target/${TRIPLE}/release/${m//-/_}.wasm" "modules/${m}.wasm"
  printf '%-12s %6d bytes\n' "$m" "$(wc -c < "modules/${m}.wasm")"
done
