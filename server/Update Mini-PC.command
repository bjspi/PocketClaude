#!/usr/bin/env bash
# Doppelklick: pusht den aktuellen Server-Code an Deinen Mini-PC + restart.
#
# Erstkonfiguration einmalig in ~/.zshrc:
#   export POCKET_CLAUDE_TARGET="<user>@<host>"           # z.B. me@minipc.local
#   export POCKET_CLAUDE_PUBLIC_URL="https://<fqdn>"      # öffentliche Tunnel-URL
#
# Beim Doppelklick lädt macOS die User-Shell — das `.zshrc` wird gesourct
# und die Variablen sind verfügbar. Falls nicht: hier vor dem bash-Aufruf
# manuell setzen.
set -e
cd "$(dirname "$0")"

# Falls Du die Variablen nicht in .zshrc haben willst, hier hardcoded
# überschreiben (für eine einzelne Maschine völlig OK):
# export POCKET_CLAUDE_TARGET="me@minipc.local"
# export POCKET_CLAUDE_PUBLIC_URL="https://my-host.tailnet.ts.net"

bash ./deploy/push-to-minipc.sh
echo
read -rp "Drücke Enter zum Schließen…" _
