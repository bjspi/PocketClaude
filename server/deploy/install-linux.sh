#!/usr/bin/env bash
# =============================================================================
#  Pocket Claude — Linux One-Shot Installer (Ubuntu/Debian + Fedora/RHEL)
# =============================================================================
#
#  Usage:
#      sudo bash deploy/install-linux.sh
#
#  What it does:
#      1. System packages (python3, venv, curl, nodejs for claude-cli)
#      2. Create service user `pocket-claude`
#      3. Copy repo to /opt/pocket-claude (or update it)
#      4. Create Python venv + install requirements
#      5. Re-use existing `claude` CLI or install it via npm
#      6. Generate .env with a random SERVER_TOKEN
#      7. Install systemd service + enable + start
#      8. Print tunnel-setup hint
#
#  Idempotent — can run multiple times, no re-setup needed.
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------- Configuration
INSTALL_DIR="${INSTALL_DIR:-/opt/pocket-claude}"
SERVICE_USER="${SERVICE_USER:-pocket-claude}"
SERVICE_NAME="pocket-claude"
REPO_URL="${REPO_URL:-https://github.com/joshtech90/PocketClaude.git}"

# Source directory: either the repo where the script lives (dev path), or
# a fresh clone from GitHub (fresh-install path).
SOURCE_DIR="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)"
TMP_CLONE=""
cleanup() {
    if [[ -n "$TMP_CLONE" && -d "$TMP_CLONE" ]]; then
        rm -rf "$TMP_CLONE"
    fi
}
trap cleanup EXIT

# ---------------------------------------------------------------- Pretty-Print
c_blue()   { printf '\033[1;34m%s\033[0m\n' "$*"; }
c_green()  { printf '\033[1;32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[1;33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
step()     { echo; c_blue "==> $*"; }

read_prompt() {
    local prompt="$1"
    local var_name="$2"
    if [[ -r /dev/tty ]]; then
        read -r -p "$prompt" "$var_name" < /dev/tty
    elif [[ -t 0 ]]; then
        read -r -p "$prompt" "$var_name"
    else
        return 1
    fi
}

prompt_yes_no() {
    local prompt="$1"
    local default="${2:-y}"
    local answer
    if [[ "$default" =~ ^[Yy]$ ]]; then
        read_prompt "$prompt [Y/n] " answer || answer="y"
        answer="${answer:-y}"
    else
        read_prompt "$prompt [y/N] " answer || answer="n"
        answer="${answer:-n}"
    fi
    [[ "$answer" =~ ^[Yy]$ ]]
}

prompt_access_type() {
    local answer
    if [[ -n "${ACCESS_TYPE:-}" ]]; then
        answer="$ACCESS_TYPE"
    elif [[ -r /dev/tty || -t 0 ]]; then
        echo >&2
        c_blue "Access type" >&2
        echo "  1) tailscale-internal  private; Android needs Tailscale VPN app" >&2
        echo "  2) tailscale-funnel    public internet via *.ts.net" >&2
        echo "  3) cloudflare-tunnel   public hostname; requires a Cloudflare-managed domain" >&2
        echo "  4) skip                install server only" >&2
        read_prompt "Choose access type [1]: " answer || answer="1"
        answer="${answer:-1}"
    else
        answer="1"
    fi
    case "$answer" in
        1|tailscale-internal|internal|tailscale) printf '%s\n' "tailscale-internal" ;;
        2|tailscale-funnel|funnel) printf '%s\n' "tailscale-funnel" ;;
        3|cloudflare-tunnel|cloudflare|cf) printf '%s\n' "cloudflare-tunnel" ;;
        4|skip|none) printf '%s\n' "skip" ;;
        *) c_yellow "Unknown access type '$answer' — using tailscale-internal." >&2; printf '%s\n' "tailscale-internal" ;;
    esac
}

set_env_value() {
    local file="$1"
    local key="$2"
    local value="$3"
    if grep -qE "^${key}=" "$file"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$file"
    elif grep -qE "^# ${key}=" "$file"; then
        sed -i "s|^# ${key}=.*|${key}=${value}|" "$file"
    else
        printf '\n%s=%s\n' "$key" "$value" >> "$file"
    fi
}

resolve_server_source() {
    local candidate="$1"
    if [[ -d "$candidate/pocket_claude" && -f "$candidate/requirements.txt" ]]; then
        printf '%s\n' "$candidate"
        return 0
    fi
    if [[ -d "$candidate/server/pocket_claude" && -f "$candidate/server/requirements.txt" ]]; then
        printf '%s\n' "$candidate/server"
        return 0
    fi
    return 1
}

# ---------------------------------------------------------------- Root-Check
if [[ $EUID -ne 0 ]]; then
    c_red "Please run with sudo: sudo bash $0"
    exit 1
fi

# ---------------------------------------------------------------- Interactive choices
WEBUI_ENABLED="${WEBUI_ENABLED:-}"
if [[ -z "$WEBUI_ENABLED" ]]; then
    if prompt_yes_no "Enable the built-in browser Web UI?" "y"; then
        WEBUI_ENABLED=1
    else
        WEBUI_ENABLED=0
    fi
fi
case "${WEBUI_ENABLED,,}" in
    1|true|yes|y|on) WEBUI_ENABLED=1 ;;
    *) WEBUI_ENABLED=0 ;;
esac

ACCESS_TYPE="$(prompt_access_type)"
c_green "Selected Web UI: $([[ "$WEBUI_ENABLED" == "1" ]] && echo enabled || echo disabled)"
c_green "Selected access type: $ACCESS_TYPE"

# ---------------------------------------------------------------- OS-Detection
if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    source /etc/os-release
    OS_ID="${ID:-unknown}"
    OS_LIKE="${ID_LIKE:-}"
else
    c_red "/etc/os-release not found — unknown OS."
    exit 1
fi

is_debian=false
is_redhat=false
case "$OS_ID $OS_LIKE" in
    *debian*|*ubuntu*) is_debian=true ;;
    *fedora*|*rhel*|*centos*) is_redhat=true ;;
esac

if ! $is_debian && ! $is_redhat; then
    c_yellow "Warning: OS '$OS_ID' is not officially tested. Trying as Debian-like anyway."
    is_debian=true
fi

# ---------------------------------------------------------------- Pre-check: is `claude` already usable?
# If the operator already has a working Claude CLI on PATH (very common —
# many users install it manually before running our installer), we can skip
# the whole Node + npm dance below. Saves install time and avoids version
# collisions with whatever Node version they're already running.
claude_already_present=false
claude_binary_path=""
if claude_binary_path="$(type -P claude 2>/dev/null)" && [[ -n "$claude_binary_path" ]]; then
    if claude_version_output="$("$claude_binary_path" --version 2>&1)" && [[ -n "$claude_version_output" ]]; then
        claude_already_present=true
        c_green "Detected existing Claude CLI: $claude_binary_path"
        c_green "Version: $claude_version_output"
        c_green "Skipping Node.js + npm + claude-cli install."
    fi
fi

# ---------------------------------------------------------------- System packages
step "Installing system packages"
if $is_debian; then
    apt-get update -qq
    apt-get install -y --no-install-recommends \
        python3 python3-venv python3-pip \
        curl ca-certificates git rsync
    if ! $claude_already_present; then
        # nodejs/npm separately: if a version is already installed (e.g. via
        # NodeSource), don't overwrite — the NodeSource packages already bundle
        # `npm`, an additional `apt install npm` would collide.
        if ! command -v node >/dev/null 2>&1; then
            apt-get install -y --no-install-recommends nodejs npm
        elif ! command -v npm >/dev/null 2>&1; then
            apt-get install -y --no-install-recommends npm
        fi
    fi
elif $is_redhat; then
    dnf install -y python3 python3-pip python3-virtualenv \
        curl ca-certificates git rsync
    if ! $claude_already_present; then
        if ! command -v node >/dev/null 2>&1; then
            dnf install -y nodejs npm
        elif ! command -v npm >/dev/null 2>&1; then
            dnf install -y npm
        fi
    fi
fi

# Check Node version — claude CLI requires >= 18. Only relevant if we're going
# to install the CLI ourselves; if claude is already on PATH, the operator's
# existing Node setup is by definition fine.
if ! $claude_already_present; then
    node_major="$(node -v 2>/dev/null | sed 's/^v//;s/\..*//' || echo 0)"
    if [[ "$node_major" -lt 18 ]]; then
        c_yellow "Node $node_major is too old (claude CLI requires >=18). Installing NodeSource Node 20..."
        if $is_debian; then
            curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
            apt-get install -y nodejs
        else
            curl -fsSL https://rpm.nodesource.com/setup_20.x | bash -
            dnf install -y nodejs
        fi
    fi
fi

# ---------------------------------------------------------------- Service user
step "Create service user '$SERVICE_USER' (if missing)"
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    useradd --system --create-home --shell /bin/bash "$SERVICE_USER"
    c_green "    User created."
else
    c_green "    User already exists."
fi

# ---------------------------------------------------------------- Code deployment
step "Deploying code to $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
DEPLOY_SOURCE=""
if DEPLOY_SOURCE="$(resolve_server_source "$SOURCE_DIR")"; then
    c_green "    Local repo detected — copying $DEPLOY_SOURCE -> $INSTALL_DIR"
elif command -v git >/dev/null 2>&1; then
    TMP_CLONE="$(mktemp -d)"
    c_green "    Cloning repo from $REPO_URL"
    git clone "$REPO_URL" "$TMP_CLONE"
    if ! DEPLOY_SOURCE="$(resolve_server_source "$TMP_CLONE")"; then
        c_red "Cloned repository does not look like PocketClaude."
        c_red "Expected either ./server/pocket_claude or ./pocket_claude with requirements.txt."
        exit 1
    fi
else
    c_red "Neither local repo nor git available — cannot deploy."
    exit 1
fi

# `--exclude .venv` so we don't copy a local venv with wrong binaries.
# `--exclude data` so a running server doesn't lose its SQLite DB.
# `--exclude .git` — we don't need repo history on the server.
rsync -a --delete \
    --exclude '.venv' \
    --exclude 'data' \
    --exclude '.git' \
    --exclude '__pycache__' \
    --exclude '*.pyc' \
    "$DEPLOY_SOURCE/" "$INSTALL_DIR/"

# Create data/ folder if missing (for fresh install or first start)
mkdir -p "$INSTALL_DIR/data/uploads"

chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

# ---------------------------------------------------------------- Python venv
step "Python venv + dependencies"
if [[ ! -d "$INSTALL_DIR/.venv" ]]; then
    sudo -u "$SERVICE_USER" python3 -m venv "$INSTALL_DIR/.venv"
fi
sudo -u "$SERVICE_USER" "$INSTALL_DIR/.venv/bin/pip" install --quiet --upgrade pip
sudo -u "$SERVICE_USER" "$INSTALL_DIR/.venv/bin/pip" install --quiet -r "$INSTALL_DIR/requirements.txt"
c_green "    Dependencies installed."

# ---------------------------------------------------------------- Claude CLI
step "Claude CLI"
if $claude_already_present; then
    c_green "    Re-using existing Claude CLI at $claude_binary_path"
    c_green "    Version: $claude_version_output"
elif claude_binary_path="$(type -P claude 2>/dev/null)" && [[ -n "$claude_binary_path" ]]; then
    # Could only happen if claude appeared on PATH between the pre-check and
    # here (very unlikely). Still: don't reinstall.
    c_green "    Claude CLI already present ($("$claude_binary_path" --version 2>&1 | head -1))."
else
    npm install -g @anthropic-ai/claude-code
    claude_binary_path="$(type -P claude 2>/dev/null || true)"
    c_green "    Claude CLI installed."
fi

# Verify the service user can actually run `claude`. The npm-global path
# (/usr/local/bin) is usually on the service user's PATH, but if the operator
# installed Claude into ~/.npm-global or a non-standard prefix, the service
# user won't see it. Surface this immediately rather than at first request.
if ! sudo -u "$SERVICE_USER" -H bash -lc 'command -v claude' >/dev/null 2>&1; then
    if [[ -n "$claude_binary_path" ]] && sudo -u "$SERVICE_USER" -H test -x "$claude_binary_path" 2>/dev/null; then
        c_yellow "Note: 'claude' is not on the service user's PATH."
        c_yellow "The installer will set CLAUDE_BINARY=$claude_binary_path in $INSTALL_DIR/.env."
    else
        c_yellow "Note: 'claude' is not on the service user's PATH."
        c_yellow "Either move the binary to /usr/local/bin, or set CLAUDE_BINARY in $INSTALL_DIR/.env"
        c_yellow "to its absolute path (e.g. CLAUDE_BINARY=$(command -v claude))."
    fi
fi

# ---------------------------------------------------------------- .env
step "Preparing .env file"
if [[ ! -f "$INSTALL_DIR/.env" ]]; then
    cp "$INSTALL_DIR/.env.example" "$INSTALL_DIR/.env"
    # Generate a random server token (not directly used for multi-user auth,
    # but pydantic-settings requires a min-length-8 value).
    TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
    sed -i "s|^SERVER_TOKEN=.*|SERVER_TOKEN=$TOKEN|" "$INSTALL_DIR/.env"
    chown "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/.env"
    chmod 600 "$INSTALL_DIR/.env"
    c_green "    Created new .env with a random SERVER_TOKEN."
else
    c_green "    .env already exists — leaving it unchanged."
fi

set_env_value "$INSTALL_DIR/.env" "ENABLE_WEBUI" "$WEBUI_ENABLED"
if [[ -n "$claude_binary_path" ]] && ! sudo -u "$SERVICE_USER" -H bash -lc 'command -v claude' >/dev/null 2>&1; then
    if sudo -u "$SERVICE_USER" -H test -x "$claude_binary_path" 2>/dev/null; then
        set_env_value "$INSTALL_DIR/.env" "CLAUDE_BINARY" "$claude_binary_path"
        c_green "    CLAUDE_BINARY=$claude_binary_path"
    fi
fi
case "$ACCESS_TYPE" in
    tailscale-internal|tailscale-funnel|cloudflare-tunnel)
        set_env_value "$INSTALL_DIR/.env" "SERVER_HOST" "127.0.0.1"
        ;;
esac
chown "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/.env"
chmod 600 "$INSTALL_DIR/.env"
c_green "    ENABLE_WEBUI=$WEBUI_ENABLED"

# ---------------------------------------------------------------- systemd unit
step "Installing systemd service"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
sed -e "s|@PROJECT_DIR@|$INSTALL_DIR|g" -e "s|@SERVICE_USER@|$SERVICE_USER|g" \
    "$INSTALL_DIR/deploy/systemd/pocket-claude.service" > "$SERVICE_FILE"

systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null
c_green "    Service enabled (starts automatically on boot)."

# ---------------------------------------------------------------- First start
step "Starting server"
# Only start if `claude` is already logged in. Otherwise we end up in a crash
# loop until the operator has run `claude login` as the pocket-claude user.
if sudo -u "$SERVICE_USER" -H bash -c '[[ -f ~/.claude/credentials.json ]] || [[ -f ~/.config/claude/credentials.json ]]' 2>/dev/null; then
    systemctl restart "$SERVICE_NAME"
    sleep 2
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        c_green "    ✓ Service running."
    else
        c_yellow "    Service not active — see logs: journalctl -u $SERVICE_NAME -n 50"
    fi
else
    c_yellow "    Service NOT YET started — claude login is missing."
    if [[ -f /root/.claude/credentials.json || -f /root/.config/claude/credentials.json ]]; then
        c_yellow "    Root appears to have Claude credentials, but the service runs as '$SERVICE_USER'."
    fi
    c_yellow "    Run once:"
    c_yellow "      sudo -u $SERVICE_USER -H claude login"
fi

# ---------------------------------------------------------------- Access setup
step "Access setup ($ACCESS_TYPE)"
case "$ACCESS_TYPE" in
    tailscale-internal)
        bash "$INSTALL_DIR/deploy/setup-tailscale-internal.sh" || \
            c_yellow "    Tailscale internal setup did not complete. Re-run it later with: sudo bash $INSTALL_DIR/deploy/setup-tailscale-internal.sh"
        ;;
    tailscale-funnel)
        c_yellow "    Tailscale Funnel publishes Pocket Claude to the public internet."
        bash "$INSTALL_DIR/deploy/setup-tailscale-funnel.sh" || \
            c_yellow "    Tailscale Funnel setup did not complete. Re-run it later with: sudo bash $INSTALL_DIR/deploy/setup-tailscale-funnel.sh"
        ;;
    cloudflare-tunnel)
        bash "$INSTALL_DIR/deploy/setup-cloudflare-tunnel.sh" || \
            c_yellow "    Cloudflare Tunnel setup did not complete. Re-run it later with: sudo bash $INSTALL_DIR/deploy/setup-cloudflare-tunnel.sh"
        ;;
    skip)
        c_yellow "    Skipping tunnel setup. The server listens on localhost only."
        ;;
esac

# ---------------------------------------------------------------- Next steps
echo
echo "============================================================"
c_green "Base installation complete."
echo "============================================================"
echo
echo "Next steps:"
echo
c_blue "1. Log in to Claude (once per host)"
echo "      sudo -u $SERVICE_USER -H claude login"
echo "   -> opens a URL. Open it on another device, log in,"
echo "      then paste the code back into the terminal."
echo
c_blue "2. Start/restart the server"
echo "      sudo systemctl restart $SERVICE_NAME"
echo "      sudo systemctl status $SERVICE_NAME"
echo
c_blue "3. Access mode"
case "$ACCESS_TYPE" in
    tailscale-internal)
        echo "   Tailscale internal-only is configured."
        echo "   Android must run the Tailscale VPN app and be in the same tailnet."
        echo "   Re-run: sudo bash $INSTALL_DIR/deploy/setup-tailscale-internal.sh"
        ;;
    tailscale-funnel)
        echo "   Tailscale Funnel is configured as public internet access."
        echo "   Re-run: sudo bash $INSTALL_DIR/deploy/setup-tailscale-funnel.sh"
        ;;
    cloudflare-tunnel)
        echo "   Cloudflare Tunnel is configured or ready to finish."
        echo "   If you add Cloudflare Access Service Auth, enter the Client ID"
        echo "   and Client Secret in the Android profile."
        echo "   Re-run: sudo bash $INSTALL_DIR/deploy/setup-cloudflare-tunnel.sh"
        ;;
    skip)
        echo "   Tunnel setup skipped."
        echo "   Available scripts:"
        echo "      sudo bash $INSTALL_DIR/deploy/setup-tailscale-internal.sh"
        echo "      sudo bash $INSTALL_DIR/deploy/setup-tailscale-funnel.sh"
        echo "      sudo bash $INSTALL_DIR/deploy/setup-cloudflare-tunnel.sh"
        ;;
esac
echo
c_blue "4. Configure the app"
echo "   Enter the printed server URL into the Pocket Claude app, sign in,"
echo "   and configure Cloudflare Access headers only if you protected the"
echo "   Cloudflare hostname with a Service Auth policy. The initial admin password is in:"
echo "        $INSTALL_DIR/data/INITIAL_PASSWORD.txt"
echo
echo "Tail logs live:    journalctl -u $SERVICE_NAME -f"
echo "Server status:     systemctl status $SERVICE_NAME"
echo
