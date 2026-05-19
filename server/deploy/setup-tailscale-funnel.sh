#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude — Tailscale Funnel Setup (default path)
# =============================================================================
#
#  Makes Pocket Claude publicly reachable via a persistent URL of the form
#      https://<hostname>.<tailnet>.ts.net
#  e.g. https://my-host.my-tailnet.ts.net
#
#  Benefits:
#    - 100% free (Free plan: 50 GB public bandwidth/month — way more than
#      enough for a personal chat app)
#    - Automatic HTTPS via Let's Encrypt (managed by Tailscale)
#    - URL survives reboots, power outages, Tailscale restarts
#    - No DNS / domain setup, no port forwarding
#
#  Requirements:
#    - Tailscale is installed AND the host is part of the tailnet
#      (visible at https://login.tailscale.com/admin/machines)
#    - In the tailnet admin, MagicDNS + HTTPS are enabled:
#        https://login.tailscale.com/admin/dns
#    - Funnel capability is allowed in the ACLs for this node
#      (default for owner nodes: yes)
#
#  Usage (on the server host):
#      sudo bash /opt/pocket-claude/deploy/setup-tailscale-funnel.sh
#
#  The script is idempotent — safe to run any number of times.
# =============================================================================
set -euo pipefail

LOCAL_PORT="${LOCAL_PORT:-8787}"

c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
step()     { echo; c_blue "==> $*"; }

if [[ $EUID -ne 0 ]]; then
    c_red "Bitte mit sudo aufrufen."
    exit 1
fi

# ---------------------------------------------------------------- Tailscale check
step "Tailscale-Status prüfen"
if ! command -v tailscale >/dev/null 2>&1; then
    c_yellow "    Tailscale ist nicht installiert. Installiere via offizielles Skript…"
    curl -fsSL https://tailscale.com/install.sh | sh
fi

# Backend muss "Running" sein — also `tailscale up` schon einmal gelaufen.
if ! tailscale status --json 2>/dev/null | grep -q '"BackendState":"Running"'; then
    echo
    c_yellow "    Tailscale läuft nicht / Mini-PC ist nicht im Tailnet."
    c_yellow "    Starte Login-Flow — Du bekommst eine URL, die Du auf einem anderen"
    c_yellow "    Gerät öffnest, das schon im Tailnet ist."
    echo
    tailscale up --ssh
fi

# ---------------------------------------------------------------- URL ermitteln
# Wir lesen den FQDN dieses Knotens aus dem tailscale-Status — das ist die URL,
# die später für Funnel benutzt wird. Wenn MagicDNS aus ist oder der Tailnet
# noch keinen Public-Namen hat, brechen wir mit klarer Anleitung ab.
step "Tailnet-Konfiguration auslesen"
TS_FQDN="$(tailscale status --json 2>/dev/null | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    print((d.get("Self", {}).get("DNSName", "") or "").rstrip("."))
except Exception:
    pass
' 2>/dev/null)"

if [[ -z "$TS_FQDN" ]] || [[ "$TS_FQDN" != *.ts.net ]]; then
    c_red "    Kein Tailscale-FQDN auf diesem Knoten gefunden."
    echo "    Stelle sicher dass im Tailnet-Admin MagicDNS aktiviert ist:"
    echo "        https://login.tailscale.com/admin/dns"
    exit 1
fi
c_green "    FQDN: $TS_FQDN"

# ---------------------------------------------------------------- HTTPS-Cert
step "HTTPS-Cert-Provisioning checken"
# `tailscale cert` legt das Cert provisorisch an — falls die Tailnet-Feature
# „HTTPS Certificates" noch nicht aktiviert ist, scheitert das hier mit einer
# klaren Fehlermeldung. Wir machen einen leichtgewichtigen Probe-Lauf.
if ! tailscale cert --cert-file=/tmp/tspc-probe.crt --key-file=/tmp/tspc-probe.key "$TS_FQDN" >/dev/null 2>&1; then
    rm -f /tmp/tspc-probe.crt /tmp/tspc-probe.key
    c_yellow "    HTTPS-Cert-Provisioning ist (noch) nicht aktiviert."
    echo
    echo "    Aktiviere im Tailscale-Admin EINMALIG:"
    c_yellow "        https://login.tailscale.com/admin/dns"
    echo "        → \"HTTPS Certificates\" → Enable"
    echo
    read -rp "    Aktiviert? Drücke Enter zum Erneut-Probieren … " _
    if ! tailscale cert --cert-file=/tmp/tspc-probe.crt --key-file=/tmp/tspc-probe.key "$TS_FQDN" >/dev/null 2>&1; then
        c_red "    Klappt immer noch nicht — bitte HTTPS im Admin aktivieren und Skript erneut starten."
        exit 1
    fi
fi
rm -f /tmp/tspc-probe.crt /tmp/tspc-probe.key
c_green "    HTTPS-Cert verfügbar."

# ---------------------------------------------------------------- Funnel aktivieren
step "Funnel auf Port 443 → localhost:$LOCAL_PORT"
# Tailscale 1.96+ hat die funnel/serve-CLI vereinfacht — `funnel <target>`
# erledigt beides (serve + public-machen) in einem Aufruf. Idempotent — vorhandene
# Routes werden überschrieben, falls schon was da war.
tailscale funnel reset 2>/dev/null || true
tailscale serve reset 2>/dev/null || true

# Funnel direkt aufs lokale Target schicken — kein separates `serve` mehr nötig.
tailscale funnel --bg "http://localhost:$LOCAL_PORT"

# ---------------------------------------------------------------- Verifikation
step "Verifikation"
# 1. Status-Output von Tailscale
tailscale funnel status 2>&1 | sed 's/^/    /'

# 2. Quick-Test: läuft der Pocket-Claude-Server lokal?
if curl -fsS --max-time 5 "http://localhost:$LOCAL_PORT/health" >/dev/null 2>&1; then
    c_green "    ✓ Pocket Claude antwortet auf localhost:$LOCAL_PORT/health"
else
    c_yellow "    ⚠ Pocket Claude antwortet NICHT auf localhost:$LOCAL_PORT — Funnel ist trotzdem aktiv."
    c_yellow "      Prüfe:  systemctl status pocket-claude"
fi

echo
echo "============================================================"
c_green "✅ Tailscale Funnel läuft."
echo "============================================================"
echo
echo "Pocket Claude ist jetzt PERMANENT erreichbar unter:"
c_green "  https://$TS_FQDN"
echo
echo "Diese URL überlebt jeden Reboot — in der Pocket-Claude-App eintragen."
echo
echo "Status:       tailscale funnel status"
echo "Abschalten:   tailscale funnel --https=443 off"
echo "Wieder an:    tailscale funnel --bg 443 on"
echo
