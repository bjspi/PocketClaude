#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude — Update (auf dem Mini-PC)
# =============================================================================
#
#  Holt die neueste Version von GitHub und startet den Service neu.
#  Idempotent — nichts kaputt wenn schon aktuell.
#
#  Aufruf:
#      sudo bash /opt/pocket-claude/deploy/helpers/update.sh
# =============================================================================
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/pocket-claude}"
SERVICE_USER="${SERVICE_USER:-pocket-claude}"
SERVICE_NAME="pocket-claude"

c_blue()  { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green() { printf '\033[1;32m%s\033[0m\n' "$*"; }
step()    { echo; c_blue "==> $*"; }

if [[ $EUID -ne 0 ]]; then
    echo "Bitte mit sudo aufrufen." >&2
    exit 1
fi

step "Repo updaten ($INSTALL_DIR)"
if [[ ! -d "$INSTALL_DIR/.git" ]]; then
    echo "    Kein git-Repo unter $INSTALL_DIR — Skip pull (Dev-Install via rsync?)."
else
    sudo -u "$SERVICE_USER" git -C "$INSTALL_DIR" fetch --quiet origin
    BEFORE=$(sudo -u "$SERVICE_USER" git -C "$INSTALL_DIR" rev-parse HEAD)
    sudo -u "$SERVICE_USER" git -C "$INSTALL_DIR" reset --hard origin/main
    AFTER=$(sudo -u "$SERVICE_USER" git -C "$INSTALL_DIR" rev-parse HEAD)
    if [[ "$BEFORE" == "$AFTER" ]]; then
        c_green "    Schon aktuell."
    else
        c_green "    Update: $BEFORE → $AFTER"
    fi
fi

step "Dependencies aktualisieren"
sudo -u "$SERVICE_USER" "$INSTALL_DIR/.venv/bin/pip" install --quiet --upgrade -r "$INSTALL_DIR/requirements.txt"

step "systemd-Unit neu rendern (falls Template geändert)"
sed -e "s|@PROJECT_DIR@|$INSTALL_DIR|g" -e "s|@SERVICE_USER@|$SERVICE_USER|g" \
    "$INSTALL_DIR/deploy/systemd/pocket-claude.service" > "/etc/systemd/system/${SERVICE_NAME}.service"
systemctl daemon-reload

step "Service neu starten"
systemctl restart "$SERVICE_NAME"
sleep 2
if systemctl is-active --quiet "$SERVICE_NAME"; then
    c_green "    ✓ Service läuft."
else
    echo "    ⚠ Service nicht aktiv — Logs: journalctl -u $SERVICE_NAME -n 50"
fi
