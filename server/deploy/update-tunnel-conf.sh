#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude - Public tunnel switcher
# =============================================================================
#
#  Controls only public exposure:
#    - Tailscale Funnel can be enabled/disabled.
#    - Cloudflare cloudflared service can be enabled/disabled.
#
#  Tailscale internal-only access is intentionally never disabled here. This
#  script does not run `tailscale down` and does not reset `tailscale serve`.
# =============================================================================
set -euo pipefail

LOCAL_PORT="${LOCAL_PORT:-8787}"
INSTALL_DIR="${INSTALL_DIR:-/opt/pocket-claude}"

c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
step()     { echo; c_blue "==> $*"; }

usage() {
    cat <<'EOF'
Usage:
  sudo bash deploy/update-tunnel-conf.sh status
  sudo bash deploy/update-tunnel-conf.sh disable-funnel
  sudo bash deploy/update-tunnel-conf.sh enable-funnel
  sudo bash deploy/update-tunnel-conf.sh disable-cloudflare
  sudo bash deploy/update-tunnel-conf.sh enable-cloudflare
  sudo bash deploy/update-tunnel-conf.sh switch-to-funnel
  sudo bash deploy/update-tunnel-conf.sh switch-to-cloudflare
  sudo bash deploy/update-tunnel-conf.sh disable-public

Notes:
  - Tailscale internal-only access is never disabled.
  - enable-funnel delegates to setup-tailscale-funnel.sh.
  - enable-cloudflare delegates to setup-cloudflare-tunnel.sh.
  - switch-to-* disables the other public tunnel first.
EOF
}

if [[ $EUID -ne 0 ]]; then
    c_red "Please run with sudo."
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
ACTION="${1:-status}"

tailscale_available() {
    command -v tailscale >/dev/null 2>&1
}

cloudflare_service_exists() {
    systemctl list-unit-files cloudflared.service --no-legend 2>/dev/null |
        grep -q '^cloudflared\.service'
}

cloudflare_config_exists() {
    [[ -f /etc/cloudflared/config.yml ]]
}

disable_funnel() {
    step "Disable Tailscale Funnel public exposure"
    if ! tailscale_available; then
        c_yellow "    tailscale command not found; nothing to disable."
        return
    fi
    tailscale funnel reset 2>/dev/null || true
    c_green "    Tailscale Funnel reset complete."
    c_yellow "    Tailscale internal access is untouched."
}

enable_funnel() {
    step "Enable Tailscale Funnel"
    bash "$SCRIPT_DIR/setup-tailscale-funnel.sh"
}

disable_cloudflare() {
    step "Disable Cloudflare public tunnel"
    if cloudflare_service_exists; then
        systemctl disable --now cloudflared >/dev/null 2>&1 || true
        c_green "    cloudflared service stopped and disabled."
    else
        c_yellow "    cloudflared.service not installed."
    fi
    if cloudflare_config_exists; then
        c_yellow "    Cloudflare config kept at /etc/cloudflared/config.yml for later re-enable."
    fi
}

enable_cloudflare() {
    step "Enable Cloudflare public tunnel"
    if cloudflare_config_exists && cloudflare_service_exists; then
        systemctl enable --now cloudflared
        c_green "    cloudflared service enabled and started."
        return
    fi
    bash "$SCRIPT_DIR/setup-cloudflare-tunnel.sh"
}

status_all() {
    step "Pocket Claude local server"
    if curl -fsS --max-time 5 "http://localhost:$LOCAL_PORT/health" >/dev/null 2>&1; then
        c_green "    Pocket Claude responds on localhost:$LOCAL_PORT/health"
    else
        c_yellow "    Pocket Claude does not respond on localhost:$LOCAL_PORT/health"
        echo "    Check: systemctl status pocket-claude"
    fi

    step "Tailscale"
    if tailscale_available; then
        tailscale status --json 2>/dev/null | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    self = d.get("Self", {})
    print("    Backend: " + str(d.get("BackendState", "unknown")))
    print("    DNSName: " + str(self.get("DNSName", "")).rstrip("."))
except Exception:
    print("    Tailscale status available, but JSON parsing failed.")
' || true
        echo "    Funnel status:"
        tailscale funnel status 2>&1 | sed 's/^/      /' || true
        echo "    Serve status (internal; not managed by this script):"
        tailscale serve status 2>&1 | sed 's/^/      /' || true
    else
        c_yellow "    tailscale command not found."
    fi

    step "Cloudflare"
    if cloudflare_service_exists; then
        systemctl is-enabled cloudflared >/dev/null 2>&1 && enabled="enabled" || enabled="disabled"
        systemctl is-active cloudflared >/dev/null 2>&1 && active="active" || active="inactive"
        echo "    cloudflared.service: $enabled, $active"
    else
        c_yellow "    cloudflared.service not installed."
    fi
    if cloudflare_config_exists; then
        echo "    Config: /etc/cloudflared/config.yml"
        awk '/hostname:/ {print "    Hostname: " $2; exit}' /etc/cloudflared/config.yml || true
    fi
}

case "$ACTION" in
    status)
        status_all
        ;;
    disable-funnel)
        disable_funnel
        ;;
    enable-funnel)
        enable_funnel
        ;;
    disable-cloudflare)
        disable_cloudflare
        ;;
    enable-cloudflare)
        enable_cloudflare
        ;;
    switch-to-funnel)
        disable_cloudflare
        enable_funnel
        ;;
    switch-to-cloudflare)
        disable_funnel
        enable_cloudflare
        ;;
    disable-public)
        disable_funnel
        disable_cloudflare
        ;;
    -h|--help|help)
        usage
        ;;
    *)
        c_red "Unknown action: $ACTION"
        usage
        exit 2
        ;;
esac
