from __future__ import annotations

import unittest

from citizen_brain.provider import ProviderReply, ProviderToolCall
from citizen_brain.service import ApiError, BrainService
from citizen_brain.storage import SQLiteStore

from tests.helpers import FakeProvider, TempDatabaseTest, settings, start_payload


def tool_reply(*calls: ProviderToolCall) -> ProviderReply:
    normalized = [
        {"type": "function", "function": {"name": call.name, "arguments": call.arguments}}
        for call in calls
    ]
    return ProviderReply(
        content="",
        assistant_message={"role": "assistant", "content": "", "tool_calls": normalized},
        tool_calls=tuple(calls),
    )


def final_reply(speech: str) -> ProviderReply:
    return ProviderReply(
        content=speech,
        assistant_message={"role": "assistant", "content": speech},
        tool_calls=(),
    )


class BrainServiceTest(TempDatabaseTest, unittest.TestCase):
    def service(self, provider: FakeProvider, **overrides: str) -> BrainService:
        configured = settings(self.db_path, **overrides)
        return BrainService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=provider,
        )

    def test_parallel_calls_are_emitted_sequentially_with_tool_names(self) -> None:
        provider = FakeProvider(
            tool_reply(
                ProviderToolCall("collect_items", {"item": "iron_ore"}),
                ProviderToolCall("move_to", {"x": 12}),
            ),
            final_reply("I collected the iron."),
        )
        service = self.service(provider)

        first = service.start(start_payload())
        self.assertEqual("collect_items", first["tool_call"]["name"])
        self.assertEqual(1, len(provider.calls))

        second = service.continue_turn(
            {
                "protocol": 1,
                "turn_id": first["turn_id"],
                "tool_call_id": first["tool_call"]["id"],
                "result": {"collected": 4},
            }
        )
        self.assertEqual("move_to", second["tool_call"]["name"])
        self.assertEqual(1, len(provider.calls), "queued call must not trigger the model early")

        final = service.continue_turn(
            {
                "protocol": 1,
                "turn_id": first["turn_id"],
                "tool_call_id": second["tool_call"]["id"],
                "result": "arrived",
            }
        )
        self.assertEqual({"kind": "final", "speech": "I collected the iron."}, {
            "kind": final["kind"], "speech": final["speech"]
        })
        self.assertEqual(2, len(provider.calls))
        tool_messages = [message for message in provider.calls[1][0] if message["role"] == "tool"]
        self.assertEqual(["collect_items", "move_to"], [m["tool_name"] for m in tool_messages])

    def test_completed_request_is_idempotent_and_memory_is_actor_scoped(self) -> None:
        provider = FakeProvider(
            final_reply("First answer."),
            final_reply("Second answer."),
            final_reply("Other actor answer."),
        )
        service = self.service(provider)
        payload = start_payload()
        first = service.start(payload)
        replay = service.start(payload)
        self.assertEqual(first, replay)
        self.assertEqual(1, len(provider.calls))

        service.start(start_payload("request-2", prompt="what did I ask?"))
        second_messages = provider.calls[1][0]
        self.assertIn({"role": "user", "content": "collect iron"}, second_messages)
        self.assertIn({"role": "assistant", "content": "First answer."}, second_messages)

        service.start(start_payload("request-3", actor_id="actor-2", prompt="hello"))
        other_messages = provider.calls[2][0]
        self.assertNotIn({"role": "user", "content": "collect iron"}, other_messages)
        self.assertNotIn({"role": "assistant", "content": "First answer."}, other_messages)

        changed = start_payload(prompt="different")
        with self.assertRaises(ApiError) as raised:
            service.start(changed)
        self.assertEqual("request_id_reused", raised.exception.code)

    def test_final_speech_control_whitespace_is_stored_and_returned_single_line(self) -> None:
        provider = FakeProvider(
            final_reply("\n  I\tcollected\r\n iron.\v  Ready.\x00  \f"),
            final_reply("Next."),
        )
        service = self.service(provider)

        result = service.start(start_payload())
        self.assertEqual("I collected iron. Ready.", result["speech"])

        service.start(start_payload("request-2", prompt="continue"))
        second_messages = provider.calls[1][0]
        self.assertIn(
            {"role": "assistant", "content": "I collected iron. Ready."},
            second_messages,
        )
        self.assertNotIn(
            {"role": "assistant", "content": "\n  I\tcollected\r\n iron.\v  Ready.\x00  \f"},
            second_messages,
        )

    def test_tool_call_content_is_normalized_and_bounded_before_persistence(self) -> None:
        call = ProviderToolCall("collect_items", {"item": "iron_ore"})
        provider = FakeProvider(
            ProviderReply(
                content="\n Planning\tthis.\x00 ",
                assistant_message={"role": "assistant", "content": "untrusted raw value"},
                tool_calls=(call,),
            ),
            final_reply("Finished."),
        )
        service = self.service(provider)

        first = service.start(start_payload())
        service.continue_turn(
            {
                "protocol": 1,
                "turn_id": first["turn_id"],
                "tool_call_id": first["tool_call"]["id"],
                "result": {"success": True},
            }
        )

        second_messages = provider.calls[1][0]
        assistant = next(
            message for message in second_messages if message.get("role") == "assistant"
        )
        self.assertEqual("Planning this.", assistant["content"])
        self.assertEqual("collect_items", assistant["tool_calls"][0]["function"]["name"])

        oversized = FakeProvider(
            ProviderReply(
                content="x" * 2_049,
                assistant_message={"role": "assistant", "content": "x" * 2_049},
                tool_calls=(call,),
            )
        )
        isolated = self.service(oversized)
        with self.assertRaises(ApiError) as raised:
            isolated.start(start_payload("oversized-tool-content"))
        self.assertEqual("provider_error", raised.exception.code)

    def test_tool_step_limit_fails_closed_before_emitting_any_parallel_call(self) -> None:
        provider = FakeProvider(
            tool_reply(
                ProviderToolCall("collect_items", {"item": "coal"}),
                ProviderToolCall("move_to", {"x": 3}),
            )
        )
        service = self.service(provider, CITIZENS_MAX_TOOL_STEPS="1")
        result = service.start(start_payload())
        self.assertEqual("final", result["kind"])
        self.assertIn("tool-step limit", result["speech"])

    def test_unknown_provider_tool_is_rejected_and_turn_is_released(self) -> None:
        provider = FakeProvider(
            tool_reply(ProviderToolCall("operator_command", {"command": "stop"})),
            final_reply("Recovered."),
        )
        service = self.service(provider)
        with self.assertRaises(ApiError) as raised:
            service.start(start_payload())
        self.assertEqual(502, raised.exception.status)
        self.assertEqual("provider_error", raised.exception.code)

        recovered = service.start(start_payload("request-2"))
        self.assertEqual("Recovered.", recovered["speech"])

    def test_cancel_is_idempotent_and_prevents_continuation(self) -> None:
        provider = FakeProvider(tool_reply(ProviderToolCall("move_to", {"x": 9})))
        service = self.service(provider)
        call = service.start(start_payload())
        canceled = service.cancel({"protocol": 1, "turn_id": call["turn_id"]})
        self.assertEqual("canceled", canceled["kind"])
        self.assertEqual(canceled, service.cancel({"protocol": 1, "turn_id": call["turn_id"]}))
        with self.assertRaises(ApiError) as raised:
            service.continue_turn(
                {
                    "protocol": 1,
                    "turn_id": call["turn_id"],
                    "tool_call_id": call["tool_call"]["id"],
                    "result": "late",
                }
            )
        self.assertEqual("turn_not_active", raised.exception.code)

    def test_second_actor_is_rejected_while_citizen_has_an_active_turn(self) -> None:
        provider = FakeProvider(tool_reply(ProviderToolCall("move_to", {"x": 9})))
        service = self.service(provider)
        active = service.start(start_payload(actor_id="actor-a"))
        self.assertEqual("tool_call", active["kind"])

        with self.assertRaises(ApiError) as raised:
            service.start(start_payload("request-2", actor_id="actor-b"))
        self.assertEqual(409, raised.exception.status)
        self.assertEqual("citizen_busy", raised.exception.code)
        self.assertEqual(1, len(provider.calls))

    def test_cancel_by_request_id_recovers_a_lost_start_response(self) -> None:
        provider = FakeProvider(tool_reply(ProviderToolCall("move_to", {"x": 9})))
        service = self.service(provider)
        call = service.start(start_payload("lost-response-request"))

        canceled = service.cancel(
            {"protocol": 1, "request_id": "lost-response-request"}
        )
        self.assertEqual("canceled", canceled["kind"])
        self.assertEqual(call["turn_id"], canceled["turn_id"])
        self.assertEqual(
            canceled,
            service.cancel({"protocol": 1, "request_id": "lost-response-request"}),
        )

        with self.assertRaises(ApiError) as raised:
            service.cancel(
                {
                    "protocol": 1,
                    "turn_id": call["turn_id"],
                    "request_id": "lost-response-request",
                }
            )
        self.assertEqual(400, raised.exception.status)
        self.assertEqual("invalid_request", raised.exception.code)

    def test_request_id_cancel_before_start_leaves_a_race_tombstone(self) -> None:
        provider = FakeProvider(final_reply("Must never run."))
        service = self.service(provider)

        early_cancel = service.cancel({"protocol": 1, "request_id": "raced-request"})
        self.assertEqual(
            {"protocol": 1, "turn_id": None, "kind": "canceled"},
            early_cancel,
        )
        self.assertEqual(
            early_cancel,
            service.cancel({"protocol": 1, "request_id": "raced-request"}),
        )

        with self.assertRaises(ApiError) as raised:
            service.start(start_payload("raced-request"))
        self.assertEqual(409, raised.exception.status)
        self.assertEqual("request_canceled", raised.exception.code)
        self.assertEqual([], provider.calls)

    def test_cancel_tombstones_follow_terminal_count_bound(self) -> None:
        now = [1.0]
        provider = FakeProvider(final_reply("Old cancellation was pruned."))
        configured = settings(self.db_path, CITIZENS_MAX_TERMINAL_TURNS="2")
        service = BrainService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=provider,
            clock=lambda: now[0],
        )
        for index in range(1, 4):
            now[0] = float(index)
            service.cancel({"protocol": 1, "request_id": f"canceled-{index}"})

        now[0] = 4.0
        allowed = service.start(start_payload("canceled-1"))
        self.assertEqual("Old cancellation was pruned.", allowed["speech"])
        with self.assertRaises(ApiError) as raised:
            service.start(start_payload("canceled-3"))
        self.assertEqual("request_canceled", raised.exception.code)

    def test_cancel_tombstone_expires_after_terminal_retention_window(self) -> None:
        now = [10.0]
        provider = FakeProvider(final_reply("Cancellation expired."))
        configured = settings(
            self.db_path,
            CITIZENS_TERMINAL_TURN_TTL_SECONDS="60",
        )
        service = BrainService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=provider,
            clock=lambda: now[0],
        )
        service.cancel({"protocol": 1, "request_id": "expired-cancellation"})
        now[0] = 71.0
        result = service.start(start_payload("expired-cancellation"))
        self.assertEqual("Cancellation expired.", result["speech"])

    def test_server_owned_citizen_may_have_no_owner_id(self) -> None:
        provider = FakeProvider(final_reply("For the realm."))
        service = self.service(provider)
        payload = start_payload()
        payload["citizen"]["owner_kind"] = "SERVER"
        payload["citizen"]["owner_id"] = None
        result = service.start(payload)
        self.assertEqual("For the realm.", result["speech"])

    def test_terminal_turn_count_is_bounded_while_recent_replay_survives(self) -> None:
        provider = FakeProvider(
            final_reply("One."),
            final_reply("Two."),
            final_reply("Three."),
            final_reply("One again."),
        )
        service = self.service(provider, CITIZENS_MAX_TERMINAL_TURNS="2")
        first_payload = start_payload("request-1")
        service.start(first_payload)
        service.start(start_payload("request-2"))
        newest = service.start(start_payload("request-3"))

        self.assertEqual(newest, service.start(start_payload("request-3")))
        self.assertEqual(3, len(provider.calls))
        replay_after_eviction = service.start(first_payload)
        self.assertEqual("One again.", replay_after_eviction["speech"])
        self.assertEqual(4, len(provider.calls))


if __name__ == "__main__":
    unittest.main()
