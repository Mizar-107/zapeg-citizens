"""Environment-only configuration for the shared brain."""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
from typing import Mapping
from urllib.parse import urlsplit


def _integer(
    env: Mapping[str, str],
    name: str,
    default: int,
    minimum: int,
    maximum: int,
) -> int:
    raw = env.get(name, str(default)).strip()
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _secret(env: Mapping[str, str], name: str, *, required: bool) -> str | None:
    direct = env.get(name)
    file_name = env.get(f"{name}_FILE")
    if direct is not None and file_name is not None:
        raise ValueError(f"set only one of {name} or {name}_FILE")

    value: str | None
    if file_name is not None:
        path = Path(file_name)
        try:
            if path.stat().st_size > 65_536:
                raise ValueError(f"{name}_FILE is unexpectedly large")
            value = path.read_text(encoding="utf-8")
        except OSError as exc:
            raise ValueError(f"could not read {name}_FILE") from exc
    else:
        value = direct

    if value is not None:
        value = value.strip()
    if required and not value:
        raise ValueError(f"{name} or {name}_FILE is required")
    return value or None


@dataclass(frozen=True, slots=True)
class Settings:
    bind: str
    port: int
    db_path: str
    brain_token: str
    llm_url: str
    llm_api_key: str | None
    llm_model: str
    llm_timeout_seconds: int
    llm_queue_timeout_seconds: int
    llm_concurrency: int
    max_body_bytes: int
    max_provider_bytes: int
    max_prompt_chars: int
    max_persona_chars: int
    max_result_chars: int
    max_speech_chars: int
    max_tool_description_chars: int
    max_tool_schema_bytes: int
    max_tools: int
    max_tool_steps: int
    max_history_messages: int
    max_active_turns: int
    turn_ttl_seconds: int
    terminal_turn_ttl_seconds: int
    max_terminal_turns: int

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        values = os.environ if env is None else env
        model = values.get("CITIZENS_LLM_MODEL", "").strip()
        if not model:
            raise ValueError("CITIZENS_LLM_MODEL is required")

        llm_url = values.get("CITIZENS_LLM_URL", "https://ollama.com/api/chat").strip()
        parsed = urlsplit(llm_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("CITIZENS_LLM_URL must be an http(s) URL")
        if parsed.username or parsed.password or parsed.fragment:
            raise ValueError("CITIZENS_LLM_URL must not contain credentials or a fragment")
        llm_api_key = _secret(values, "CITIZENS_LLM_API_KEY", required=False)
        if llm_api_key and parsed.scheme != "https":
            raise ValueError("CITIZENS_LLM_API_KEY requires an https CITIZENS_LLM_URL")

        return cls(
            bind=values.get("CITIZENS_BIND", "0.0.0.0").strip() or "0.0.0.0",
            port=_integer(values, "CITIZENS_PORT", 8787, 1, 65_535),
            db_path=values.get("CITIZENS_DB_PATH", "data/citizens-brain.sqlite3").strip()
            or "data/citizens-brain.sqlite3",
            brain_token=_secret(values, "CITIZENS_BRAIN_TOKEN", required=True) or "",
            llm_url=llm_url,
            llm_api_key=llm_api_key,
            llm_model=model,
            llm_timeout_seconds=_integer(values, "CITIZENS_LLM_TIMEOUT_SECONDS", 90, 1, 600),
            llm_queue_timeout_seconds=_integer(
                values, "CITIZENS_LLM_QUEUE_TIMEOUT_SECONDS", 20, 1, 120
            ),
            llm_concurrency=_integer(values, "CITIZENS_LLM_CONCURRENCY", 1, 1, 16),
            max_body_bytes=_integer(values, "CITIZENS_MAX_BODY_BYTES", 262_144, 1_024, 2_097_152),
            max_provider_bytes=_integer(
                values, "CITIZENS_MAX_PROVIDER_BYTES", 1_048_576, 4_096, 8_388_608
            ),
            max_prompt_chars=_integer(values, "CITIZENS_MAX_PROMPT_CHARS", 8_000, 1, 64_000),
            max_persona_chars=_integer(
                values, "CITIZENS_MAX_PERSONA_CHARS", 4_096, 1, 16_384
            ),
            max_result_chars=_integer(values, "CITIZENS_MAX_RESULT_CHARS", 16_000, 1, 128_000),
            max_speech_chars=_integer(values, "CITIZENS_MAX_SPEECH_CHARS", 2_048, 1, 2_048),
            max_tool_description_chars=_integer(
                values, "CITIZENS_MAX_TOOL_DESCRIPTION_CHARS", 4_096, 1, 65_536
            ),
            max_tool_schema_bytes=_integer(
                values, "CITIZENS_MAX_TOOL_SCHEMA_BYTES", 131_072, 1_024, 1_048_576
            ),
            max_tools=_integer(values, "CITIZENS_MAX_TOOLS", 64, 0, 256),
            max_tool_steps=_integer(values, "CITIZENS_MAX_TOOL_STEPS", 8, 1, 64),
            max_history_messages=_integer(
                values, "CITIZENS_MAX_HISTORY_MESSAGES", 24, 0, 200
            ),
            max_active_turns=_integer(values, "CITIZENS_MAX_ACTIVE_TURNS", 64, 1, 1_024),
            turn_ttl_seconds=_integer(values, "CITIZENS_TURN_TTL_SECONDS", 900, 30, 86_400),
            terminal_turn_ttl_seconds=_integer(
                values, "CITIZENS_TERMINAL_TURN_TTL_SECONDS", 86_400, 60, 604_800
            ),
            max_terminal_turns=_integer(
                values, "CITIZENS_MAX_TERMINAL_TURNS", 1_000, 1, 100_000
            ),
        )
