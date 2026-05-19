#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude — Cloudflare Named Tunnel Setup
# =============================================================================
#
#  Setzt einen DAUERHAFTEN Cloudflare-Tunnel auf:
#    - Eigene Subdomain (z.B. pocket-claude.deine-domain.de)
#    - Persistente Tunnel-ID + Credentials in /etc/cloudflared/
#    - Läuft als systemd-Service → Auto-Restart, Auto-Start beim Boot
#    - Survival nach Reboot: gleiche URL, kein manueller Eingriff nötig
#
#  Voraussetzung:
#    - Du hast eine Domain bei Cloudflare verwaltet (gratis: jede Domain dort
#      transferieren oder als DNS-Provider Cloudflare nutzen).
#    - Du hast `install-linux.sh` schon ausgeführt.
#
#  Falls keine eigene Domain: nimm stattdessen `setup-tailscale-funnel.sh`.
# =============================================================================
set -euo pipefail

TUNNEL_NAME="${TUNNEL_NAME:-pocket-claude}"
LOCAL_TARGET="${LOCAL_TARGET:-http://localhost:8787}"
CRED_DIR="/etc/cloudflared"

# ---------------------------------------------------------------- Pretty-Print
c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
step()     { echo; c_blue "==> $*"; }

# ---------------------------------------------------------------- Root-Check
if [[ $EUID -ne 0 ]]; then
    c_red "Bitte mit sudo aufrufen."
    exit 1
fi

# ---------------------------------------------------------------- cloudflared
step "cloudflared installieren (falls fehlt)"
if ! command -v cloudflared >/dev/null 2>&1; then
    if [[ -f /etc/debian_version ]]; then
        mkdir -p --mode=0755 /usr/share/keyrings
        curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg \
            | tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
        echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared $(lsb_release -cs) main" \
            | tee /etc/apt/sources.list.d/cloudflared.list
        apt-get update -qq
        apt-get install -y cloudflared
    else
        # Fedora/RHEL — Binary direkt holen
        ARCH=$(uname -m)
        case "$ARCH" in
            x86_64) BIN="cloudflared-linux-amd64" ;;
            aarch64|arm64) BIN="cloudflared-linux-arm64" ;;
            *) c_red "Unbekannte Architektur: $ARCH"; exit 1 ;;
        esac
        curl -fsSL "https://github.com/cloudflare/cloudflared/releases/latest/download/$BIN" \
            -o /usr/local/bin/cloudflared
        chmod +x /usr/local/bin/cloudflared
    fi
    c_green "    cloudflared installiert."
else
    c_green "    cloudflared ist schon da ($(cloudflared --version 2>&1 | head -1))."
fi

# ---------------------------------------------------------------- Login
step "Cloudflare-Login"
# Wir loggen als root ein, weil der spätere systemd-Service auch als root
# läuft und die Credentials unter /etc/cloudflared erwartet werden.
if [[ ! -f /root/.cloudflared/cert.pem ]]; then
    echo
    c_yellow "    Im Browser wirst Du gleich aufgefordert, einer Domain Zugriff zu geben."
    c_yellow "    Falls Du am Mini-PC keinen Browser hast: die ausgegebene URL kopieren,"
    c_yellow "    auf einem anderen Gerät öffnen, dort Domain auswählen, dann hier zurück."
    echo
    cloudflared tunnel login
else
    c_green "    Login schon da ($(ls -la /root/.cloudflared/cert.pem | awk '{print $6, $7, $8}'))."
fi

# ---------------------------------------------------------------- Tunnel anlegen
step "Tunnel '$TUNNEL_NAME' anlegen (falls fehlt)"
# `cloudflared tunnel list` gibt Tabular-Output zurück. Wir parsen die 2. Spalte (NAME).
if ! cloudflared tunnel list 2>/dev/null | awk 'NR>1 && $2==t {f=1} END{exit !f}' t="$TUNNEL_NAME"; then
    cloudflared tunnel create "$TUNNEL_NAME"
    c_green "    Tunnel angelegt."
else
    c_green "    Tunnel existiert schon."
fi

TUNNEL_ID="$(cloudflared tunnel list 2>/dev/null | awk -v t="$TUNNEL_NAME" 'NR>1 && $2==t {print $1; exit}')"
if [[ -z "$TUNNEL_ID" ]]; then
    c_red "Konnte Tunnel-ID nicht ermitteln."
    exit 1
fi
c_green "    Tunnel-ID: $TUNNEL_ID"

# ---------------------------------------------------------------- Credentials-File
step "Credentials nach $CRED_DIR umziehen (für systemd-Run als root)"
mkdir -p "$CRED_DIR"
# Quelle: ~/.cloudflared/$TUNNEL_ID.json (wurde von `tunnel create` angelegt)
SRC_CRED="/root/.cloudflared/$TUNNEL_ID.json"
DST_CRED="$CRED_DIR/$TUNNEL_ID.json"
if [[ -f "$SRC_CRED" && ! -f "$DST_CRED" ]]; then
    cp "$SRC_CRED" "$DST_CRED"
    chmod 600 "$DST_CRED"
fi
if [[ ! -f "$DST_CRED" ]]; then
    c_red "Credentials-File $DST_CRED nicht gefunden — Tunnel-Create gescheitert?"
    exit 1
fi

# ---------------------------------------------------------------- Hostname-Prompt
step "DNS-Eintrag konfigurieren"
echo
echo "Welche Subdomain soll der Tunnel haben?"
echo "(Beispiel: pocket-claude.deine-domain.de — die Domain muss bei Cloudflare verwaltet sein.)"
read -rp "Hostname: " HOSTNAME
if [[ -z "$HOSTNAME" ]]; then
    c_red "Kein Hostname angegeben."
    exit 1
fi

# ---------------------------------------------------------------- Config-Datei
step "Tunnel-Config schreiben"
cat > "$CRED_DIR/config.yml" <<EOF
# Auto-generiert von deploy/setup-cloudflare-tunnel.sh
# Manuell editierbar — danach: sudo systemctl restart cloudflared

tunnel: $TUNNEL_ID
credentials-file: $DST_CRED

# Outbound-Connection zu Cloudflare; kein Port-Forwarding im Router nötig.
ingress:
  - hostname: $HOSTNAME
    service: $LOCAL_TARGET
    originRequest:
      # SSE-Streams (Chat) brauchen lange Read-Phasen → kein aggressives Timeout.
      noTLSVerify: false
      connectTimeout: 30s
      tlsTimeout: 10s
      tcpKeepAlive: 30s
      keepAliveConnections: 10
      keepAliveTimeout: 90s
  # Catch-all für alles andere
  - service: http_status:404
EOF
chmod 644 "$CRED_DIR/config.yml"

# ---------------------------------------------------------------- DNS-Route
step "DNS-Eintrag bei Cloudflare anlegen"
# `route dns` ist idempotent — kein Fehler bei bestehendem Eintrag.
cloudflared tunnel route dns "$TUNNEL_NAME" "$HOSTNAME" || \
    c_yellow "    DNS-Route existiert evtl. schon — kein Fehler."

# ---------------------------------------------------------------- systemd-Service
step "cloudflared als systemd-Service installieren"
# `cloudflared service install` legt /etc/systemd/system/cloudflared.service an
# und liest aus /etc/cloudflared/config.yml.
if ! systemctl list-unit-files | grep -q '^cloudflared.service'; then
    cloudflared service install
fi
systemctl daemon-reload
systemctl enable --now cloudflared
sleep 2
if systemctl is-active --quiet cloudflared; then
    c_green "    ✓ cloudflared läuft."
else
    c_yellow "    cloudflared nicht aktiv — Logs: journalctl -u cloudflared -n 50"
fi

# ---------------------------------------------------------------- Erfolg
echo
echo "============================================================"
c_green "✅ Cloudflare Named Tunnel läuft."
echo "============================================================"
echo
echo "Pocket Claude ist jetzt PERMANENT erreichbar unter:"
c_green "  https://$HOSTNAME"
echo
echo "Diese URL überlebt jeden Reboot — in der Pocket-Claude-App eintragen."
echo
echo "Status:    systemctl status cloudflared"
echo "Logs:      journalctl -u cloudflared -f"
echo "Config:    $CRED_DIR/config.yml"
echo
