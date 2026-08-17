from __future__ import annotations

from collections import deque
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any, Mapping, Sequence

from citizen_brain.config import Settings
from citizen_brain.provider import ProviderReply


def settings(db_path: str, **overrides: str) -> Settings:
    environment = {
        "CITIZENS_LLM_MODEL": "test-model",
        "CITIZENS_BRAIN_TOKEN": "test-brain-token",
        "CITIZENS_DB_PATH": db_path,
    }
    environment.update(overrides)
    return Settings.from_env(environment)


class FakeProvider:
    def __init__(self, *replies: ProviderReply) -> None:
        self.replies = deque(replies)
        self.calls: list[tuple[list[dict[str, Any]], list[dict[str, Any]]]] = []

    def chat(
        self,
        messages: Sequence[Mapping[str, Any]],
        tools: Sequence[Mapping[str, Any]],
    ) -> ProviderReply:
        self.calls.append(([dict(message) for message in messages], [dict(tool) for tool in tools]))
        if not self.replies:
            raise AssertionError("fake provider has no queued reply")
        return self.replies.popleft()


class TempDatabaseTest:
    temp: TemporaryDirectory[str]
    db_path: str

    def setUp(self) -> None:
        super().setUp()  # type: ignore[misc]
        self.temp = TemporaryDirectory()
        self.db_path = str(Path(self.temp.name) / "brain.sqlite3")

    def tearDown(self) -> None:
        self.temp.cleanup()
        super().tearDown()  # type: ignore[misc]


TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "collect_items",
            "description": "Collect an item from the world.",
            "parameters": {
                "type": "object",
                "properties": {"item": {"type": "string"}},
                "required": ["item"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "move_to",
            "description": "Move to coordinates.",
            "parameters": {
                "type": "object",
                "properties": {"x": {"type": "integer"}},
                "required": ["x"],
            },
        },
    },
]


def start_payload(
    request_id: str = "request-1",
    *,
    actor_id: str = "actor-1",
    prompt: str = "collect iron",
) -> dict[str, Any]:
    return {
        "protocol": 3,
        "request_id": request_id,
        "citizen": {
            "id": "citizen-1",
            "name": "Atlas",
            "owner_kind": "PLAYER",
            "owner_id": "owner-1",
            "role": "miner",
            "faction": "village",
            "interaction_mode": "TASK",
            "persona": "A practical village miner who speaks plainly.",
        },
        "actor": {"id": actor_id, "name": "Player"},
        "prompt": prompt,
        "tools": TOOLS,
    }
