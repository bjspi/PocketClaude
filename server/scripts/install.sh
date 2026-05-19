#!/usr/bin/env bash
# =============================================================================
#  Legacy-Wrapper — verweist auf den neuen Production-Installer.
# =============================================================================
#
#  Die alte Single-Script-Installation ist durch den moderneren Deploy-
#  Pfad ersetzt worden. Volle Doku: deploy/README.md
#
#  Dieses Skript leitet den Aufruf an den neuen Installer weiter, damit
#  alte Anleitungen + Aliase weiterhin funktionieren.
# =============================================================================
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec sudo bash "$DIR/deploy/install-linux.sh" "$@"
