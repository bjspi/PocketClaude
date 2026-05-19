"""Auth-Layer: Session-Token (Bearer) → User.

Seit Username+Passwort-Login werden alle Auth-Checks gegen die `sessions`-
Tabelle gemacht. Der gelieferte Bearer-Token IST die Session-ID. Beim Hit
wird `sessions.last_seen_at` aktualisiert.

Für die Migration aus der Token-Only-Zeit: Wenn der Bearer-Token in keiner
Session steckt, fallen wir auf `users.token` zurück — aber NUR für User,
die noch kein Passwort haben (frisch migriert). Sobald ein Passwort gesetzt
ist, sterben Legacy-Tokens.
"""
from __future__ import annotations

from fastapi import Depends, Header, HTTPException, Query, status

from pocket_claude import db


def _no_auth() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Missing or invalid token.",
        headers={"WWW-Authenticate": "Bearer"},
    )


async def _resolve_user(token_str: str) -> dict:
    """Schlägt den Bearer-Token nach (Session bevorzugt, Legacy-Fallback)."""
    token_str = token_str.strip()
    user = await db.get_user_by_session_token(token_str)
    if user is None:
        # Legacy: pre-Session-Token. Nur User ohne Passwort dürfen.
        user = await db.get_user_by_token(token_str)
    if user is None:
        raise _no_auth()
    # Internen Bearer-Token im user-dict mitführen, damit Logout-Endpoint
    # weiß, welche Session zu löschen ist. Underscore-Prefix → nicht in
    # API-Responses durchreichen.
    user["__bearer__"] = token_str
    return user


async def require_user(authorization: str | None = Header(default=None)) -> dict:
    """FastAPI-Dependency: authentifiziert per Bearer-Header und liefert den
    User-Dict (id, name, is_admin, must_change_password, …)."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise _no_auth()
    return await _resolve_user(authorization.split(" ", 1)[1])


async def require_admin(user: dict = Depends(require_user)) -> dict:
    """Wie require_user, aber zusätzlich: User muss is_admin=1 haben."""
    if not user.get("is_admin"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin-Rechte erforderlich.",
        )
    return user


async def require_user_header_or_query(
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> dict:
    """Wie require_user, akzeptiert zusätzlich `?token=...` als Query-Param.
    Für Media-Streaming-Endpoints (Audio, Attachment-Download), weil
    ExoPlayer/Browser-<img> keine eigenen Auth-Header mitschicken können."""
    if authorization and authorization.lower().startswith("bearer "):
        return await _resolve_user(authorization.split(" ", 1)[1])
    if token:
        return await _resolve_user(token)
    raise _no_auth()


# Legacy-Aliase für bestehenden Code (importiert in server.py).
require_token = require_user
require_token_header_or_query = require_user_header_or_query
