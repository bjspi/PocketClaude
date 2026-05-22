#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude — Cloudflare Named Tunnel Setup
# =============================================================================
#
#  Sets up a PERSISTENT Cloudflare tunnel:
#    - Custom hostname (e.g. claude.your-domain.com)
#    - Persistent tunnel ID + credentials in /etc/cloudflared/
#    - Runs as a systemd service -> auto-restart, auto-start on boot
#    - Survives reboot: same URL, no manual action needed
#    - Optional Cloudflare Access in front of the app. Access itself is
#      configured in the Cloudflare Zero Trust dashboard; this script prints
#      the exact values the Android app needs afterwards.
#
#  Requirements:
#    - You have a Cloudflare account and a domain managed by Cloudflare DNS.
#    - You can use any subdomain under that zone, for example
#      claude.example.com. Dynamic DNS or trycloudflare.com are not
#      enough for a persistent Access-protected app.
#    - You have already run `install-linux.sh`.
#
#  If you don't have your own domain: use `setup-tailscale-funnel.sh` instead.
# =============================================================================
set -euo pipefail

TUNNEL_NAME="${TUNNEL_NAME:-pocket-claude}"
DEFAULT_TUNNEL_SUBDOMAIN="${TUNNEL_SUBDOMAIN:-pocket-claude}"
LOCAL_TARGET="${LOCAL_TARGET:-http://localhost:8787}"
CRED_DIR="/etc/cloudflared"

# ---------------------------------------------------------------- Pretty-Print
c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
step()     { echo; c_blue "==> $*"; }

read_prompt() {
    local prompt="$1"
    local var_name="$2"
    if [[ -r /dev/tty ]]; then
        read -r -p "$prompt" "$var_name" < /dev/tty
    elif [[ -t 0 ]]; then
        read -r -p "$prompt" "$var_name"
    else
        return 1
    fi
}

prompt_yes_no() {
    local prompt="$1"
    local default="${2:-n}"
    local answer
    if [[ "$default" =~ ^[Yy]$ ]]; then
        read_prompt "$prompt [Y/n] " answer || answer="y"
        answer="${answer:-y}"
    else
        read_prompt "$prompt [y/N] " answer || answer="n"
        answer="${answer:-n}"
    fi
    [[ "$answer" =~ ^[Yy]$ ]]
}

read_prompt_default() {
    local prompt="$1"
    local var_name="$2"
    local default="$3"
    local answer
    if ! read_prompt "$prompt" answer; then
        printf -v "$var_name" '%s' "$default"
        return 0
    fi
    printf -v "$var_name" '%s' "${answer:-$default}"
}

# ---------------------------------------------------------------- Root-Check
if [[ $EUID -ne 0 ]]; then
    c_red "Please run with sudo."
    exit 1
fi

# ---------------------------------------------------------------- Domain requirement
step "Cloudflare domain requirement"
echo
c_yellow "    Cloudflare Named Tunnel + Access needs a hostname in a domain"
c_yellow "    that is managed by your Cloudflare account, for example:"
echo "        claude.example.com"
echo
echo "    Without your own Cloudflare-managed domain, Cloudflare only offers"
echo "    TryCloudflare quick tunnels on random *.trycloudflare.com names."
echo "    Those are intended for testing/development, are not stable enough"
echo "    for this installer, and are not the right place for Access Service Auth."
echo
if [[ -n "${TUNNEL_HOSTNAME:-}" ]]; then
    c_green "    TUNNEL_HOSTNAME is set: $TUNNEL_HOSTNAME"
elif [[ -n "${TUNNEL_DOMAIN:-}" ]]; then
    c_green "    TUNNEL_DOMAIN is set: $TUNNEL_DOMAIN"
else
    if ! prompt_yes_no "Do you have a domain managed by Cloudflare DNS?" "n"; then
        c_red "Cloudflare Tunnel setup cannot continue without a Cloudflare-managed domain."
        echo
        echo "Use one of these instead:"
        echo "  - Tailscale internal-only: sudo bash /opt/pocket-claude/deploy/setup-tailscale-internal.sh"
        echo "  - Tailscale Funnel:       sudo bash /opt/pocket-claude/deploy/setup-tailscale-funnel.sh"
        echo
        echo "If you later add a domain to Cloudflare, re-run this script."
        exit 1
    fi
fi

# ---------------------------------------------------------------- cloudflared
step "Installing cloudflared (if missing)"
if ! command -v cloudflared >/dev/null 2>&1; then
    if [[ -f /etc/debian_version ]]; then
        mkdir -p --mode=0755 /usr/share/keyrings
        curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg \
            | tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
        echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" \
            | tee /etc/apt/sources.list.d/cloudflared.list
        apt-get update -qq
        apt-get install -y cloudflared
    else
        # Fedora/RHEL — fetch binary directly
        ARCH=$(uname -m)
        case "$ARCH" in
            x86_64) BIN="cloudflared-linux-amd64" ;;
            aarch64|arm64) BIN="cloudflared-linux-arm64" ;;
            *) c_red "Unknown architecture: $ARCH"; exit 1 ;;
        esac
        curl -fsSL "https://github.com/cloudflare/cloudflared/releases/latest/download/$BIN" \
            -o /usr/local/bin/cloudflared
        chmod +x /usr/local/bin/cloudflared
    fi
    c_green "    cloudflared installed."
else
    c_green "    cloudflared already present ($(cloudflared --version 2>&1 | head -1))."
fi

# ---------------------------------------------------------------- Login
step "Cloudflare login"
# We log in as root because the later systemd service also runs as root
# and credentials are expected under /etc/cloudflared.
if [[ ! -f /root/.cloudflared/cert.pem ]]; then
    echo
    c_yellow "    The browser will ask you to grant access to a domain."
    c_yellow "    If the host has no browser: copy the printed URL, open it"
    c_yellow "    on another device, select the domain there, then return here."
    echo
    cloudflared tunnel login
else
    c_green "    Login already present ($(ls -la /root/.cloudflared/cert.pem | awk '{print $6, $7, $8}'))."
fi

step "Cloudflare Access option"
echo
c_yellow "    Cloudflare Tunnel publishes the hostname. To make it private,"
c_yellow "    create a Cloudflare Access self-hosted application for this hostname"
c_yellow "    and add either a user login policy or a Service Auth policy."
echo
echo "    For the Android app, Service Auth is the practical option:"
echo "      1. Zero Trust Dashboard -> Access -> Service Auth -> Service Tokens"
echo "      2. Create a token and copy Client ID + Client Secret"
echo "      3. In the Access app policy, add Service Auth for that token"
echo "      4. Enter the same Client ID + Secret in Pocket Claude's profile"
echo
if prompt_yes_no "Will this hostname be protected by Cloudflare Access Service Token?" "n"; then
    USE_CF_ACCESS="y"
else
    USE_CF_ACCESS="n"
fi

# ---------------------------------------------------------------- Create tunnel
step "Create tunnel '$TUNNEL_NAME' (if missing)"
# `cloudflared tunnel list` returns tabular output. We parse column 2 (NAME).
if ! cloudflared tunnel list 2>/dev/null | awk 'NR>1 && $2==t {f=1} END{exit !f}' t="$TUNNEL_NAME"; then
    cloudflared tunnel create "$TUNNEL_NAME"
    c_green "    Tunnel created."
else
    c_green "    Tunnel already exists."
fi

TUNNEL_ID="$(cloudflared tunnel list 2>/dev/null | awk -v t="$TUNNEL_NAME" 'NR>1 && $2==t {print $1; exit}')"
if [[ -z "$TUNNEL_ID" ]]; then
    c_red "Could not determine tunnel ID."
    exit 1
fi
c_green "    Tunnel ID: $TUNNEL_ID"

# ---------------------------------------------------------------- Credentials file
step "Move credentials to $CRED_DIR (for systemd run as root)"
mkdir -p "$CRED_DIR"
# Source: ~/.cloudflared/$TUNNEL_ID.json (created by `tunnel create`)
SRC_CRED="/root/.cloudflared/$TUNNEL_ID.json"
DST_CRED="$CRED_DIR/$TUNNEL_ID.json"
if [[ -f "$SRC_CRED" && ! -f "$DST_CRED" ]]; then
    cp "$SRC_CRED" "$DST_CRED"
    chmod 600 "$DST_CRED"
fi
if [[ ! -f "$DST_CRED" ]]; then
    c_red "Credentials file $DST_CRED not found — did tunnel create fail?"
    exit 1
fi

# ---------------------------------------------------------------- Hostname prompt
step "Configure DNS record"
echo
HOSTNAME="${TUNNEL_HOSTNAME:-}"
if [[ -n "$HOSTNAME" ]]; then
    c_green "    Using hostname from TUNNEL_HOSTNAME: $HOSTNAME"
else
    echo "Which Cloudflare hostname should the tunnel use?"
    echo "Enter the Cloudflare-managed domain and the subdomain label separately."
    echo "Example: domain example.com + subdomain claude -> claude.example.com"
    echo

    DOMAIN="${TUNNEL_DOMAIN:-}"
    SUBDOMAIN="$DEFAULT_TUNNEL_SUBDOMAIN"

    if [[ -z "$DOMAIN" ]]; then
        if ! read_prompt "Cloudflare domain/zone (e.g. example.com): " DOMAIN; then
            c_red "No hostname provided and no interactive terminal available."
            echo "Set either TUNNEL_HOSTNAME or TUNNEL_DOMAIN/TUNNEL_SUBDOMAIN, for example:"
            echo "    sudo TUNNEL_HOSTNAME=claude.example.com bash $0"
            echo "    sudo TUNNEL_DOMAIN=example.com TUNNEL_SUBDOMAIN=claude bash $0"
            exit 1
        fi
    else
        c_green "    Using domain from TUNNEL_DOMAIN: $DOMAIN"
    fi

    if [[ -z "${TUNNEL_SUBDOMAIN:-}" ]]; then
        if ! read_prompt_default "Subdomain label [$DEFAULT_TUNNEL_SUBDOMAIN] (use @ for root domain): " SUBDOMAIN "$DEFAULT_TUNNEL_SUBDOMAIN"; then
            c_red "No subdomain provided and no interactive terminal available."
            echo "Set either TUNNEL_HOSTNAME or TUNNEL_DOMAIN/TUNNEL_SUBDOMAIN, for example:"
            echo "    sudo TUNNEL_HOSTNAME=claude.example.com bash $0"
            echo "    sudo TUNNEL_DOMAIN=example.com TUNNEL_SUBDOMAIN=claude bash $0"
            exit 1
        fi
    else
        c_green "    Using subdomain from TUNNEL_SUBDOMAIN: $SUBDOMAIN"
    fi

    if [[ -z "$DOMAIN" || -z "$SUBDOMAIN" ]]; then
        c_red "Domain and subdomain must not be empty."
        exit 1
    fi

    if [[ "$SUBDOMAIN" == "@" ]]; then
        HOSTNAME="$DOMAIN"
    else
        HOSTNAME="$SUBDOMAIN.$DOMAIN"
    fi
fi
if [[ -z "$HOSTNAME" ]]; then
    c_red "No hostname provided."
    exit 1
fi
if [[ "$HOSTNAME" == http://* || "$HOSTNAME" == https://* || "$HOSTNAME" == */* ]]; then
    c_red "Use a hostname only, without https:// and without a path: $HOSTNAME"
    exit 1
fi
c_green "    Cloudflare hostname: $HOSTNAME"

# ---------------------------------------------------------------- Config file
step "Writing tunnel config"
cat > "$CRED_DIR/config.yml" <<EOF
# Auto-generated by deploy/setup-cloudflare-tunnel.sh
# Editable by hand — afterwards: sudo systemctl restart cloudflared

tunnel: $TUNNEL_ID
credentials-file: $DST_CRED

# Outbound connection to Cloudflare; no port forwarding in the router required.
ingress:
  - hostname: $HOSTNAME
    service: $LOCAL_TARGET
    originRequest:
      # SSE streams (chat) need long read phases -> no aggressive timeout.
      noTLSVerify: false
      connectTimeout: 30s
      tlsTimeout: 10s
      tcpKeepAlive: 30s
      keepAliveConnections: 10
      keepAliveTimeout: 90s
  # Catch-all for everything else
  - service: http_status:404
EOF
chmod 644 "$CRED_DIR/config.yml"

# ---------------------------------------------------------------- DNS route
step "Create DNS record at Cloudflare"
# `route dns` is idempotent — no error on existing record.
cloudflared tunnel route dns "$TUNNEL_NAME" "$HOSTNAME" || \
    c_yellow "    DNS route may already exist — no error."

# ---------------------------------------------------------------- systemd service
step "Install cloudflared as a systemd service"
# `cloudflared service install` creates /etc/systemd/system/cloudflared.service
# and reads from /etc/cloudflared/config.yml.
if ! systemctl list-unit-files | grep -q '^cloudflared.service'; then
    cloudflared service install
fi
systemctl daemon-reload
systemctl enable --now cloudflared
sleep 2
if systemctl is-active --quiet cloudflared; then
    c_green "    ✓ cloudflared running."
else
    c_yellow "    cloudflared not active — logs: journalctl -u cloudflared -n 50"
fi

# ---------------------------------------------------------------- Success
echo
echo "============================================================"
c_green "Cloudflare Named Tunnel is running."
echo "============================================================"
echo
echo "Pocket Claude is now PERMANENTLY reachable at:"
c_green "  https://$HOSTNAME"
echo
echo "This URL survives any reboot — enter it in the Pocket Claude app."
if [[ "$USE_CF_ACCESS" =~ ^[Yy]$ ]]; then
    echo
    c_yellow "Android app profile:"
    echo "  Server URL:                 https://$HOSTNAME"
    echo "  Cloudflare Access:          enabled"
    echo "  CF-Access-Client-Id:        <your Service Token Client ID>"
    echo "  CF-Access-Client-Secret:    <your Service Token Client Secret>"
    echo
    echo "Requests without those headers will be blocked by Cloudflare before"
    echo "they reach Pocket Claude."
else
    echo
    c_yellow "No Cloudflare Access Service Token selected."
    echo "Anyone who can reach https://$HOSTNAME can see Pocket Claude's login."
fi
echo
echo "Status:    systemctl status cloudflared"
echo "Logs:      journalctl -u cloudflared -f"
echo "Config:    $CRED_DIR/config.yml"
echo
