"""Voice-Input — Groq Whisper Large v3 Turbo Wrapper.

Eingehend: rohes Audio-File (m4a/aac/webm-opus/wav/...) + UI-Sprache.
Ausgehend: transkribierter Text. Pro Sprache ein eigener Bias-Prompt
(verbessert Zeichensetzung, Großschreibung und das Beibehalten von
englischen Tech-Begriffen), Stil-Vorlage angelehnt an WhisperTyper.

Per-User Groq-API-Key liegt in der kv-Tabelle, scope=user_id, key
`voice_groq_api_key`. Settings-Backup deckt das automatisch über
`extra_kv` ab.
"""
from __future__ import annotations

import logging
from typing import Final

import httpx

log = logging.getLogger(__name__)

GROQ_TRANSCRIBE_URL: Final = "https://api.groq.com/openai/v1/audio/transcriptions"
GROQ_DEFAULT_MODEL: Final = "whisper-large-v3-turbo"

# UI-Locale → (whisper `language` ISO 639-1, Bias-Prompt).
# Whisper akzeptiert `language` nur zweistellig — "pt-BR" wird zu "pt"
# gemappt, "zh" und "ja" bleiben wie sie sind.
#
# Prompts: ~50-80 Token pro Sprache. Bewusst nicht länger — Whisper benutzt
# den `prompt`-Parameter als Conditioning-Kontext für die letzten Tokens,
# zu lange Prompts können zu Echos im Output führen.
_LANG_CONFIG: Final[dict[str, tuple[str, str]]] = {
    "en": (
        "en",
        "The following is a transcription of a voice input. Aim for an "
        "almost perfect, verbatim transcript — only strip filler words "
        "and silence. Use correct spelling, capitalization, and natural "
        "punctuation (periods, commas, question marks). I often mix in "
        "English tech terms — gadgets, smartphones, AI, software, "
        "programming jargon — please preserve those exactly.",
    ),
    "de": (
        "de",
        "Das Folgende ist eine Transkription einer Spracheingabe. Die "
        "Transkription sollte nahezu perfekt mit dem Original "
        "übereinstimmen, lediglich Füllwörter und Pausen sollten entfernt "
        "werden. Bitte achten Sie auf Rechtschreibung, Großschreibung und "
        "sinnvolle Zeichensetzung, einschließlich Punkte und Kommas. Ich "
        "verwende auch \"denglische\" (eingedeutschte) englische Begriffe, "
        "insbesondere aus dem Technologie- und IT-Bereich, aus den "
        "Bereichen Gadgets, Smartphones, KI und Python-Programmierung.",
    ),
    "es": (
        "es",
        "Lo siguiente es la transcripción de una entrada de voz. La "
        "transcripción debe ser casi perfecta — eliminar solo muletillas "
        "y silencios. Cuida la ortografía, las mayúsculas y la puntuación "
        "natural (puntos, comas, signos de interrogación). Suelo mezclar "
        "términos técnicos en inglés (gadgets, smartphones, IA, software, "
        "programación), por favor consérvalos tal cual.",
    ),
    "fr": (
        "fr",
        "Ce qui suit est la transcription d'une saisie vocale. Vise une "
        "transcription presque parfaite — supprime uniquement les mots de "
        "remplissage et les silences. Soigne l'orthographe, les "
        "majuscules et la ponctuation naturelle (points, virgules, points "
        "d'interrogation). J'utilise souvent des termes techniques "
        "anglais (gadgets, smartphones, IA, logiciels, programmation), "
        "conserve-les tels quels.",
    ),
    "pt-BR": (
        "pt",
        "O seguinte é uma transcrição de uma entrada de voz. A "
        "transcrição deve ser quase perfeita — remova apenas palavras de "
        "preenchimento e silêncios. Cuide da ortografia, do uso de "
        "maiúsculas e da pontuação natural (pontos, vírgulas, pontos de "
        "interrogação). Costumo usar termos técnicos em inglês (gadgets, "
        "smartphones, IA, software, programação), por favor preserve-os.",
    ),
    "zh": (
        "zh",
        "以下是语音输入的转写。请尽可能忠实于原音 — 只去除填充词和静音。"
        "注意拼写、大小写和自然的标点(句号、逗号、问号)。"
        "我经常夹杂英文技术词汇(gadgets, smartphones, AI, software, "
        "programming),请保留原貌。",
    ),
    "ja": (
        "ja",
        "以下は音声入力の書き起こしです。フィラーや無音以外はほぼ完全に"
        "忠実に書き起こしてください。スペル、大文字小文字、自然な句読点"
        "(ピリオド、カンマ、疑問符)に注意してください。技術用語"
        "(gadgets、smartphones、AI、software、programming)は"
        "英語のまま残してください。",
    ),
}


def resolve_lang(ui_locale: str | None) -> tuple[str, str]:
    """UI-Locale (z.B. "de", "pt-BR", "zh-CN") → (whisper-lang-code, bias-prompt).

    Unbekannte / leere Locale → Englisch-Default.
    """
    if not ui_locale:
        return _LANG_CONFIG["en"]
    raw = ui_locale.strip()
    if raw in _LANG_CONFIG:
        return _LANG_CONFIG[raw]
    # Variant-Reduktion: "zh-CN" → "zh", "pt-PT" → "pt-BR" (näher dran als en)
    base = raw.split("-", 1)[0].lower()
    if base == "pt":
        return _LANG_CONFIG["pt-BR"]
    if base in _LANG_CONFIG:
        return _LANG_CONFIG[base]
    return _LANG_CONFIG["en"]


class VoiceTranscribeError(Exception):
    """Wrapper für alle Fehler beim Transkribieren — gibt einen
    User-tauglichen Text zurück, der direkt in der UI gezeigt werden kann."""


async def transcribe(
    *,
    audio_bytes: bytes,
    filename: str,
    content_type: str,
    ui_locale: str | None,
    api_key: str,
    model: str = GROQ_DEFAULT_MODEL,
    timeout_sec: float = 60.0,
) -> str:
    """Schickt das Audio an Groq, liefert den Transkript-Text zurück.

    Wirft `VoiceTranscribeError` mit lesbarer Nachricht bei Auth-Fehlern,
    Timeout, leerem Transkript oder Groq-5xx.
    """
    if not api_key:
        raise VoiceTranscribeError(
            "Kein Groq-API-Key gesetzt. In den Einstellungen unter "
            "'Spracheingabe' eintragen.")
    if not audio_bytes:
        raise VoiceTranscribeError("Audio-Daten leer.")

    whisper_lang, prompt = resolve_lang(ui_locale)

    files = {
        "file": (filename, audio_bytes, content_type or "application/octet-stream"),
    }
    data = {
        "model": model,
        "language": whisper_lang,
        "prompt": prompt,
        "response_format": "json",
        "temperature": "0",
    }
    headers = {"Authorization": f"Bearer {api_key}"}

    try:
        async with httpx.AsyncClient(timeout=timeout_sec) as cli:
            r = await cli.post(
                GROQ_TRANSCRIBE_URL,
                headers=headers,
                files=files,
                data=data,
            )
    except httpx.TimeoutException as e:
        log.warning("Groq transcribe timeout: %s", e)
        raise VoiceTranscribeError(
            "Groq antwortet zu langsam (>60 s). Bitte nochmal versuchen."
        ) from e
    except httpx.RequestError as e:
        log.warning("Groq transcribe network error: %s", e)
        raise VoiceTranscribeError(f"Netzwerkfehler zu Groq: {e}") from e

    if r.status_code == 401:
        raise VoiceTranscribeError(
            "Groq-API-Key ungültig oder abgelaufen. In den Einstellungen "
            "neuen Key eintragen.")
    if r.status_code == 429:
        raise VoiceTranscribeError(
            "Groq-Rate-Limit erreicht (zu viele Anfragen). Kurz warten und "
            "erneut versuchen.")
    if r.status_code >= 500:
        raise VoiceTranscribeError(
            f"Groq-Serverfehler ({r.status_code}). Bitte später nochmal.")
    if r.status_code >= 400:
        # 4xx mit Body — Groq liefert oft `{"error": {"message": "..."}}`
        msg = f"HTTP {r.status_code}"
        try:
            j = r.json()
            err = (j.get("error") or {}).get("message") or ""
            if err:
                msg = err
        except Exception:  # noqa: BLE001 — fallback to raw body
            msg = (r.text or msg)[:200]
        raise VoiceTranscribeError(f"Groq lehnt die Anfrage ab: {msg}")

    try:
        body = r.json()
    except Exception as e:  # noqa: BLE001
        raise VoiceTranscribeError(
            f"Groq-Antwort ist kein JSON: {r.text[:200]}") from e

    text = (body.get("text") or "").strip()
    if not text:
        raise VoiceTranscribeError(
            "Transkript ist leer — vermutlich keine Sprache erkannt. "
            "Bitte näher ans Mikro oder lauter sprechen.")
    return text
