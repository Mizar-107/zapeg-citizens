from __future__ import annotations

import json
import unittest

from citizen_brain.job_service import JobService
from citizen_brain.provider import ProviderError, ProviderReply, ProviderToolCall
from citizen_brain.service import ApiError
from citizen_brain.storage import SQLiteStore

from tests.helpers import FakeProvider, TempDatabaseTest, settings


WORLD_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "look_around",
            "description": "Observe nearby blocks and entities.",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "mine",
            "description": "Mine requested blocks.",
            "parameters": {
                "type": "object",
                "properties": {
                    "block_ids": {"type": "array", "items": {"type": "string"}},
                    "count": {"type": "integer"},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "load_skill",
            "description": "Load bounded server-authored task guidance without changing the world.",
            "parameters": {
                "type": "object",
                "properties": {"skill": {"type": "string"}},
                "required": ["skill"],
            },
        },
    },
]


def reply(name: str, arguments: dict) -> ProviderReply:
    call = ProviderToolCall(name, arguments)
    return ProviderReply(
        content="",
        assistant_message={
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {"type": "function", "function": {"name": name, "arguments": arguments}}
            ],
        },
        tool_calls=(call,),
    )


def job_payload(
    request_id: str = "job-start-1",
    job_id: str = "job-1",
    *,
    goal: str = "Mine five diamonds and report the confirmed count.",
    max_actions: int = 32,
    max_model_calls: int = 64,
) -> dict:
    return {
        "protocol": 3,
        "request_id": request_id,
        "job_id": job_id,
        "citizen": {
            "id": "citizen-1",
            "name": "Atlas",
            "owner_kind": "PLAYER",
            "owner_id": "owner-1",
            "role": "miner",
            "faction": "village",
            "interaction_mode": "TASK",
            "persona": "A careful miner.",
        },
        "actor": {
            "id": "actor-1",
            "name": "Player",
            "dimension": "minecraft:overworld",
            "x": 10.5,
            "y": 64,
            "z": -2.5,
            "yaw": 90.0,
            "pitch": 0.0,
            "look_target": {
                "kind": "BLOCK",
                "dimension": "minecraft:overworld",
                "x": 11,
                "y": 64,
                "z": -2,
            },
        },
        "goal": goal,
        "tools": WORLD_TOOLS,
        "budgets": {
            "max_actions": max_actions,
            "max_model_calls": max_model_calls,
            "max_active_seconds": 10_800,
        },
    }


def result_payload(request_id: str, job_id: str, action_id: str, result: object) -> dict:
    return {
        "protocol": 3,
        "request_id": request_id,
        "job_id": job_id,
        "action_id": action_id,
        "result": result,
    }


def resume_payload(
    request_id: str,
    job_id: str,
    *,
    pending_action_id: str | None = None,
    uncertain: bool = False,
    answer: str | None = None,
    actions_completed: int = 0,
) -> dict:
    payload = {
        "protocol": 3,
        "request_id": request_id,
        "job_id": job_id,
        "checkpoint": {
            "state": "PAUSED",
            "actions_completed": actions_completed,
            "active_seconds": 30,
            "pending_action_uncertain": uncertain,
            "progress": {"detail": "server restart reconciliation"},
        },
    }
    if pending_action_id is not None:
        payload["checkpoint"]["pending_action_id"] = pending_action_id
    if answer is not None:
        payload["answer"] = answer
    return payload


class DurableJobTest(TempDatabaseTest, unittest.TestCase):
    def service(self, provider: FakeProvider, **overrides: str) -> JobService:
        configured = settings(self.db_path, **overrides)
        return JobService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=provider,
        )

    def test_plan_action_result_finish_and_result_idempotency(self) -> None:
        provider = FakeProvider(
            reply(
                "job_define_plan",
                {
                    "phase": "survey",
                    "summary": "Plan diamond collection and verification.",
                    "completion_criteria": ["Five new diamonds are confirmed."],
                    "steps": ["Survey", "Mine", "Verify"],
                },
            ),
            reply("mine", {"block_ids": ["minecraft:diamond_ore"], "count": 5}),
            reply("look_around", {}),
        )
        service = self.service(provider)
        started = service.start(job_payload())
        self.assertEqual("ACTION", started["kind"])
        self.assertEqual("mine", started["action"]["name"])
        self.assertEqual(2, len(provider.calls))
        action_id = started["action"]["id"]

        mine_result = result_payload(
            "job-result-1", "job-1", action_id, {"success": True, "count": 5}
        )
        verification = service.result(mine_result)
        self.assertEqual("ACTION", verification["kind"])
        self.assertEqual("look_around", verification["action"]["name"])
        verification_id = verification["action"]["id"]

        provider.replies.append(
            reply(
                "job_finish",
                {
                    "phase": "complete",
                    "summary": "Five diamonds confirmed.",
                    "speech": "I mined five diamonds.",
                    "evidence_action_ids": [action_id, verification_id],
                },
            )
        )
        result = result_payload(
            "job-result-2",
            "job-1",
            verification_id,
            {"success": True, "inventory": {"minecraft:diamond": 5}},
        )
        completed = service.result(result)
        self.assertEqual("COMPLETED", completed["kind"])
        self.assertEqual(2, completed["progress"]["actions_completed"])
        self.assertEqual(completed, service.result(result))
        self.assertEqual(4, len(provider.calls))

        replay_with_new_request = dict(result)
        replay_with_new_request["request_id"] = "job-result-1-retry"
        self.assertEqual(completed, service.result(replay_with_new_request))
        changed = dict(replay_with_new_request)
        changed["request_id"] = "job-result-wrong"
        changed["result"] = {"success": False}
        with self.assertRaises(ApiError) as raised:
            service.result(changed)
        self.assertEqual("result_mismatch", raised.exception.code)

    def test_needs_input_answer_is_persisted_and_reenters_planning(self) -> None:
        provider = FakeProvider(
            reply(
                "job_needs_input",
                {
                    "phase": "materials",
                    "summary": "A palette choice is required.",
                    "question": "Should the villa use spruce or oak?",
                },
            ),
            reply("look_around", {}),
        )
        service = self.service(provider)
        blocked = service.start(job_payload(goal="Build a villa here."))
        self.assertEqual("NEEDS_INPUT", blocked["kind"])
        replay = service.resume(resume_payload("resume-no-answer", "job-1"))
        self.assertEqual(blocked, replay)
        self.assertEqual(1, len(provider.calls))

        resumed = service.resume(
            resume_payload(
                "resume-with-answer",
                "job-1",
                answer="Use spruce with a stone foundation.",
            )
        )
        self.assertEqual("ACTION", resumed["kind"])
        context = json.dumps(provider.calls[-1][0], ensure_ascii=False)
        self.assertIn("Use spruce with a stone foundation.", context)

    def test_mutating_pending_action_becomes_interrupted_and_requires_observation(self) -> None:
        first_provider = FakeProvider(
            reply("mine", {"block_ids": ["minecraft:diamond_ore"], "count": 5})
        )
        service = self.service(first_provider)
        pending = service.start(job_payload())
        action_id = pending["action"]["id"]

        recovering_provider = FakeProvider(reply("look_around", {}), reply("mine", {"count": 1}))
        recovering = self.service(recovering_provider)
        observation = recovering.resume(
            resume_payload(
                "resume-after-crash",
                "job-1",
                pending_action_id=action_id,
                uncertain=True,
            )
        )
        self.assertEqual("look_around", observation["action"]["name"])
        offered = {tool["function"]["name"] for tool in recovering_provider.calls[0][1]}
        self.assertIn("look_around", offered)
        self.assertIn("load_skill", offered)
        self.assertNotIn("mine", offered)
        self.assertNotIn("job_finish", offered)

        next_action = recovering.result(
            result_payload(
                "recovery-observation-result",
                "job-1",
                observation["action"]["id"],
                {"success": True, "nearby": []},
            )
        )
        self.assertEqual("mine", next_action["action"]["name"])
        offered_after = {
            tool["function"]["name"] for tool in recovering_provider.calls[1][1]
        }
        self.assertIn("mine", offered_after)

    def test_read_only_pending_action_is_reemitted_without_model_call(self) -> None:
        service = self.service(FakeProvider(reply("look_around", {})))
        pending = service.start(job_payload())
        reloaded_provider = FakeProvider()
        reloaded = self.service(reloaded_provider)
        replay = reloaded.resume(
            resume_payload(
                "resume-read-only",
                "job-1",
                pending_action_id=pending["action"]["id"],
                uncertain=True,
            )
        )
        self.assertEqual(pending, replay)
        self.assertEqual([], reloaded_provider.calls)

    def test_paused_read_only_action_is_reemitted_exactly_on_resume(self) -> None:
        provider = FakeProvider(reply("look_around", {}))
        service = self.service(provider)
        pending = service.start(job_payload())
        service.pause(
            {
                "protocol": 3,
                "request_id": "pause-read-only",
                "job_id": "job-1",
                "reason": "server stopping",
            }
        )

        replay = service.resume(resume_payload("resume-read-only-paused", "job-1"))
        self.assertEqual("ACTION", replay["kind"])
        self.assertEqual(pending["action"], replay["action"])
        self.assertEqual(1, len(provider.calls))

    def test_action_budget_pauses_instead_of_emitting_an_extra_action(self) -> None:
        provider = FakeProvider(
            reply("mine", {"count": 1}),
            reply("mine", {"count": 1}),
        )
        service = self.service(provider)
        first = service.start(job_payload(max_actions=1))
        paused = service.result(
            result_payload("only-result", "job-1", first["action"]["id"], {"success": True})
        )
        self.assertEqual("PAUSED", paused["kind"])
        self.assertIn("action budget", paused["reason"])
        self.assertEqual(2, len(provider.calls))

    def test_model_call_budget_pauses_before_an_unbudgeted_followup(self) -> None:
        provider = FakeProvider(reply("mine", {"count": 1}))
        service = self.service(provider)
        first = service.start(job_payload(max_model_calls=1))
        paused = service.result(
            result_payload(
                "model-budget-result",
                "job-1",
                first["action"]["id"],
                {"success": True},
            )
        )
        self.assertEqual("PAUSED", paused["kind"])
        self.assertIn("model-call budget", paused["reason"])
        self.assertEqual(1, len(provider.calls))

    def test_finish_requires_at_least_one_confirmed_action_id(self) -> None:
        premature_finish = reply(
            "job_finish",
            {
                "phase": "complete",
                "summary": "Claimed completion without evidence.",
                "speech": "Done.",
                "evidence_action_ids": [],
            },
        )
        provider = FakeProvider(premature_finish, premature_finish, premature_finish)
        service = self.service(provider)
        paused = service.start(job_payload())
        self.assertEqual("PAUSED", paused["kind"])
        self.assertIn("confirmed action evidence", paused["reason"])
        # One initial attempt plus the bounded planner retries were all journaled.
        self.assertEqual(3, len(provider.calls))
        stored = service.store.get_job("job-1")
        assert stored is not None
        self.assertEqual("PAUSED", stored.state)
        events = service.store.recent_job_events("job-1", 16)
        faults = [event for event in events if event["type"] == "planner_error"]
        self.assertEqual(2, len(faults))
        self.assertIn("confirmed action evidence", faults[0]["payload"]["reason"])

    def test_finish_fault_feedback_lets_the_planner_recover_and_act(self) -> None:
        premature_finish = reply(
            "job_finish",
            {
                "phase": "complete",
                "summary": "Claimed completion without evidence.",
                "speech": "Done.",
                "evidence_action_ids": [],
            },
        )
        provider = FakeProvider(premature_finish, reply("look_around", {}))
        service = self.service(provider)
        started = service.start(job_payload())
        self.assertEqual("ACTION", started["kind"])
        self.assertEqual("look_around", started["action"]["name"])
        self.assertEqual(2, len(provider.calls))
        feedback = json.dumps(provider.calls[-1][0], ensure_ascii=False)
        self.assertIn("planner_error", feedback)
        self.assertIn("confirmed action evidence", feedback)

    def test_malformed_planner_reply_is_journaled_and_retried(self) -> None:
        prose = ProviderReply(
            content="I will look around first.",
            assistant_message={
                "role": "assistant",
                "content": "I will look around first.",
            },
            tool_calls=(),
        )
        provider = FakeProvider(prose, reply("look_around", {}))
        service = self.service(provider)
        started = service.start(job_payload())
        self.assertEqual("ACTION", started["kind"])
        self.assertEqual("look_around", started["action"]["name"])
        self.assertEqual(2, len(provider.calls))
        feedback = json.dumps(provider.calls[-1][0], ensure_ascii=False)
        self.assertIn("planner_error", feedback)
        self.assertIn("exactly one", feedback)
        stored = service.store.get_job("job-1")
        assert stored is not None
        self.assertEqual("WAITING_ACTION", stored.state)

    def test_transient_provider_error_is_retried_before_pausing(self) -> None:
        class FlakyProvider:
            def __init__(self) -> None:
                self.calls = 0

            def chat(self, messages: object, tools: object) -> ProviderReply:
                self.calls += 1
                if self.calls == 1:
                    raise ProviderError("provider is busy")
                return reply("look_around", {})

        provider = FlakyProvider()
        service = self.service(provider)  # type: ignore[arg-type]
        started = service.start(job_payload())
        self.assertEqual("ACTION", started["kind"])
        self.assertEqual(2, provider.calls)
        events = service.store.recent_job_events("job-1", 16)
        faults = [event for event in events if event["type"] == "planner_error"]
        self.assertEqual(1, len(faults))
        self.assertIn("provider is busy", faults[0]["payload"]["reason"])

    def test_persistent_provider_failure_pauses_after_bounded_retries(self) -> None:
        class DownProvider:
            def __init__(self) -> None:
                self.calls = 0

            def chat(self, messages: object, tools: object) -> ProviderReply:
                self.calls += 1
                raise ProviderError("provider request failed")

        provider = DownProvider()
        service = self.service(provider)  # type: ignore[arg-type]
        paused = service.start(job_payload())
        self.assertEqual("PAUSED", paused["kind"])
        self.assertIn("provider request failed", paused["reason"])
        self.assertEqual(3, provider.calls)
        stored = service.store.get_job("job-1")
        assert stored is not None
        self.assertEqual("PAUSED", stored.state)

    def test_planner_retries_can_be_disabled_for_fail_fast(self) -> None:
        prose = ProviderReply(
            content="done",
            assistant_message={"role": "assistant", "content": "done"},
            tool_calls=(),
        )
        provider = FakeProvider(prose)
        service = self.service(provider, CITIZENS_MAX_JOB_PLANNER_RETRIES="0")
        paused = service.start(job_payload())
        self.assertEqual("PAUSED", paused["kind"])
        self.assertIn("exactly one", paused["reason"])
        self.assertEqual(1, len(provider.calls))

    def test_unavailable_world_tool_is_retried_with_feedback(self) -> None:
        provider = FakeProvider(
            reply("break_block", {"x": 1}),
            reply("look_around", {}),
        )
        service = self.service(provider)
        started = service.start(job_payload())
        self.assertEqual("ACTION", started["kind"])
        self.assertEqual("look_around", started["action"]["name"])
        feedback = json.dumps(provider.calls[-1][0], ensure_ascii=False)
        self.assertIn("unavailable world tool", feedback)

    def test_tool_free_and_parallel_provider_replies_pause_safely(self) -> None:
        no_call = ProviderReply(
            content="done",
            assistant_message={"role": "assistant", "content": "done"},
            tool_calls=(),
        )
        service = self.service(FakeProvider(no_call, no_call, no_call))
        paused = service.start(job_payload())
        self.assertEqual("PAUSED", paused["kind"])
        self.assertIn("exactly one", paused["reason"])

        second_path = self.db_path + ".parallel"
        configured = settings(second_path)
        parallel_call = ProviderReply(
            content="",
            assistant_message={"role": "assistant", "content": ""},
            tool_calls=(
                ProviderToolCall("look_around", {}),
                ProviderToolCall("mine", {"count": 1}),
            ),
        )
        parallel = FakeProvider(parallel_call, parallel_call, parallel_call)
        parallel_service = JobService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=parallel,
        )
        paused_parallel = parallel_service.start(job_payload(job_id="job-2"))
        self.assertEqual("PAUSED", paused_parallel["kind"])
        self.assertIn("exactly one", paused_parallel["reason"])

    def test_pause_cancel_status_and_list_use_bounded_flow_projection(self) -> None:
        secret_goal = "sort chests SECRET-GOAL-CONTENT"
        service = self.service(FakeProvider(reply("look_around", {})))
        pending = service.start(job_payload(goal=secret_goal))
        status = service.status({"protocol": 3, "job_id": "job-1"})
        self.assertEqual("ACTION", status["kind"])
        self.assertNotIn("action", status)
        self.assertEqual(pending["progress"], status["progress"])
        listed = service.list_jobs({"protocol": 3})
        self.assertEqual([status], listed["jobs"])
        self.assertNotIn(secret_goal, json.dumps(listed))

        paused = service.pause(
            {
                "protocol": 3,
                "request_id": "pause-1",
                "job_id": "job-1",
                "reason": "operator requested a pause",
            }
        )
        self.assertEqual("PAUSED", paused["kind"])
        canceled = service.cancel(
            {
                "protocol": 3,
                "request_id": "cancel-1",
                "job_id": "job-1",
                "reason": "operator canceled it",
            }
        )
        self.assertEqual("PAUSED", canceled["kind"])
        self.assertEqual("canceled", canceled["reason"])
        self.assertEqual(
            canceled,
            service.cancel(
                {
                    "protocol": 3,
                    "request_id": "cancel-1",
                    "job_id": "job-1",
                    "reason": "operator canceled it",
                }
            ),
        )

    def test_cancel_before_start_tombstones_the_delayed_job(self) -> None:
        provider = FakeProvider(reply("look_around", {}))
        service = self.service(provider)
        canceled = service.cancel(
            {
                "protocol": 3,
                "request_id": "cancel-before-start",
                "job_id": "job-1",
                "reason": "operator canceled immediately",
            }
        )
        self.assertEqual("PAUSED", canceled["kind"])
        self.assertEqual("canceled", canceled["reason"])

        with self.assertRaises(ApiError) as raised:
            service.start(job_payload())
        self.assertEqual("job_not_ready", raised.exception.code)
        self.assertEqual([], provider.calls)

    def test_job_cancel_fence_is_not_evicted_by_dialogue_tombstone_limit(self) -> None:
        provider = FakeProvider(reply("look_around", {}))
        service = self.service(provider, CITIZENS_MAX_TERMINAL_TURNS="1")
        service.cancel(
            {
                "protocol": 3,
                "request_id": "cancel-before-start",
                "job_id": "job-1",
                "reason": "operator canceled immediately",
            }
        )
        service.store.cancel(
            turn_id=None,
            request_id="unrelated-dialogue-cancel",
            now=2.0,
            active_cutoff=-1.0,
            tombstone_cutoff=-1.0,
            max_tombstones=1,
        )

        with self.assertRaises(ApiError) as raised:
            service.start(job_payload())
        self.assertEqual("job_not_ready", raised.exception.code)
        self.assertEqual([], provider.calls)

    def test_cancel_preserves_a_concurrently_completed_job(self) -> None:
        provider = FakeProvider(
            reply("mine", {"count": 1}),
            reply("look_around", {}),
            reply(
                "job_finish",
                {
                    "phase": "complete",
                    "summary": "Work was verified.",
                    "speech": "The work is complete.",
                    "evidence_action_ids": ["placeholder"],
                },
            ),
        )
        service = self.service(provider)
        mutation = service.start(job_payload())
        observation = service.result(
            result_payload(
                "mutation-result",
                "job-1",
                mutation["action"]["id"],
                {"success": True},
            )
        )
        # Finish evidence is generated after action IDs exist.
        finish_call = provider.replies[-1]
        provider.replies[-1] = reply(
            "job_finish",
            {
                "phase": "complete",
                "summary": "Work was verified.",
                "speech": "The work is complete.",
                "evidence_action_ids": [
                    mutation["action"]["id"],
                    observation["action"]["id"],
                ],
            },
        )
        self.assertIsNotNone(finish_call)
        completed = service.result(
            result_payload(
                "observation-result",
                "job-1",
                observation["action"]["id"],
                {"success": True, "verified": True},
            )
        )
        self.assertEqual("COMPLETED", completed["kind"])

        canceled = service.cancel(
            {
                "protocol": 3,
                "request_id": "late-cancel",
                "job_id": "job-1",
                "reason": "operator clicked stop after the reply was lost",
            }
        )
        self.assertEqual(completed, canceled)
        self.assertEqual(completed, service.status({"protocol": 3, "job_id": "job-1"}))

    def test_pause_preserves_a_concurrently_committed_question(self) -> None:
        service = self.service(
            FakeProvider(
                reply(
                    "job_needs_input",
                    {
                        "phase": "materials",
                        "summary": "A material choice is required.",
                        "question": "Should I use oak or spruce?",
                    },
                )
            )
        )
        question = service.start(job_payload(goal="Build a villa."))
        paused = service.pause(
            {
                "protocol": 3,
                "request_id": "pause-after-question",
                "job_id": "job-1",
                "reason": "owner logged out",
            }
        )
        self.assertEqual(question, paused)
        self.assertEqual("NEEDS_INPUT", service.store.get_job("job-1").state)

    def test_result_racing_pause_is_recorded_without_restarting_planning(self) -> None:
        provider = FakeProvider(reply("mine", {"count": 1}))
        service = self.service(provider)
        action = service.start(job_payload())
        service.pause(
            {
                "protocol": 3,
                "request_id": "pause-race",
                "job_id": "job-1",
                "reason": "owner logged out",
            }
        )
        paused = service.result(
            result_payload(
                "late-result",
                "job-1",
                action["action"]["id"],
                {"success": True, "count": 1},
            )
        )
        self.assertEqual("PAUSED", paused["kind"])
        self.assertEqual(1, len(provider.calls))
        stored = service.store.get_job("job-1")
        assert stored is not None
        self.assertEqual("PAUSED", stored.state)
        self.assertEqual(1, stored.actions_completed)
        self.assertIsNone(stored.pending_action)

    def test_old_result_retry_returns_current_pause_not_cached_action(self) -> None:
        provider = FakeProvider(
            reply("mine", {"count": 1}),
            reply("look_around", {}),
        )
        service = self.service(provider)
        first = service.start(job_payload())
        first_result = result_payload(
            "first-result",
            "job-1",
            first["action"]["id"],
            {"success": True},
        )
        next_action = service.result(first_result)
        self.assertEqual("ACTION", next_action["kind"])
        service.pause(
            {
                "protocol": 3,
                "request_id": "pause-after-next-action",
                "job_id": "job-1",
                "reason": "operator paused",
            }
        )

        replay = service.result(first_result)
        self.assertEqual("PAUSED", replay["kind"])
        self.assertNotIn("action", replay)

    def test_failed_read_only_result_does_not_clear_recovery_barrier(self) -> None:
        initial = self.service(FakeProvider(reply("mine", {"count": 1})))
        mutating = initial.start(job_payload())

        provider = FakeProvider(
            reply("look_around", {}),
            reply(
                "job_needs_input",
                {
                    "phase": "recovery",
                    "summary": "Observation failed.",
                    "question": "Should I retry observation?",
                },
            ),
        )
        recovered = self.service(provider)
        observation = recovered.resume(
            resume_payload(
                "resume-uncertain",
                "job-1",
                pending_action_id=mutating["action"]["id"],
                uncertain=True,
            )
        )
        self.assertEqual("look_around", observation["action"]["name"])
        response = recovered.result(
            result_payload(
                "failed-observation",
                "job-1",
                observation["action"]["id"],
                {"success": False, "message": "chunk unavailable"},
            )
        )
        self.assertEqual("NEEDS_INPUT", response["kind"])
        stored = recovered.store.get_job("job-1")
        assert stored is not None
        self.assertTrue(stored.recovery_required)

    def test_loading_a_skill_does_not_clear_recovery_observation_barrier(self) -> None:
        initial = self.service(FakeProvider(reply("mine", {"count": 1})))
        mutating = initial.start(job_payload())
        provider = FakeProvider(
            reply("load_skill", {"skill": "mining"}),
            reply(
                "job_needs_input",
                {
                    "phase": "recovery",
                    "summary": "World observation is still required.",
                    "question": "Should I inspect the area now?",
                },
            ),
        )
        recovered = self.service(provider)
        skill = recovered.resume(
            resume_payload(
                "resume-load-skill",
                "job-1",
                pending_action_id=mutating["action"]["id"],
                uncertain=True,
            )
        )
        self.assertEqual("load_skill", skill["action"]["name"])
        response = recovered.result(
            result_payload(
                "load-skill-result",
                "job-1",
                skill["action"]["id"],
                {"success": True, "workflow": "mining"},
            )
        )
        self.assertEqual("NEEDS_INPUT", response["kind"])
        stored = recovered.store.get_job("job-1")
        assert stored is not None
        self.assertTrue(stored.recovery_required)
        offered = {tool["function"]["name"] for tool in provider.calls[-1][1]}
        self.assertNotIn("mine", offered)
        self.assertNotIn("job_finish", offered)

    def test_failed_mutation_requires_observation_before_another_mutation(self) -> None:
        provider = FakeProvider(
            reply("mine", {"count": 1}),
            reply("look_around", {}),
        )
        service = self.service(provider)
        mutation = service.start(job_payload())
        observation = service.result(
            result_payload(
                "failed-mutation",
                "job-1",
                mutation["action"]["id"],
                {"success": False, "message": "partial path failure"},
            )
        )
        self.assertEqual("look_around", observation["action"]["name"])
        stored = service.store.get_job("job-1")
        assert stored is not None
        self.assertTrue(stored.recovery_required)
        offered = {tool["function"]["name"] for tool in provider.calls[-1][1]}
        self.assertNotIn("mine", offered)
        self.assertNotIn("job_finish", offered)

    def test_recent_context_is_bounded_to_configured_event_window(self) -> None:
        provider = FakeProvider(
            reply("mine", {"count": 1}),
            reply("mine", {"count": 1}),
            reply("mine", {"count": 1}),
        )
        service = self.service(provider, CITIZENS_MAX_JOB_RECENT_EVENTS="2")
        first = service.start(job_payload())
        second = service.result(
            result_payload(
                "r-1",
                "job-1",
                first["action"]["id"],
                {"success": True, "marker": "FIRST"},
            )
        )
        service.result(
            result_payload(
                "r-2",
                "job-1",
                second["action"]["id"],
                {"success": True, "marker": "SECOND"},
            )
        )
        latest_context = json.dumps(provider.calls[-1][0])
        self.assertIn("SECOND", latest_context)
        self.assertNotIn("FIRST", latest_context)

    def test_large_job_action_uses_separate_argument_limit(self) -> None:
        large = "x" * 20_000
        service = self.service(FakeProvider(reply("mine", {"design": large})))
        action = service.start(job_payload())
        self.assertEqual("ACTION", action["kind"])
        self.assertEqual(large, action["action"]["arguments"]["design"])

    def test_waiting_action_survives_store_reopen_without_idle_expiry(self) -> None:
        configured = settings(self.db_path)
        store = SQLiteStore(configured.db_path)
        payload = job_payload()
        # Let normal validation create the row, then leave it in CALLING as a hard-crash fixture.
        service = JobService(settings=configured, store=store, provider=FakeProvider(reply("look_around", {})))
        service.start(payload)
        action = store.get_job("job-1")
        assert action is not None
        # A waiting physical action is intentionally retained across sidecar restart.
        reopened = SQLiteStore(configured.db_path).get_job("job-1")
        assert reopened is not None
        self.assertEqual("WAITING_ACTION", reopened.state)

    def test_calling_model_pass_is_recovered_to_ready_after_store_reopen(self) -> None:
        configured = settings(self.db_path)
        store = SQLiteStore(configured.db_path)
        payload = job_payload()
        created, _ = store.create_job(
            job_id=payload["job_id"],
            request_id=payload["request_id"],
            input_hash="fixture-input-hash",
            citizen=payload["citizen"],
            actor=payload["actor"],
            goal=payload["goal"],
            tools=payload["tools"],
            budgets=payload["budgets"],
            now=1.0,
            max_active_jobs=configured.max_active_jobs,
        )
        self.assertTrue(created)
        self.assertEqual("CALLING", store.begin_job_model_call("job-1", now=2.0).state)

        recovered = SQLiteStore(configured.db_path).get_job("job-1")
        assert recovered is not None
        self.assertEqual("READY", recovered.state)
        self.assertEqual(1, recovered.model_calls)


if __name__ == "__main__":
    unittest.main()
