#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude — Sauberes Entfernen vom Linux-Host
# =============================================================================
#
#  Stoppt + entfernt:
#    - pocket-claude.service (systemd)
#    - cloudflared.service (Tunnel)
#    - /opt/pocket-claude/  (Code + Daten — Daten werden zuerst gesichert)
#    - pocket-claude (Service-User)
#
#  Aufruf:
#      sudo bash /opt/pocket-claude/deploy/helpers/uninstall.sh
#
#  Daten landen vorm Löschen in ~/pocket-claude-backup-<TS>.tar.gz im Home des
#  aufrufenden Users — falls Du mal zurück willst.
# =============================================================================
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/pocket-claude}"
SERVICE_USER="${SERVICE_USER:-pocket-claude}"
SERVICE_NAME="pocket-claude"

c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }

if [[ $EUID -ne 0 ]]; then
    c_red "Bitte mit sudo aufrufen."
    exit 1
fi

echo
c_red "WARNUNG: Das entfernt Pocket Claude komplett vom System."
echo "Was passiert:"
echo "  - systemd-Services stoppen + disablen"
echo "  - Daten als tar.gz im Home Deines Sudo-Users sichern"
echo "  - $INSTALL_DIR komplett löschen"
echo "  - User '$SERVICE_USER' löschen"
echo
read -rp "Wirklich? Tippe 'JA' zur Bestätigung: " confirm
[[ "$confirm" == "JA" ]] || { echo "Abgebrochen."; exit 0; }

c_blue "==> Backup der Daten"
SUDO_USER_HOME="$(eval echo ~"${SUDO_USER:-root}")"
TS=$(date +%Y%m%d-%H%M%S)
BACKUP_FILE="$SUDO_USER_HOME/pocket-claude-backup-$TS.tar.gz"
if [[ -d "$INSTALL_DIR/data" ]] || [[ -f "$INSTALL_DIR/.env" ]]; then
    tar -czf "$BACKUP_FILE" -C "$INSTALL_DIR" data .env 2>/dev/null || true
    chown "${SUDO_USER:-root}:${SUDO_USER:-root}" "$BACKUP_FILE" 2>/dev/null || true
    c_green "    Backup: $BACKUP_FILE"
fi

c_blue "==> Services stoppen"
systemctl disable --now "$SERVICE_NAME" 2>/dev/null || true
systemctl disable --now cloudflared 2>/dev/null || true

c_blue "==> systemd-Units löschen"
rm -f "/etc/systemd/system/${SERVICE_NAME}.service"
systemctl daemon-reload

c_blue "==> Code-Verzeichnis löschen"
rm -rf "$INSTALL_DIR"

c_blue "==> Service-User entfernen"
if id -u "$SERVICE_USER" >/dev/null 2>&1; then
    userdel -r "$SERVICE_USER" 2>/dev/null || userdel "$SERVICE_USER"
fi

echo
c_green "✅ Pocket Claude entfernt."
echo "Daten-Backup: $BACKUP_FILE"
c_yellow "cloudflared-Binary + ~/.cloudflared sind STEHEN GELASSEN — falls Du sie auch loswerden willst:"
echo "    sudo apt remove cloudflared   # bzw. dnf remove cloudflared"
echo "    sudo rm -rf /root/.cloudflared /etc/cloudflared"
