#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude - Tailscale internal-only setup
# =============================================================================
#
#  Makes Pocket Claude reachable only from devices in your tailnet:
#      https://<hostname>.<tailnet>.ts.net
#
#  Android devices must run the Tailscale app and be logged into the same
#  tailnet. Nothing is published to the public internet.
# =============================================================================
set -euo pipefail

LOCAL_PORT="${LOCAL_PORT:-8787}"

c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
step()     { echo; c_blue "==> $*"; }

tailscale_backend_state() {
    tailscale status --json 2>/dev/null | python3 -c '
import json, sys
try:
    print(json.load(sys.stdin).get("BackendState", ""))
except Exception:
    pass
' 2>/dev/null
}

run_tailscale_cmd() {
    if command -v timeout >/dev/null 2>&1; then
        timeout 45s "$@"
    else
        "$@"
    fi
}

if [[ $EUID -ne 0 ]]; then
    c_red "Please run with sudo."
    exit 1
fi

step "Checking Tailscale status"
if ! command -v tailscale >/dev/null 2>&1; then
    c_yellow "    Tailscale is not installed. Installing via the official script..."
    curl -fsSL https://tailscale.com/install.sh | sh
fi

TS_BACKEND="$(tailscale_backend_state)"
if [[ "$TS_BACKEND" != "Running" ]]; then
    echo
    c_yellow "    Tailscale backend state is '${TS_BACKEND:-unknown}', not Running."
    c_yellow "    Starting login flow. Open the printed URL, log in, then return here."
    echo
    tailscale up
else
    c_green "    Tailscale is already running."
fi

step "Reading tailnet configuration"
TS_FQDN="$(tailscale status --json 2>/dev/null | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    print((d.get("Self", {}).get("DNSName", "") or "").rstrip("."))
except Exception:
    pass
' 2>/dev/null)"

if [[ -z "$TS_FQDN" ]] || [[ "$TS_FQDN" != *.ts.net ]]; then
    c_red "    No Tailscale FQDN found on this node."
    echo "    Make sure MagicDNS is enabled in the tailnet admin:"
    echo "        https://login.tailscale.com/admin/dns"
    exit 1
fi
c_green "    FQDN: $TS_FQDN"

step "Serve inside tailnet only -> localhost:$LOCAL_PORT"
tailscale funnel reset 2>/dev/null || true
tailscale serve reset 2>/dev/null || true
serve_rc=0
serve_output="$(run_tailscale_cmd tailscale serve --bg "http://localhost:$LOCAL_PORT" 2>&1)" || serve_rc=$?
if [[ "$serve_rc" -ne 0 ]]; then
    echo "$serve_output" | sed 's/^/    /'
    if echo "$serve_output" | grep -qi "Serve is not enabled"; then
        echo
        c_yellow "    Tailscale Serve must be enabled once in the tailnet admin."
        echo "    Open the URL printed above, or visit:"
        echo "        https://login.tailscale.com/admin/dns"
        echo "    Then re-run:"
        echo "        sudo bash $0"
    elif [[ "$serve_rc" -eq 124 ]]; then
        echo
        c_yellow "    tailscale serve did not finish within 45 seconds."
        echo "    Check in another terminal:"
        echo "        sudo tailscale serve status"
        echo "        sudo tailscale status"
        echo "    If Serve is not enabled, open the Tailscale URL printed by the CLI."
    fi
    exit 1
fi

step "Verification"
tailscale serve status 2>&1 | sed 's/^/    /'
if curl -fsS --max-time 5 "http://localhost:$LOCAL_PORT/health" >/dev/null 2>&1; then
    c_green "    Pocket Claude responds on localhost:$LOCAL_PORT/health"
else
    c_yellow "    Pocket Claude does NOT respond on localhost:$LOCAL_PORT yet."
    c_yellow "      Check:  systemctl status pocket-claude"
fi

echo
echo "============================================================"
c_green "Tailscale internal access is configured."
echo "============================================================"
echo
echo "Pocket Claude is reachable only from your tailnet at:"
c_green "  https://$TS_FQDN"
echo
echo "Android setup:"
echo "  1. Install/log in to the Tailscale Android app."
echo "  2. Enter this server URL in Pocket Claude:"
echo "        https://$TS_FQDN"
echo
echo "Status:   tailscale serve status"
echo "Disable:  tailscale serve reset"
