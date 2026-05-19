#!/usr/bin/env bash
# =============================================================================
#  Legacy-Wrapper — verweist auf den neuen Tunnel-Setup-Skript.
# =============================================================================
#
#  Volle Doku + Tailscale-Alternative: deploy/README.md
# =============================================================================
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec sudo bash "$DIR/deploy/setup-cloudflare-tunnel.sh" "$@"
