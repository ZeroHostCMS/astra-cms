#!/usr/bin/env bash
# Calls one tool of Astra's MCP server (POST /mcp, JSON-RPC tools/call) with the session scripts/mcp-claude.sh opened,
# for tools that take a file: key@=path sends the file base64-encoded, so a module never has to be retyped.
#   scripts/astra-mcp.sh <tool> [key=value ...] [key:=json ...] [key@=file ...]
# Example: scripts/astra-mcp.sh anomaly_backtest module_base64@=anomaly.wasm
set -euo pipefail
SESSION="${ASTRA_MCP_SESSION:-${TMPDIR:-/tmp}/astra-mcp-session.env}"
[[ -r $SESSION ]] || { echo "no MCP session: run scripts/mcp-claude.sh USER TENANT first" >&2; exit 1; }
# shellcheck source=/dev/null
source "$SESSION"
tool="${1:?usage: $0 <tool> [key=value|key:=json|key@=file ...]}"; shift
args='{}'
for kv in "$@"; do
  case "$kv" in
    *@=*) k="${kv%%@=*}"; f="${kv#*@=}"; args=$(jq -c --arg k "$k" --rawfile v <(base64 < "$f" | tr -d '\n') '. + {($k): $v}' <<<"$args") ;;
    *:=*) k="${kv%%:=*}"; args=$(jq -c --arg k "$k" --argjson v "${kv#*:=}" '. + {($k): $v}' <<<"$args") ;;
    *=*)  k="${kv%%=*}"; args=$(jq -c --arg k "$k" --arg v "${kv#*=}" '. + {($k): $v}' <<<"$args") ;;
    *) echo "argument '$kv' is not key=value, key:=json or key@=file" >&2; exit 2 ;;
  esac
done
body=$(jq -c --arg t "$tool" --argjson a "$args" '{jsonrpc: "2.0", id: 1, method: "tools/call", params: {name: $t, arguments: $a}}' <<<'{}')
curl -sS "${ASTRA_BASE}/mcp" -H "Authorization: Bearer ${ASTRA_TOKEN}" -H "X-Astra-Tenant: ${ASTRA_TENANT}" \
  -H 'Content-Type: application/json' -d "$body" | jq '.result.structuredContent // .result // .error'
