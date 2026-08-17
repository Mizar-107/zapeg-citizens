from __future__ import annotations

from http.client import HTTPConnection
import json
import threading
import unittest

from citizen_brain.http_api import BrainApplication, create_server
from citizen_brain.job_service import JobService
from citizen_brain.service import BrainService
from citizen_brain.storage import SQLiteStore

from tests.helpers import FakeProvider, TempDatabaseTest, settings, start_payload
from tests.test_service import final_reply
from tests.test_jobs import job_payload, reply, result_payload, resume_payload


class HTTPAPITest(TempDatabaseTest, unittest.TestCase):
    def setUp(self) -> None:
        super().setUp()
        configured = settings(self.db_path)
        self.provider = FakeProvider(final_reply("Ready."))
        self.job_provider = FakeProvider()
        store = SQLiteStore(configured.db_path)
        service = BrainService(
            settings=configured,
            store=store,
            provider=self.provider,
        )
        job_service = JobService(
            settings=configured,
            store=store,
            provider=self.job_provider,
        )
        self.server = create_server(
            ("127.0.0.1", 0),
            BrainApplication(
                service=service,
                job_service=job_service,
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
        self.assertEqual({"protocol": 3, "status": "ok"}, health)

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

    def test_protocol_one_is_rejected_with_a_protocol_three_error(self) -> None:
        payload = start_payload()
        payload["protocol"] = 1

        status, document = self.request(
            "POST",
            "/v1/turn/start",
            payload,
            token="test-brain-token",
        )

        self.assertEqual(400, status)
        self.assertEqual(3, document["protocol"])
        self.assertEqual("unsupported_protocol", document["error"]["code"])
        self.assertEqual(0, len(self.provider.calls))

    def test_all_job_routes_are_authenticated_and_dispatch(self) -> None:
        payload = job_payload()
        status, error = self.request("POST", "/v1/job/start", payload)
        self.assertEqual(401, status)
        self.assertEqual("unauthorized", error["error"]["code"])

        self.job_provider.replies.append(reply("look_around", {}))
        status, action = self.request(
            "POST", "/v1/job/start", payload, token="test-brain-token"
        )
        self.assertEqual(200, status)
        self.assertEqual("ACTION", action["kind"])

        self.job_provider.replies.append(
            reply(
                "job_needs_input",
                {
                    "phase": "materials",
                    "summary": "A material choice is required.",
                    "question": "Should I use oak or spruce?",
                },
            )
        )
        status, blocked = self.request(
            "POST",
            "/v1/job/result",
            result_payload(
                "http-result",
                "job-1",
                action["action"]["id"],
                {"success": True, "nearby": []},
            ),
            token="test-brain-token",
        )
        self.assertEqual(200, status)
        self.assertEqual("NEEDS_INPUT", blocked["kind"])

        self.job_provider.replies.append(reply("look_around", {}))
        status, action = self.request(
            "POST",
            "/v1/job/resume",
            resume_payload(
                "http-resume",
                "job-1",
                answer="Use spruce.",
                actions_completed=1,
            ),
            token="test-brain-token",
        )
        self.assertEqual(200, status)
        self.assertEqual("ACTION", action["kind"])

        status, current = self.request(
            "POST",
            "/v1/job/status",
            {"protocol": 3, "job_id": "job-1"},
            token="test-brain-token",
        )
        self.assertEqual(200, status)
        self.assertEqual("ACTION", current["kind"])
        self.assertNotIn("action", current)
        status, listed = self.request(
            "POST",
            "/v1/job/list",
            {"protocol": 3, "citizen_id": "citizen-1"},
            token="test-brain-token",
        )
        self.assertEqual(200, status)
        self.assertEqual([current], listed["jobs"])

        status, paused = self.request(
            "POST",
            "/v1/job/pause",
            {
                "protocol": 3,
                "request_id": "http-pause",
                "job_id": "job-1",
                "reason": "HTTP route test",
            },
            token="test-brain-token",
        )
        self.assertEqual(200, status)
        self.assertEqual("PAUSED", paused["kind"])
        status, canceled = self.request(
            "POST",
            "/v1/job/cancel",
            {
                "protocol": 3,
                "request_id": "http-cancel",
                "job_id": "job-1",
                "reason": "HTTP route test complete",
            },
            token="test-brain-token",
        )
        self.assertEqual(200, status)
        self.assertEqual("canceled", canceled["reason"])


if __name__ == "__main__":
    unittest.main()
