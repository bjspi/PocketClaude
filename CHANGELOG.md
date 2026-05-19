# Changelog

All notable changes to Pocket Claude are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).

## [0.1.0] — 2026-05-19

### Added

- Initial public release.
- **Server** (Python 3.10+, FastAPI, SQLite + FTS5)
  - Multi-user authentication (scrypt + session tokens), admin user-management endpoints.
  - SSE streaming of Claude replies via `claude-agent-sdk`.
  - File attachments (images, PDFs, code, configs, JSON, CSV, arbitrary text formats).
  - Full-text search across all conversations.
  - Three TTS providers: Microsoft Edge (free), Gemini Direct API (free tier), Google Cloud TTS (Chirp 3 HD).
  - Image generation via Gemini Nano Banana (separate from chat).
  - Four system-prompt modes: Standard, Permissive, Ultra-Liberal, Custom.
  - Per-chat skill toggles (WebSearch, WebFetch, Bash).
  - AES-256 encrypted backups.
  - Built-in web UI as a desktop / browser alternative to the app.
  - Linux deployment via `deploy/install-linux.sh`, Tailscale Funnel setup, optional Cloudflare Named Tunnel.
- **Android app** (Kotlin 2.0.21, Jetpack Compose Material 3, minSdk 31)
  - Multi-profile login (one app instance can talk to multiple servers / families).
  - Native chat with Markdown rendering, code-block highlighting, native text selection.
  - In-chat full-text search with spring-to-match navigation.
  - Lock-screen TTS audio controls via Media3 ExoPlayer + MediaSession.
  - Liberal file uploads matching the server's accepted types.
  - Image generation screen with gallery and image-to-image editing.
  - ChatGPT-style collapse for long user messages.
  - Encrypted backup + settings export/import.
- **Internationalization**
  - 7 languages: English (default), German, Spanish, French, Brazilian Portuguese, Simplified Chinese, Japanese.
  - In-app language picker under **Settings → Appearance → Language**.
  - Web-UI language picker in **Settings → Language**.
  - Top-level README + per-language README translations under `docs/i18n/`.
- **Documentation**
  - Comprehensive English `README.md` with logo, architecture diagram, quickstart for server + app.
  - Multilingual READMEs for all 7 supported languages.
  - `server/deploy/README.md` covering Tailscale + Cloudflare paths, host migration, troubleshooting.
  - `server/deploy/WORKFLOW.md` for the daily Mac → Mini-PC development loop.

[0.1.0]: https://github.com/joshtech90/PocketClaude/releases/tag/v0.1.0
