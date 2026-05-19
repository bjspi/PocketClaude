#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude — Migration vom Mac (oder anderem Quell-Host) auf den Mini-PC
# =============================================================================
#
#  Was übertragen wird:
#      - data/pocket_claude.db          (SQLite mit allen Chats + User + Sessions)
#      - data/uploads/                  (Attachments)
#      - data/google_tts_credentials.json (Cloud-TTS-Service-Account, falls da)
#      - .env                            (Server-Token + Modell-Setting)
#
#  Was NICHT übertragen wird:
#      - ~/.claude/credentials.json (claude-CLI-Login) — der muss auf dem
#        Ziel-Mini-PC neu gemacht werden (`claude login` als pocket-claude-User).
#        Grund: claude-CLI-Sessions sind oft an Geräte-Fingerprints gebunden.
#      - Cloudflare-Tunnel-Credentials — die werden via setup-cloudflare-tunnel.sh
#        auf dem Ziel neu angelegt. (Vorteil: Mac und Mini-PC können beide
#        eigene Tunnel haben, Failover-fähig.)
#
#  Aufruf vom Mac aus (im Quell-Repo-Root):
#      ./deploy/migrate-to-server.sh me@mini-pc.local
#  oder mit explizitem Ziel-Pfad:
#      ./deploy/migrate-to-server.sh me@10.0.0.42:/opt/pocket-claude
#
#  Voraussetzung:
#      - Auf dem Mini-PC läuft schon `install-linux.sh` (= /opt/pocket-claude existiert,
#        Service-User `pocket-claude` ist da, der Service ist enabled).
#      - SSH-Zugang funktioniert (`ssh user@host` lässt Dich rein).
# =============================================================================
set -euo pipefail

c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
step()     { echo; c_blue "==> $*"; }

TARGET=""
if [[ $# -ge 1 ]]; then
    TARGET="$1"
fi

# Wenn kein Target übergeben: versuche, ein passendes via Tailscale zu finden.
# Wir listen alle Linux-Knoten aus `tailscale status` zur Auswahl.
if [[ -z "$TARGET" ]] && command -v tailscale >/dev/null 2>&1; then
    echo "Kein SSH-Ziel angegeben — suche im Tailnet…"
    TS_CANDIDATES="$(tailscale status --json 2>/dev/null | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    for p in (d.get("Peer") or {}).values():
        if p.get("OS","").lower() in ("linux","unknown"):
            dns = (p.get("DNSName","") or "").rstrip(".")
            host = p.get("HostName","")
            if dns and "Online" in str(p.get("Online", "")) or p.get("Online", False):
                print(f"{host}\t{dns}")
except Exception:
    pass
' 2>/dev/null)"
    if [[ -n "$TS_CANDIDATES" ]]; then
        echo
        echo "Tailnet-Knoten:"
        echo "$TS_CANDIDATES" | awk -F'\t' '{printf "    %d) %s  (%s)\n", NR, $1, $2}'
        echo
        read -rp "SSH-User (default: aktueller User '$USER'): " SSH_USER
        SSH_USER="${SSH_USER:-$USER}"
        # Default: erster Treffer
        DEFAULT_HOST="$(echo "$TS_CANDIDATES" | head -1 | awk -F'\t' '{print $2}')"
        read -rp "SSH-Ziel-Host (default: $DEFAULT_HOST): " SSH_HOST
        SSH_HOST="${SSH_HOST:-$DEFAULT_HOST}"
        TARGET="$SSH_USER@$SSH_HOST"
    fi
fi

if [[ -z "$TARGET" ]]; then
    c_red "Usage: $0 <user@host[:remote-path]>"
    echo
    echo "Beispiele:"
    echo "  $0 me@minipc"
    echo "  $0 me@minipc.tailnet-name.ts.net"
    echo "  $0 me@10.0.0.42:/opt/pocket-claude"
    exit 1
fi
REMOTE_PATH="/opt/pocket-claude"
if [[ "$TARGET" == *":"* ]]; then
    REMOTE_PATH="${TARGET#*:}"
    TARGET="${TARGET%%:*}"
fi

LOCAL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="$LOCAL_DIR/data"
ENV_FILE="$LOCAL_DIR/.env"

if [[ ! -d "$DATA_DIR" ]]; then
    c_red "data/ nicht gefunden in $LOCAL_DIR — bist Du im richtigen Repo?"
    exit 1
fi

# ---------------------------------------------------------------- Pre-Flight
step "Pre-Flight-Check: SSH zum Ziel ($TARGET)"
if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "$TARGET" 'echo OK' >/dev/null 2>&1; then
    c_red "    SSH-Zugang fehlgeschlagen."
    echo "    Tipp: 'ssh $TARGET' manuell testen. Ggf. SSH-Key kopieren mit:"
    echo "        ssh-copy-id $TARGET"
    exit 1
fi
c_green "    SSH OK."

step "Pre-Flight: Pocket Claude installiert auf Ziel?"
if ! ssh "$TARGET" "test -d $REMOTE_PATH" 2>/dev/null; then
    c_red "    $REMOTE_PATH existiert auf dem Ziel nicht."
    echo "    Bitte zuerst auf dem Mini-PC ausführen:"
    echo "        sudo bash $REMOTE_PATH/deploy/install-linux.sh"
    echo "    (oder das install-linux.sh Skript woanders ablegen und laufen lassen)"
    exit 1
fi
c_green "    Installation gefunden."

# ---------------------------------------------------------------- Source-Server stoppen?
LOCAL_RUNNING=false
if pgrep -f "python.*pocket_claude" >/dev/null 2>&1 || \
   pgrep -f "pocket_claude_manager" >/dev/null 2>&1; then
    LOCAL_RUNNING=true
    echo
    c_yellow "Auf diesem Host läuft noch ein Pocket-Claude-Prozess."
    echo "Empfehlung: vor der Migration den Mac-Server stoppen, damit die SQLite-DB"
    echo "in einem sauberen Zustand ist (keine offene Schreib-Transaktion)."
    echo
    read -rp "Mac-Server jetzt stoppen? [Y/n] " yn
    if [[ ! "$yn" =~ ^[Nn]$ ]]; then
        pkill -f "python.*pocket_claude" 2>/dev/null || true
        pkill -f "pocket_claude_manager" 2>/dev/null || true
        sleep 1
        c_green "    Server gestoppt."
    fi
fi

# ---------------------------------------------------------------- Bundle erstellen
step "Daten-Bundle erstellen"
TMP_BUNDLE="$(mktemp /tmp/pocket-claude-migrate-XXXXXX.tar.gz)"
trap "rm -f '$TMP_BUNDLE'" EXIT

INCLUDE=()
INCLUDE+=("data/pocket_claude.db")
[[ -d "$DATA_DIR/uploads" ]] && INCLUDE+=("data/uploads")
[[ -f "$DATA_DIR/google_tts_credentials.json" ]] && INCLUDE+=("data/google_tts_credentials.json")
[[ -f "$ENV_FILE" ]] && INCLUDE+=(".env")

if [[ ${#INCLUDE[@]} -eq 0 ]]; then
    c_red "    Nichts zu migrieren — data/ ist leer und keine .env vorhanden."
    exit 1
fi

# `tar -C` damit die Pfade relativ bleiben, Dereferenzierung von Symlinks
# nicht nötig (alles real). gzip-9 weil's einmalig läuft.
tar -czf "$TMP_BUNDLE" -C "$LOCAL_DIR" "${INCLUDE[@]}" 2>/dev/null
BUNDLE_SIZE=$(du -h "$TMP_BUNDLE" | awk '{print $1}')
c_green "    Bundle: $BUNDLE_SIZE — ${#INCLUDE[@]} Pfade"
for p in "${INCLUDE[@]}"; do echo "        · $p"; done

# ---------------------------------------------------------------- Hochladen
step "Hochladen via scp"
REMOTE_TMP="/tmp/pocket-claude-migrate.tar.gz"
scp -q "$TMP_BUNDLE" "$TARGET:$REMOTE_TMP"
c_green "    Bundle auf dem Ziel-Host: $REMOTE_TMP"

# ---------------------------------------------------------------- Ziel-Vorgang
step "Auf dem Ziel: Backup, Entpacken, Ownership, Restart"

# Wir wickeln das alles in einem einzigen Remote-Bash ab — atomarer.
# `sudo` brauchen wir, weil /opt/pocket-claude pocket-claude:pocket-claude gehört.
ssh "$TARGET" bash <<REMOTE_EOF
set -euo pipefail
REMOTE_PATH='$REMOTE_PATH'
REMOTE_TMP='$REMOTE_TMP'
SERVICE_NAME='pocket-claude'

# 1. Service stoppen damit die DB nicht in-flight überschrieben wird
if sudo systemctl is-active --quiet "\$SERVICE_NAME"; then
    echo "    -> systemd-Service stoppen"
    sudo systemctl stop "\$SERVICE_NAME"
fi

# 2. Backup des aktuellen Stands (falls da)
TS=\$(date +%Y%m%d-%H%M%S)
BACKUP_DIR="\$REMOTE_PATH/data/.pre-migration-backup-\$TS"
if [[ -f "\$REMOTE_PATH/data/pocket_claude.db" ]]; then
    echo "    -> Bestehende Daten sichern nach \$BACKUP_DIR"
    sudo mkdir -p "\$BACKUP_DIR"
    sudo cp -a "\$REMOTE_PATH/data/pocket_claude.db" "\$BACKUP_DIR/" 2>/dev/null || true
    [[ -d "\$REMOTE_PATH/data/uploads" ]] && \\
        sudo cp -a "\$REMOTE_PATH/data/uploads" "\$BACKUP_DIR/" 2>/dev/null || true
    [[ -f "\$REMOTE_PATH/.env" ]] && \\
        sudo cp -a "\$REMOTE_PATH/.env" "\$BACKUP_DIR/" 2>/dev/null || true
fi

# 3. Bundle entpacken
echo "    -> Entpacken nach \$REMOTE_PATH"
sudo tar -xzf "\$REMOTE_TMP" -C "\$REMOTE_PATH"
sudo chown -R pocket-claude:pocket-claude "\$REMOTE_PATH/data" "\$REMOTE_PATH/.env" 2>/dev/null || true
sudo chmod 600 "\$REMOTE_PATH/.env" 2>/dev/null || true
[[ -f "\$REMOTE_PATH/data/google_tts_credentials.json" ]] && \\
    sudo chmod 600 "\$REMOTE_PATH/data/google_tts_credentials.json"

# 4. Bundle aufräumen
rm -f "\$REMOTE_TMP"

# 5. Service wieder starten
echo "    -> systemd-Service starten"
sudo systemctl start "\$SERVICE_NAME"
sleep 2
if sudo systemctl is-active --quiet "\$SERVICE_NAME"; then
    echo "    ✓ Service läuft."
else
    echo "    ⚠ Service nicht aktiv — Logs auf dem Ziel checken:"
    echo "         journalctl -u \$SERVICE_NAME -n 50"
fi
REMOTE_EOF

echo
echo "============================================================"
c_green "✅ Migration abgeschlossen."
echo "============================================================"
echo
echo "Auf dem Mini-PC läuft Pocket Claude jetzt mit Deinen Daten."
echo
echo "Verifizieren:"
echo "  ssh $TARGET 'sudo systemctl status pocket-claude'"
echo "  ssh $TARGET 'journalctl -u pocket-claude -n 30'"
echo
echo "Erinnerung: für den ersten Start auf dem Mini-PC ist EVTL. noch nötig:"
echo "  1. claude-CLI-Login (falls noch nicht):"
echo "       ssh $TARGET 'sudo -u pocket-claude -H claude login'"
echo "  2. Tunnel einrichten (falls noch nicht):"
echo "       ssh $TARGET 'sudo bash $REMOTE_PATH/deploy/setup-cloudflare-tunnel.sh'"
echo
c_yellow "Hinweis: Der Mac-Server ist (wenn Du oben Y gewählt hast) gestoppt. Du kannst"
c_yellow "ihn jetzt deaktivieren oder ganz deinstallieren — Mini-PC übernimmt die Hosting-"
c_yellow "Rolle. App-Seite: Server-URL in den Profil-Einstellungen auf die neue Tunnel-URL"
c_yellow "umstellen."
