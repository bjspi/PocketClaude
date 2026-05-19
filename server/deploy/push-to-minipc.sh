#!/usr/bin/env bash
# =============================================================================
#  Mac → Mini-PC — Daily Deploy
# =============================================================================
#
#  Schritt-Loop, der nach jeder Code-Änderung läuft:
#    1. Mac-Code via rsync nach /opt/pocket-claude (außer data/, .env, .venv)
#    2. Falls requirements.txt geändert: pip install im Server-venv
#    3. systemctl restart pocket-claude
#    4. /health-Check via öffentlicher Tunnel-URL
#
#  Voraussetzung — einmalig auf dem Mini-PC eingerichtet:
#    - Dein User ist Mitglied der Gruppe `pocket-claude` (kann nach
#      /opt/pocket-claude schreiben)
#    - NOPASSWD-Sudo für `systemctl * pocket-claude` (kein PW-Prompt)
#
#  Konfiguration via Env-Variablen (z.B. in ~/.zshrc):
#      export POCKET_CLAUDE_TARGET="me@my-mini-pc"
#      export POCKET_CLAUDE_PUBLIC_URL="https://my-host.tailnet-name.ts.net"
#
#  Aufruf (im Mac-Repo-Root):
#      ./deploy/push-to-Mini-PC.sh
#  oder Doppelklick auf "Update Mini-PC.command".
# =============================================================================
set -euo pipefail

# Defaults aus Env, sonst Fehlermeldung wenn nichts konfiguriert.
TARGET="${POCKET_CLAUDE_TARGET:-${TARGET:-}}"
REMOTE_PATH="${REMOTE_PATH:-/opt/pocket-claude}"
PUBLIC_URL="${POCKET_CLAUDE_PUBLIC_URL:-${PUBLIC_URL:-}}"

if [[ -z "$TARGET" ]] || [[ -z "$PUBLIC_URL" ]]; then
    cat >&2 <<EOF
Fehler: Konfiguration fehlt. Setze einmalig in ~/.zshrc:
    export POCKET_CLAUDE_TARGET="<user>@<host>"           # z.B. me@minipc.local
    export POCKET_CLAUDE_PUBLIC_URL="https://<fqdn>"      # z.B. https://my-host.tailnet.ts.net

Dann neue Shell öffnen und Skript erneut starten.
EOF
    exit 1
fi

c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
step()     { echo; c_blue "==> $*"; }

LOCAL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LOCAL_DIR"

step "Code → $TARGET:$REMOTE_PATH"

# Vorher: hat sich requirements.txt geändert?
REMOTE_REQ_HASH="$(ssh -o BatchMode=yes "$TARGET" \
    "sha256sum $REMOTE_PATH/requirements.txt 2>/dev/null | cut -d' ' -f1" || echo "")"
LOCAL_REQ_HASH="$(shasum -a 256 requirements.txt | cut -d' ' -f1)"

# rsync mit klaren Excludes:
#   data/        → SQLite + Uploads bleiben SERVER-SIDE (Datenbank-Autorität!)
#   .env         → SERVER hat eigene .env mit eigenem SERVER_TOKEN
#   .venv        → Python-Binaries sind plattform-spezifisch (macOS ≠ Linux)
#   .git/        → Git-History brauchen wir auf Mini-PC nicht
#   __pycache__/ → wird vom Server-Python frisch generiert
#   *.pyc        → s.o.
#   .DS_Store    → macOS-Junk
rsync -az --delete \
    --exclude='.venv' \
    --exclude='data' \
    --exclude='.env' \
    --exclude='.git' \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    --exclude='.DS_Store' \
    --exclude='Pocket Claude Server.command' \
    --exclude='Update Dependencies.command' \
    ./ "$TARGET:$REMOTE_PATH/" 2>&1 | tail -3

c_green "    Code synchron."

# Falls requirements.txt sich geändert hat: pip install im Mini-PC-venv
if [[ "$LOCAL_REQ_HASH" != "$REMOTE_REQ_HASH" ]]; then
    step "requirements.txt geändert → pip install im Mini-PC-venv"
    ssh "$TARGET" "sudo -u pocket-claude $REMOTE_PATH/.venv/bin/pip install --quiet --upgrade -r $REMOTE_PATH/requirements.txt" 2>&1 | head -10
    c_green "    Dependencies aktualisiert."
fi

step "systemctl restart pocket-claude"
ssh "$TARGET" 'sudo -n systemctl restart pocket-claude'
sleep 2

step "Health-Check über öffentliche URL"
if curl -fsS --max-time 8 "$PUBLIC_URL/health" >/dev/null 2>&1; then
    HEALTH="$(curl -fsS "$PUBLIC_URL/health")"
    c_green "    ✓ $PUBLIC_URL → $HEALTH"
else
    c_red "    ⚠ Health-Endpoint antwortet nicht!"
    echo "       Logs (letzte 20 Zeilen):"
    ssh "$TARGET" 'sudo -n journalctl -u pocket-claude -n 20 --no-pager' | sed 's/^/         /'
    exit 1
fi

echo
c_green "✅ Deploy fertig — Mini-PC läuft mit aktuellem Code."
echo
