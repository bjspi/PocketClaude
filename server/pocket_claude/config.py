"""Settings loaded from .env."""
from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Auth (App ↔ Server)
    server_token: str = Field(..., min_length=8)

    # Claude-Agent-SDK nutzt die lokale `claude`-CLI-Installation. Die SDK findet
    # das Binary über PATH oder $CLAUDE_CODE_ENTRYPOINT. Falls Du einen anderen
    # Pfad brauchst, setze ihn unten — wir reichen das an `cli_path` weiter.
    claude_binary: str | None = None
    # Optional: spezifisches Modell überschreiben (sonst SDK-Default)
    claude_model: str | None = None

    # Server
    server_host: str = "0.0.0.0"
    server_port: int = 8787
    dev_reload: bool = False

    # Storage
    data_dir: Path = Path("./data")

    # Uploads
    max_upload_mb: int = 20

    # Context-Warnung in der App: ab wieviel % der 200K Tokens soll der Banner anspringen
    context_warning_ratio: float = 0.85
    max_context_tokens: int = 200_000

    # Logging
    log_level: str = "INFO"

    # Security: allow the per-chat "Bash" skill at all? Off by default — an
    # app user would otherwise be able to execute arbitrary commands on the
    # host as the `pocket-claude` system user. Operators who explicitly want
    # Bash set ALLOW_BASH=1 in .env.
    allow_bash: bool = False

    @property
    def db_path(self) -> Path:
        return self.data_dir / "pocket_claude.db"

    @property
    def uploads_dir(self) -> Path:
        return self.data_dir / "uploads"


settings = Settings()  # type: ignore[call-arg]
settings.data_dir.mkdir(parents=True, exist_ok=True)
settings.uploads_dir.mkdir(parents=True, exist_ok=True)
