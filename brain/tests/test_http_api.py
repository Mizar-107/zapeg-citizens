from __future__ import annotations

from http.client import HTTPConnection
import json
import threading
import unittest

from citizen_brain.http_api import BrainApplication, create_server
from citizen_brain.service import BrainService
from citizen_brain.storage import SQLiteStore

from tests.helpers import FakeProvider, TempDatabaseTest, settings, start_payload
from tests.test_service import final_reply


class HTTPAPITest(TempDatabaseTest, unittest.TestCase):
    def setUp(self) -> None:
        super().setUp()
        configured = settings(self.db_path)
        self.provider = FakeProvider(final_reply("Ready."))
        service = BrainService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=self.provider,
        )
        self.server = create_server(
            ("127.0.0.1", 0),
            BrainApplication(
                service=service,
                bearer_token=configured.brain_token,
                max_body_bytes=configured.max_body_bytes,
            ),
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        super().tearDown()

    def request(
        self,
        method: str,
        path: str,
        document: dict | None = None,
        *,
        token: str | None = None,
    ) -> tuple[int, dict]:
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        body = json.dumps(document).encode("utf-8") if document is not None else None
        headers = {}
        if document is not None:
            headers["Content-Type"] = "application/json"
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        decoded = json.loads(response.read())
        status = response.status
        connection.close()
        return status, decoded

    def test_health_is_public_but_turns_require_bearer_auth(self) -> None:
        status, health = self.request("GET", "/healthz")
        self.assertEqual(200, status)
        self.assertEqual({"protocol": 2, "status": "ok"}, health)

        status, error = self.request("POST", "/v1/turn/start", start_payload())
        self.assertEqual(401, status)
        self.assertEqual("unauthorized", error["error"]["code"])
        self.assertEqual(0, len(self.provider.calls))

        status, result = self.request(
            "POST", "/v1/turn/start", start_payload(), token="test-brain-token"
        )
        self.assertEqual(200, status)
        self.assertEqual("Ready.", result["speech"])

    def test_invalid_json_returns_structured_error(self) -> None:
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        connection.request(
            "POST",
            "/v1/turn/start",
            body=b"{broken",
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer test-brain-token",
            },
        )
        response = connection.getresponse()
        document = json.loads(response.read())
        connection.close()
        self.assertEqual(400, response.status)
        self.assertEqual("invalid_json", document["error"]["code"])

    def test_protocol_one_is_rejected_with_a_protocol_two_error(self) -> None:
        payload = start_payload()
        payload["protocol"] = 1

        status, document = self.request(
            "POST",
            "/v1/turn/start",
            payload,
            token="test-brain-token",
        )

        self.assertEqual(400, status)
        self.assertEqual(2, document["protocol"])
        self.assertEqual("unsupported_protocol", document["error"]["code"])
        self.assertEqual(0, len(self.provider.calls))


if __name__ == "__main__":
    unittest.main()
