#!/usr/bin/env bash
# Installer-Skript — wird vom Doppelklick-Befehl oder vom launchd-Watcher aufgerufen.
# Sucht das jüngste APK in sandbox-builds/ und installiert es via adb auf das
# zuerst gefundene angeschlossene Gerät.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DROP_DIR="$PROJECT_DIR/sandbox-builds"
LOG="$PROJECT_DIR/sandbox-builds/install.log"

mkdir -p "$DROP_DIR"

# Find adb. ANDROID_HOME may be set in .zshrc but launchd doesn't load it,
# so we probe typical install paths.
ADB=""
for candidate in \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/../../../../sdk/platform-tools/adb" \
    "$(command -v adb 2>/dev/null || true)"; do
    if [ -x "$candidate" ]; then ADB="$candidate"; break; fi
done

ts() { date +"%Y-%m-%d %H:%M:%S"; }

if [ -z "$ADB" ]; then
    echo "[$(ts)] adb nicht gefunden (Android-SDK-Pfad unklar)" >> "$LOG"
    if [ -t 0 ]; then
        echo "adb nicht gefunden. ANDROID_HOME=$ANDROID_HOME?"
    fi
    exit 1
fi

# Neueste APK suchen
APK="$(ls -t "$DROP_DIR"/app-debug-*.apk 2>/dev/null | head -1 || true)"
if [ -z "$APK" ]; then
    echo "[$(ts)] keine APK in $DROP_DIR" >> "$LOG"
    exit 0
fi

# Schon installiert? (gleicher Timestamp im Marker)
LAST_INSTALLED_MARKER="$DROP_DIR/.last-installed"
APK_BASE="$(basename "$APK")"
if [ -f "$LAST_INSTALLED_MARKER" ] && grep -qx "$APK_BASE" "$LAST_INSTALLED_MARKER"; then
    # Idempotent: schon erledigt
    exit 0
fi

# Geräte da?
DEVICES="$("$ADB" devices | tail -n +2 | awk '$2=="device" {print $1}')"
if [ -z "$DEVICES" ]; then
    echo "[$(ts)] $APK_BASE: kein Gerät verbunden — Install übersprungen" >> "$LOG"
    if [ -t 0 ]; then
        echo "Kein Android-Gerät angeschlossen. Verbinde Telefon mit USB-Debugging und versuch's nochmal."
    fi
    exit 0
fi

echo "[$(ts)] Installiere $APK_BASE auf $DEVICES" >> "$LOG"
"$ADB" install -r "$APK" >> "$LOG" 2>&1 || {
    echo "[$(ts)] Install fehlgeschlagen" >> "$LOG"
    exit 1
}

# App auch gleich starten
"$ADB" shell am start -n de.smartzone.pocketclaude/.MainActivity >> "$LOG" 2>&1 || true

echo "$APK_BASE" > "$LAST_INSTALLED_MARKER"
echo "[$(ts)] OK: $APK_BASE" >> "$LOG"

# Bei interaktivem Aufruf: Erfolg melden
if [ -t 0 ]; then
    echo "✓ $APK_BASE installiert auf $DEVICES"
fi
