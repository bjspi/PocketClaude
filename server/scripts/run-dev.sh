#!/usr/bin/env bash
# Pocket Claude — Lokal starten (für Entwicklung auf dem Mac)
set -euo pipefail

cd "$(dirname "$0")/.."

# Claude-Code-CLI muss vorhanden sein
if ! command -v claude >/dev/null 2>&1; then
    echo "==> 'claude' nicht im PATH gefunden."
    echo "    Installiere Claude Code:  npm install -g @anthropic-ai/claude-code"
    echo "    Und melde Dich an:        claude login"
    exit 1
fi
echo "==> Claude-CLI: $(command -v claude)"

if [[ ! -f .venv/bin/activate ]]; then
    if [[ -d .venv ]]; then
        echo "==> kaputtes .venv gefunden, lösche und lege neu an..."
        rm -rf .venv
    else
        echo "==> venv anlegen..."
    fi
    python3 -m venv .venv
fi

# shellcheck disable=SC1091
source .venv/bin/activate

# Dependencies abgleichen — pip install -r ist idempotent + nutzt Cache.
# Vergleich von requirements.txt mit letztem Install: läuft nur durch wenn nötig.
REQUIREMENTS_HASH_FILE=".venv/.requirements.sha"
CURRENT_HASH=$(shasum -a 256 requirements.txt | cut -d' ' -f1)
LAST_HASH=$([ -f "$REQUIREMENTS_HASH_FILE" ] && cat "$REQUIREMENTS_HASH_FILE" || echo "")
if [[ "$CURRENT_HASH" != "$LAST_HASH" ]] || ! python -c "import fastapi" 2>/dev/null; then
    echo "==> Dependencies installieren/aktualisieren..."
    pip install --upgrade pip
    pip install -r requirements.txt
    echo "$CURRENT_HASH" > "$REQUIREMENTS_HASH_FILE"
fi

if [[ ! -f .env ]]; then
    echo "==> .env aus .env.example anlegen..."
    cp .env.example .env
    TOKEN=$(python -c "import secrets; print(secrets.token_urlsafe(32))")
    if [[ "$(uname)" == "Darwin" ]]; then
        sed -i '' "s|^SERVER_TOKEN=.*|SERVER_TOKEN=$TOKEN|" .env
    else
        sed -i "s|^SERVER_TOKEN=.*|SERVER_TOKEN=$TOKEN|" .env
    fi
    echo ""
    echo "    SERVER_TOKEN generiert: $TOKEN"
    echo "    → In der Android-App unter Einstellungen → Bearer-Token eintragen."
    echo ""
fi

echo "==> Pocket Claude startet auf http://localhost:8787 ..."
exec python -m pocket_claude
