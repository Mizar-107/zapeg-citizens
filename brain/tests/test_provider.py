from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time
import unittest

from citizen_brain.provider import OllamaChatProvider, ProviderError


class FakeResponse:
    def __init__(self, document: dict) -> None:
        self.data = json.dumps(document).encode("utf-8")

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def read(self, limit: int) -> bytes:
        return self.data[:limit]


class RecordingOpener:
    def __init__(self, document: dict, *, delay: float = 0.0) -> None:
        self.document = document
        self.delay = delay
        self.requests = []
        self.active = 0
        self.max_active = 0
        self.lock = threading.Lock()

    def open(self, request, timeout: int) -> FakeResponse:
        self.requests.append(request)
        with self.lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            if self.delay:
                time.sleep(self.delay)
            return FakeResponse(self.document)
        finally:
            with self.lock:
                self.active -= 1


class BlockingOpener:
    def __init__(self) -> None:
        self.entered = threading.Event()
        self.release = threading.Event()

    def open(self, request, timeout: int) -> FakeResponse:
        self.entered.set()
        if not self.release.wait(timeout=2):
            raise TimeoutError("test release was not signaled")
        return FakeResponse({"message": {"role": "assistant", "content": "ok"}})


class OllamaChatProviderTest(unittest.TestCase):
    def test_native_response_and_string_arguments_are_normalized(self) -> None:
        opener = RecordingOpener(
            {
                "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                        {"function": {"name": "move_to", "arguments": '{"x":7}'}},
                        {"function": {"name": "scan", "arguments": {"radius": 4}}},
                    ],
                }
            }
        )
        provider = OllamaChatProvider(
            url="https://ollama.example/api/chat",
            model="model",
            api_key="secret-key",
            timeout_seconds=5,
            max_response_bytes=4096,
            concurrency=1,
            opener=opener,
        )
        reply = provider.chat([{"role": "user", "content": "go"}], [])
        self.assertEqual(["move_to", "scan"], [call.name for call in reply.tool_calls])
        self.assertEqual({"x": 7}, reply.tool_calls[0].arguments)
        self.assertEqual("Bearer secret-key", opener.requests[0].get_header("Authorization"))
        sent = json.loads(opener.requests[0].data)
        self.assertFalse(sent["stream"])
        self.assertEqual("model", sent["model"])

    def test_global_provider_concurrency_gate_defaults_to_one_slot(self) -> None:
        opener = RecordingOpener(
            {"message": {"role": "assistant", "content": "ok"}},
            delay=0.04,
        )
        provider = OllamaChatProvider(
            url="http://ollama/api/chat",
            model="model",
            api_key=None,
            timeout_seconds=5,
            max_response_bytes=4096,
            opener=opener,
        )
        threads = [
            threading.Thread(target=provider.chat, args=([{"role": "user", "content": "x"}], []))
            for _ in range(3)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(1, opener.max_active)

    def test_second_call_times_out_waiting_for_provider_slot_without_deadlock(self) -> None:
        opener = BlockingOpener()
        provider = OllamaChatProvider(
            url="http://ollama/api/chat",
            model="model",
            api_key=None,
            timeout_seconds=5,
            max_response_bytes=4096,
            queue_timeout_seconds=0.03,
            concurrency=1,
            opener=opener,
        )
        first_errors: list[BaseException] = []

        def first_call() -> None:
            try:
                provider.chat([{"role": "user", "content": "first"}], [])
            except BaseException as exc:
                first_errors.append(exc)

        first = threading.Thread(target=first_call)
        first.start()
        self.assertTrue(opener.entered.wait(timeout=1))
        started = time.monotonic()
        try:
            with self.assertRaisesRegex(ProviderError, "provider is busy"):
                provider.chat([{"role": "user", "content": "second"}], [])
            elapsed = time.monotonic() - started
            self.assertLess(elapsed, 0.5)
        finally:
            opener.release.set()
            first.join(timeout=1)

        self.assertFalse(first.is_alive(), "first provider call must not deadlock")
        self.assertEqual([], first_errors)

    def test_redirect_is_rejected_without_forwarding_authorization(self) -> None:
        target_authorization: list[str | None] = []

        class TargetHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802
                target_authorization.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()

            def log_message(self, format: str, *args: object) -> None:
                return None

        target = ThreadingHTTPServer(("127.0.0.1", 0), TargetHandler)
        target_thread = threading.Thread(target=target.serve_forever, daemon=True)
        target_thread.start()

        location = f"http://127.0.0.1:{target.server_port}/stolen"

        class RedirectHandler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", "0"))
                self.rfile.read(length)
                self.send_response(302)
                self.send_header("Location", location)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, format: str, *args: object) -> None:
                return None

        redirect = ThreadingHTTPServer(("127.0.0.1", 0), RedirectHandler)
        redirect_thread = threading.Thread(target=redirect.serve_forever, daemon=True)
        redirect_thread.start()
        try:
            provider = OllamaChatProvider(
                url=f"http://127.0.0.1:{redirect.server_port}/api/chat",
                model="model",
                api_key="must-not-leak",
                timeout_seconds=2,
                max_response_bytes=4096,
            )
            with self.assertRaisesRegex(ProviderError, "HTTP 302"):
                provider.chat([{"role": "user", "content": "hello"}], [])
            time.sleep(0.02)
            self.assertEqual([], target_authorization)
        finally:
            redirect.shutdown()
            redirect.server_close()
            redirect_thread.join(timeout=1)
            target.shutdown()
            target.server_close()
            target_thread.join(timeout=1)


if __name__ == "__main__":
    unittest.main()
