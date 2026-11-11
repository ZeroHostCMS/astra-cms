#!/usr/bin/env bash
# Builds the Express demo's tenant scripts to WebAssembly and copies them to wasm/modules, where scripts/demo-seed.sh
# and scripts/demo-tiers.sh pick them up. The .wasm files are committed, so this is only needed after changing the
# Rust sources.
set -euo pipefail
cd "$(dirname "$0")/../astra-apps/express/wasm"
TRIPLE=wasm32-unknown-unknown
rustup target add "$TRIPLE" >/dev/null
OUT="target/${TRIPLE}/release"
cargo build --release --target "$TRIPLE"
mkdir -p modules
copy() { # crate-file module-name
  cp "${OUT}/$1.wasm" "modules/$2.wasm"
  printf '%-24s %6d bytes\n' "$2" "$(wc -c < "modules/$2.wasm")"
}
copy tet_promo tet-promo
copy runaway runaway
copy abi_probe abi-probe   # test fixture of the bytes ABI
copy khaigia_shopabc khaigia-shopabc-1.0.0
cargo build --release --target "$TRIPLE" -p khaigia-shopabc --features v2   # contract annex v2
copy khaigia_shopabc khaigia-shopabc-1.1.0
copy anomaly_ops anomaly-ops-1.0.0          # reference detector (the video's is written by Claude Code)
cargo build --release --target "$TRIPLE" -p anomaly-ops --features v2
copy anomaly_ops anomaly-ops-1.1.0
