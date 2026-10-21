#!/usr/bin/env bash
# Registers Astra's MCP server with Claude Code for one demo user and tenant, with a fresh token:
#   scripts/mcp-claude.sh [USER] [TENANT] [BASE]     (default: mai saigon-express http://localhost:8080)
# Then run `claude` and ask, for example: "Đơn nào đang kẹt ở kho? Khách nào đang chờ trả COD?"
# Tokens of the local astra-dev-cli client last one hour; run this again to renew.
if (( BASH_VERSINFO[0] < 5 )); then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [[ -x $b ]] && exec "$b" "$0" "$@"; done
  echo "Bash 5+ is required: brew install bash" >&2; exit 1
fi
set -euo pipefail
USER_NAME="${1:-mai}"; TENANT="${2:-saigon-express}"; BASE="${3:-http://localhost:8080}"
cd "$(dirname "$0")/.."
source scripts/lib/demo-common.sh
login "$USER_NAME"
claude mcp remove astra >/dev/null 2>&1 || true
claude mcp add --transport http astra "${BASE}/mcp" \
  --header "Authorization: Bearer ${TOKENS[$USER_NAME]}" --header "X-Astra-Tenant: ${TENANT}"
echo "Astra MCP registered for ${USER_NAME} in ${TENANT} (token valid for about an hour)."
