"""LLM provider interface and Ollama's native ``/api/chat`` adapter."""

from __future__ import annotations

from dataclasses import dataclass
import json
from threading import BoundedSemaphore
from typing import Any, Mapping, Protocol, Sequence
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


class ProviderError(RuntimeError):
    """A safe-to-report provider failure without request or secret contents."""


class ProviderUnavailable(ProviderError):
    """A transient transport/capacity failure the model cannot fix.

    Raised for queue-slot timeouts, socket errors, retryable HTTP statuses
    (408/429/5xx), and oversized or undecodable provider bodies. Job planning
    maps this to a retryable pause instead of burning bounded planner-fault
    retries on an outage. A stable 4xx (bad key, wrong model) stays a plain
    :class:`ProviderError` so a configuration typo surfaces loudly instead of
    masquerading as an endless outage.
    """


@dataclass(frozen=True, slots=True)
class ProviderToolCall:
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True, slots=True)
class ProviderReply:
    content: str
    assistant_message: dict[str, Any]
    tool_calls: tuple[ProviderToolCall, ...]


class ChatProvider(Protocol):
    def chat(
        self,
        messages: Sequence[Mapping[str, Any]],
        tools: Sequence[Mapping[str, Any]],
    ) -> ProviderReply:
        """Return one native chat response."""


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(
        self,
        request: Request,
        file_pointer: Any,
        code: int,
        message: str,
        headers: Any,
        new_url: str,
    ) -> None:
        return None


class OllamaChatProvider:
    """Small, synchronous Ollama client with a process-wide concurrency gate."""

    def __init__(
        self,
        *,
        url: str,
        model: str,
        api_key: str | None,
        timeout_seconds: int,
        max_response_bytes: int,
        queue_timeout_seconds: float = 20,
        concurrency: int = 1,
        opener: Any | None = None,
    ) -> None:
        self._url = url
        self._model = model
        self._api_key = api_key
        self._timeout_seconds = timeout_seconds
        self._max_response_bytes = max_response_bytes
        self._queue_timeout_seconds = queue_timeout_seconds
        self._slots = BoundedSemaphore(concurrency)
        self._opener = opener or build_opener(_NoRedirectHandler())

    def chat(
        self,
        messages: Sequence[Mapping[str, Any]],
        tools: Sequence[Mapping[str, Any]],
    ) -> ProviderReply:
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": list(messages),
            "stream": False,
        }
        if tools:
            payload["tools"] = list(tools)
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": "zapeg-citizens-brain/1",
        }
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        request = Request(self._url, data=encoded, headers=headers, method="POST")

        acquired = self._slots.acquire(timeout=self._queue_timeout_seconds)
        if not acquired:
            raise ProviderUnavailable("provider is busy")
        try:
            try:
                with self._opener.open(request, timeout=self._timeout_seconds) as response:
                    raw = response.read(self._max_response_bytes + 1)
            except HTTPError as exc:
                if exc.code in (408, 429) or exc.code >= 500:
                    raise ProviderUnavailable(
                        f"provider returned HTTP {exc.code}"
                    ) from exc
                # A stable non-retryable status (401 bad key, 404 wrong model or
                # URL) is a configuration fault, not an outage: report it loudly
                # instead of cycling polite provider_unavailable retry pauses.
                raise ProviderError(
                    f"provider rejected the request with HTTP {exc.code}; check "
                    "CITIZENS_LLM_URL, CITIZENS_LLM_MODEL, and CITIZENS_LLM_API_KEY"
                ) from exc
            except (URLError, TimeoutError, OSError) as exc:
                raise ProviderUnavailable("provider request failed") from exc
        finally:
            self._slots.release()

        if len(raw) > self._max_response_bytes:
            raise ProviderUnavailable("provider response exceeded the configured limit")
        try:
            document = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ProviderUnavailable("provider returned invalid JSON") from exc
        return self._parse(document)

    @staticmethod
    def _parse(document: Any) -> ProviderReply:
        if not isinstance(document, dict):
            raise ProviderError("provider response must be a JSON object")
        message = document.get("message")
        if not isinstance(message, dict):
            raise ProviderError("provider response is missing message")
        content = message.get("content", "")
        if not isinstance(content, str):
            raise ProviderError("provider message content must be a string")
        raw_calls = message.get("tool_calls", [])
        if raw_calls is None:
            raw_calls = []
        if not isinstance(raw_calls, list):
            raise ProviderError("provider tool_calls must be an array")
        if len(raw_calls) > 64:
            raise ProviderError("provider returned too many parallel tool calls")

        calls: list[ProviderToolCall] = []
        normalized_calls: list[dict[str, Any]] = []
        for raw_call in raw_calls:
            if not isinstance(raw_call, dict) or not isinstance(raw_call.get("function"), dict):
                raise ProviderError("provider returned an invalid tool call")
            function = raw_call["function"]
            name = function.get("name")
            arguments = function.get("arguments", {})
            if isinstance(arguments, str):
                try:
                    arguments = json.loads(arguments)
                except json.JSONDecodeError as exc:
                    raise ProviderError("provider returned invalid tool arguments") from exc
            if not isinstance(name, str) or not name or not isinstance(arguments, dict):
                raise ProviderError("provider returned an invalid tool function")
            calls.append(ProviderToolCall(name=name, arguments=arguments))
            normalized_calls.append(
                {"type": "function", "function": {"name": name, "arguments": arguments}}
            )

        assistant: dict[str, Any] = {"role": "assistant", "content": content}
        if normalized_calls:
            assistant["tool_calls"] = normalized_calls
        return ProviderReply(
            content=content,
            assistant_message=assistant,
            tool_calls=tuple(calls),
        )
