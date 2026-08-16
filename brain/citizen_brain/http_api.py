"""Minimal authenticated HTTP/JSON transport."""

from __future__ import annotations

import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from typing import Any
from urllib.parse import urlsplit

from .service import ApiError, BrainService, PROTOCOL_VERSION


LOGGER = logging.getLogger("citizen_brain.http")


class BrainApplication:
    def __init__(self, *, service: BrainService, bearer_token: str, max_body_bytes: int) -> None:
        self.service = service
        self.bearer_token = bearer_token
        self.max_body_bytes = max_body_bytes


class BrainHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], application: BrainApplication) -> None:
        self.application = application
        super().__init__(address, BrainRequestHandler)


class BrainRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server: BrainHTTPServer

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        path = urlsplit(self.path).path
        if path != "/healthz":
            self._write_error(ApiError(404, "not_found", "route does not exist"))
            return
        try:
            self._write_json(200, self.server.application.service.health())
        except Exception:
            LOGGER.exception("health check failed")
            self._write_error(ApiError(503, "unhealthy", "storage is unavailable"))

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        path = urlsplit(self.path).path
        routes = {
            "/v1/turn/start": self.server.application.service.start,
            "/v1/turn/continue": self.server.application.service.continue_turn,
            "/v1/turn/cancel": self.server.application.service.cancel,
        }
        operation = routes.get(path)
        if operation is None:
            self._write_error(ApiError(404, "not_found", "route does not exist"))
            return
        if not self._authorized():
            self.close_connection = True
            self._write_json(
                401,
                {
                    "protocol": PROTOCOL_VERSION,
                    "error": {"code": "unauthorized", "message": "bearer token is required"},
                },
                extra_headers={"WWW-Authenticate": "Bearer"},
            )
            return
        try:
            payload = self._read_json()
            self._write_json(200, operation(payload))
        except ApiError as exc:
            self._write_error(exc)
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception:
            LOGGER.exception("unhandled request error on POST %s", path)
            self._write_error(ApiError(500, "internal_error", "internal server error"))

    def do_PUT(self) -> None:  # noqa: N802
        self._method_not_allowed()

    def do_PATCH(self) -> None:  # noqa: N802
        self._method_not_allowed()

    def do_DELETE(self) -> None:  # noqa: N802
        self._method_not_allowed()

    def _method_not_allowed(self) -> None:
        self._write_json(
            405,
            {
                "protocol": PROTOCOL_VERSION,
                "error": {"code": "method_not_allowed", "message": "method is not allowed"},
            },
            extra_headers={"Allow": "GET, POST"},
        )

    def _authorized(self) -> bool:
        values = self.headers.get_all("Authorization", [])
        if len(values) != 1:
            return False
        scheme, separator, credential = values[0].partition(" ")
        if not separator or scheme.lower() != "bearer" or not credential:
            return False
        return hmac.compare_digest(credential, self.server.application.bearer_token)

    def _read_json(self) -> Any:
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            raise ApiError(415, "unsupported_media_type", "Content-Type must be application/json")
        if self.headers.get("Transfer-Encoding"):
            self.close_connection = True
            raise ApiError(400, "invalid_request", "chunked request bodies are not supported")
        length_header = self.headers.get("Content-Length")
        if length_header is None:
            self.close_connection = True
            raise ApiError(411, "length_required", "Content-Length is required")
        try:
            length = int(length_header)
        except ValueError as exc:
            self.close_connection = True
            raise ApiError(400, "invalid_request", "Content-Length is invalid") from exc
        if length < 0:
            self.close_connection = True
            raise ApiError(400, "invalid_request", "Content-Length is invalid")
        if length > self.server.application.max_body_bytes:
            self.close_connection = True
            raise ApiError(413, "body_too_large", "request body exceeds the configured limit")
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise ApiError(400, "invalid_json", "request body ended early")
        try:
            return json.loads(
                raw.decode("utf-8"),
                parse_constant=lambda value: (_ for _ in ()).throw(
                    ValueError(f"invalid JSON constant: {value}")
                ),
            )
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError, RecursionError) as exc:
            raise ApiError(400, "invalid_json", "request body must be valid JSON") from exc

    def _write_error(self, error: ApiError) -> None:
        self._write_json(error.status, error.document())

    def _write_json(
        self,
        status: int,
        document: Any,
        *,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        encoded = json.dumps(
            document,
            ensure_ascii=False,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if extra_headers:
            for name, value in extra_headers.items():
                self.send_header(name, value)
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args: Any) -> None:
        # BaseHTTPRequestHandler's access line contains no headers or bodies, but
        # keeping one controlled format prevents future accidental secret logging.
        LOGGER.info("request method=%s path=%s", self.command, urlsplit(self.path).path)


def create_server(address: tuple[str, int], application: BrainApplication) -> BrainHTTPServer:
    return BrainHTTPServer(address, application)
