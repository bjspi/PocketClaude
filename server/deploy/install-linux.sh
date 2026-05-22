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
REPO_URL="${REPO_URL:-https://github.com/bjspi/PocketClaude.git}"

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
c_cyan()   { printf '\033[1;36m%s\033[0m\n' "$*"; }
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

truthy() {
    case "${1,,}" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

print_banner() {
    echo
    printf '\033[38;5;81m'
    cat <<'EOF'
    ____             __        __     ________                __
   / __ \____  _____/ /_____  / /_   / ____/ /___ ___  ______/ /__
  / /_/ / __ \/ ___/ //_/ _ \/ __/  / /   / / __ `/ / / / __  / _ \
 / ____/ /_/ / /__/ ,< /  __/ /_   / /___/ / /_/ / /_/ / /_/ /  __/
/_/    \____/\___/_/|_|\___/\__/   \____/_/\__,_/\__,_/\__,_/\___/
EOF
    printf '\033[0m'
    c_cyan "             Linux installer - server, tunnels, systemd"
    echo
}

service_unit_present() {
    [[ -f "/etc/systemd/system/${SERVICE_NAME}.service" ]] && return 0
    command -v systemctl >/dev/null 2>&1 && systemctl cat "$SERVICE_NAME" >/dev/null 2>&1
}

service_running() {
    command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet "$SERVICE_NAME"
}

existing_install_present() {
    [[ -d "$INSTALL_DIR" ]] || service_unit_present
}

print_install_status() {
    step "Current server status"
    if [[ -d "$INSTALL_DIR" ]]; then
        c_green "    Install directory: present ($INSTALL_DIR)"
    else
        c_yellow "    Install directory: missing ($INSTALL_DIR)"
    fi

    if [[ -f "$INSTALL_DIR/.env" ]]; then
        c_green "    Server .env: present"
    else
        c_yellow "    Server .env: missing"
    fi

    if [[ -d "$INSTALL_DIR/data" ]]; then
        c_green "    Data directory: present"
    else
        c_yellow "    Data directory: missing"
    fi

    if service_unit_present; then
        c_green "    systemd service: installed"
        if service_running; then
            c_green "    Runtime status: running"
        else
            c_yellow "    Runtime status: not running"
        fi
    else
        c_yellow "    systemd service: not installed"
    fi
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

claude_credentials_present() {
    sudo -u "$SERVICE_USER" -H bash -lc '
        if command -v claude >/dev/null 2>&1 && claude auth status >/dev/null 2>&1; then
            exit 0
        fi
        [[ -f ~/.claude/.credentials.json ]] ||
        [[ -f ~/.claude/credentials.json ]] ||
        [[ -f ~/.config/claude/credentials.json ]]
    ' 2>/dev/null
}

root_claude_credentials_present() {
    [[ -f /root/.claude/.credentials.json ]] ||
    [[ -f /root/.claude/credentials.json ]] ||
    [[ -f /root/.config/claude/credentials.json ]]
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

path_is_inside() {
    local child="$1"
    local parent="$2"
    local child_real
    local parent_real
    child_real="$(readlink -f "$child" 2>/dev/null || printf '%s\n' "$child")"
    parent_real="$(readlink -f "$parent" 2>/dev/null || printf '%s\n' "$parent")"
    [[ "$child_real" == "$parent_real" || "$child_real" == "$parent_real"/* ]]
}

clean_install_dir() {
    local target
    target="$(readlink -f "$INSTALL_DIR" 2>/dev/null || printf '%s\n' "$INSTALL_DIR")"
    case "$target" in
        ""|"/"|"/opt"|"/opt/"|"/usr"|"/var"|"/home")
            c_red "Refusing to clean unsafe install directory: $target"
            exit 1
            ;;
    esac
    if [[ "$target" != /opt/* && "${ALLOW_CLEAN_INSTALL_DIR:-}" != "1" ]]; then
        c_red "Refusing to clean non-/opt install directory: $target"
        c_red "Set ALLOW_CLEAN_INSTALL_DIR=1 if this custom INSTALL_DIR is intentional."
        exit 1
    fi

    step "Fresh install: cleaning $target"
    if service_running; then
        c_yellow "    Stopping running $SERVICE_NAME service first."
        systemctl stop "$SERVICE_NAME" || true
    fi
    rm -rf --one-file-system "$target"
    c_green "    Removed old install directory."
}

# ---------------------------------------------------------------- Root-Check
if [[ $EUID -ne 0 ]]; then
    c_red "Please run with sudo: sudo bash $0"
    exit 1
fi

# ---------------------------------------------------------------- Interactive choices
print_banner
print_install_status

CLEAN_INSTALL="${CLEAN_INSTALL:-}"
REUSE_EXISTING_ENV="${REUSE_EXISTING_ENV:-}"

if existing_install_present; then
    if [[ -z "$CLEAN_INSTALL" ]]; then
        if prompt_yes_no "Fresh install and CLEAN $INSTALL_DIR first?" "n"; then
            CLEAN_INSTALL=1
        else
            CLEAN_INSTALL=0
        fi
    elif truthy "$CLEAN_INSTALL"; then
        CLEAN_INSTALL=1
    else
        CLEAN_INSTALL=0
    fi
else
    CLEAN_INSTALL=0
fi

if [[ "$CLEAN_INSTALL" == "1" ]]; then
    REUSE_EXISTING_ENV=0
elif [[ -f "$INSTALL_DIR/.env" ]]; then
    if [[ -z "$REUSE_EXISTING_ENV" ]]; then
        if prompt_yes_no "Reuse existing server .env and keep the same settings?" "y"; then
            REUSE_EXISTING_ENV=1
        else
            REUSE_EXISTING_ENV=0
        fi
    elif truthy "$REUSE_EXISTING_ENV"; then
        REUSE_EXISTING_ENV=1
    else
        REUSE_EXISTING_ENV=0
    fi
else
    REUSE_EXISTING_ENV=0
fi

WEBUI_ENABLED="${WEBUI_ENABLED:-}"
if [[ "$REUSE_EXISTING_ENV" == "1" ]]; then
    c_green "Reusing existing .env: ENABLE_WEBUI and existing settings stay unchanged."
elif [[ -z "$WEBUI_ENABLED" ]]; then
    if prompt_yes_no "Enable the built-in browser Web UI?" "y"; then
        WEBUI_ENABLED=1
    else
        WEBUI_ENABLED=0
    fi
fi
if [[ "$REUSE_EXISTING_ENV" != "1" ]]; then
    case "${WEBUI_ENABLED,,}" in
        1|true|yes|y|on) WEBUI_ENABLED=1 ;;
        *) WEBUI_ENABLED=0 ;;
    esac
fi

ACCESS_TYPE="$(prompt_access_type)"
if [[ "$REUSE_EXISTING_ENV" == "1" ]]; then
    c_green "Selected Web UI: unchanged from existing .env"
else
    c_green "Selected Web UI: $([[ "$WEBUI_ENABLED" == "1" ]] && echo enabled || echo disabled)"
fi
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

if [[ "$CLEAN_INSTALL" == "1" ]]; then
    if path_is_inside "$DEPLOY_SOURCE" "$INSTALL_DIR"; then
        TMP_CLONE="$(mktemp -d)"
        c_yellow "    Installer source is inside $INSTALL_DIR; cloning $REPO_URL before cleaning."
        git clone "$REPO_URL" "$TMP_CLONE"
        if ! DEPLOY_SOURCE="$(resolve_server_source "$TMP_CLONE")"; then
            c_red "Cloned repository does not look like PocketClaude."
            c_red "Expected either ./server/pocket_claude or ./pocket_claude with requirements.txt."
            exit 1
        fi
    fi
    clean_install_dir
fi

mkdir -p "$INSTALL_DIR"

# `--exclude .venv` so we don't copy a local venv with wrong binaries.
# `--exclude data` so a running server doesn't lose its SQLite DB.
# `--exclude .env` so updates can re-use the server's token and settings.
# `--exclude .git` — we don't need repo history on the server.
rsync -a --delete \
    --exclude '.venv' \
    --exclude 'data' \
    --exclude '.env' \
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

if [[ "$REUSE_EXISTING_ENV" == "1" ]]; then
    c_green "    Reused existing .env settings."
else
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
fi
chown "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/.env"
chmod 600 "$INSTALL_DIR/.env"
if [[ "$REUSE_EXISTING_ENV" == "1" ]]; then
    c_green "    ENABLE_WEBUI unchanged"
else
    c_green "    ENABLE_WEBUI=$WEBUI_ENABLED"
fi

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
if ! claude_credentials_present; then
    c_yellow "    Claude login is missing for service user '$SERVICE_USER'."
    if root_claude_credentials_present; then
        c_yellow "    Root appears to have Claude credentials, but the service runs as '$SERVICE_USER'."
    fi
    echo
    c_yellow "    Run this in a second terminal on this host:"
    echo "      sudo -u $SERVICE_USER -H claude login"
    echo
    if [[ -r /dev/tty || -t 0 ]]; then
        c_yellow "    After login completes, press Enter here to re-check and start the service."
        c_yellow "    Press Ctrl+C to stop here and continue later."
        read_prompt "    Waiting for Claude login... " _
    fi
fi

if claude_credentials_present; then
    systemctl restart "$SERVICE_NAME"
    sleep 2
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        c_green "    ✓ Service running."
    else
        c_yellow "    Service not active — see logs: journalctl -u $SERVICE_NAME -n 50"
    fi
else
    c_yellow "    Service NOT YET started — claude login is missing."
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
case "$ACCESS_TYPE" in
    tailscale-internal)
        echo "   Enter the printed Tailscale internal URL into the Pocket Claude app."
        echo "   Android must be connected to the same Tailscale tailnet."
        ;;
    tailscale-funnel)
        echo "   Enter the printed Tailscale Funnel URL into the Pocket Claude app."
        echo "   This URL is public, so keep the Pocket Claude login password strong."
        ;;
    cloudflare-tunnel)
        echo "   Enter the printed Cloudflare Tunnel URL into the Pocket Claude app."
        echo "   Configure Cloudflare Access headers only if you protected the"
        echo "   Cloudflare hostname with a Service Auth policy."
        ;;
    skip)
        echo "   Tunnel setup was skipped. Configure an access script first, then enter"
        echo "   the printed server URL into the Pocket Claude app."
        ;;
esac
echo
echo "   Initial admin password file:"
echo "        $INSTALL_DIR/data/INITIAL_PASSWORD.txt"
echo "   Show it:"
echo "        sudo cat $INSTALL_DIR/data/INITIAL_PASSWORD.txt"
echo "   Delete it after the password was saved in your password manager:"
echo "        sudo rm -f $INSTALL_DIR/data/INITIAL_PASSWORD.txt"
echo
echo "Tail logs live:    journalctl -u $SERVICE_NAME -f"
echo "Server status:     systemctl status $SERVICE_NAME"
echo
