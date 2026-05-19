# Pocket Claude — Server

Self-hosted FastAPI backend for [Pocket Claude](../README.md). Wraps the [Claude Code CLI](https://github.com/anthropics/claude-code) and exposes it as a multi-user chat API plus a built-in web UI.

For the full project overview, architecture diagram, and quickstart, see the [top-level README](../README.md).

## Layout

```
server/
├── pocket_claude/         FastAPI app, SQLite, TTS, image-gen, auth
│   ├── webui/             Built-in web UI (vanilla JS, no build step)
│   └── ...
├── deploy/                Install/setup scripts for Linux + tunnel setup
├── scripts/               Dev helpers (run-dev.sh, etc.)
├── requirements.txt
└── pocket_claude_manager.py    Optional macOS dev GUI (Tkinter)
```

## Run from source (dev)

```bash
cd server
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python -m pocket_claude
```

Server listens on `http://127.0.0.1:8787`. First start prints the initial admin password (also saved to `data/INITIAL_PASSWORD.txt`).

## Deploy to a Linux host

See [`deploy/README.md`](deploy/README.md). One-line install:

```bash
curl -fsSL https://raw.githubusercontent.com/joshtech90/PocketClaude/main/server/deploy/install-linux.sh | sudo bash
```

## API surface

All endpoints except `/auth/login` and `/health` require `Authorization: Bearer <SESSION_TOKEN>`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Health check |
| POST | `/auth/login` | Username + password → session token |
| GET / POST / PATCH / DELETE | `/conversations[/{id}]` | Chat CRUD |
| POST | `/conversations/{id}/messages` | Send message (SSE stream) |
| GET / POST | `/attachments[/{id}]` | File uploads |
| GET / PUT | `/tts/*` | TTS status / provider / voice / model |
| GET | `/messages/{id}/audio` | TTS audio stream |
| POST | `/images/generate` | Image generation (Gemini Nano Banana) |
| GET / POST | `/backup[/...]` | Encrypted backup |
| GET / POST / DELETE | `/users/...` | User management (admin) |

## License

MIT — see [`../LICENSE`](../LICENSE).
