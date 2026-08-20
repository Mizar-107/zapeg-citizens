from __future__ import annotations

import json
import unittest

from citizen_brain.job_service import JobService
from citizen_brain.job_templates import (
    BLUEPRINTS,
    ORE_BLOCKS,
    advance_stages,
    current_stage,
    detect_template,
    is_final_complete,
    rearm_stage_budget,
    stage_budget_exhausted,
    stage_context,
)
from citizen_brain.storage import SQLiteStore

from tests.helpers import FakeProvider, TempDatabaseTest, settings
from tests.test_jobs import WORLD_TOOLS, job_payload, reply, result_payload, resume_payload


class TemplateDetectionTest(unittest.TestCase):
    def test_explicit_syntax_always_wins(self) -> None:
        template = detect_template("template:gather_wood n=40")
        self.assertIsNotNone(template)
        self.assertEqual("gather_wood", template["name"])
        self.assertEqual(40, template["params"]["count"])

        template = detect_template("template:mine_ore type=iron n=12")
        self.assertEqual("mine_ore", template["name"])
        self.assertEqual("iron", template["params"]["ore"])
        self.assertEqual(12, template["params"]["count"])

        template = detect_template("template:simple_build blueprint=storage_hut")
        self.assertEqual("simple_build", template["name"])
        self.assertEqual("storage_hut", template["params"]["blueprint"])

        self.assertIsNone(detect_template("template:mine_ore type=mithril n=2"))
        self.assertIsNone(detect_template("template:simple_build blueprint=castle"))

    def test_natural_english_and_turkish_phrasings(self) -> None:
        for goal, name, count in [
            ("Gather 16 wood for the camp", "gather_wood", 16),
            ("chop 8 logs please", "gather_wood", 8),
            ("32 odun topla", "gather_wood", 32),
            ("bana 10 kütük kesip getir", "gather_wood", 10),
        ]:
            template = detect_template(goal)
            self.assertIsNotNone(template, goal)
            self.assertEqual(name, template["name"], goal)
            self.assertEqual(count, template["params"]["count"], goal)

        template = detect_template("Mine 5 diamond ore and bring it back")
        self.assertEqual("mine_ore", template["name"])
        self.assertEqual("diamond", template["params"]["ore"])
        self.assertEqual(5, template["params"]["count"])

        template = detect_template("mine 12 diamonds")
        self.assertEqual("mine_ore", template["name"])
        self.assertEqual("diamond", template["params"]["ore"])
        self.assertEqual(12, template["params"]["count"])

        template = detect_template("20 demir cevheri kaz")
        self.assertEqual("mine_ore", template["name"])
        self.assertEqual("iron", template["params"]["ore"])
        self.assertEqual(20, template["params"]["count"])

        template = detect_template("Build a shelter hut here")
        self.assertEqual("simple_build", template["name"])
        self.assertEqual("shelter_hut", template["params"]["blueprint"])

        template = detect_template("şuraya bir gözetleme kulesi yap")
        self.assertEqual("simple_build", template["name"])
        self.assertEqual("watchtower", template["params"]["blueprint"])

    def test_freeform_goals_stay_untemplated(self) -> None:
        for goal in [
            "Sort every chest around here into categories",
            "Mine five diamonds and report the confirmed count.",
            "Follow me and protect me",
            "Build a two-storey Mediterranean villa on this plot",
            "kill 10 zombies",
            None,
            "",
        ]:
            self.assertIsNone(detect_template(goal), goal)

    def test_counts_are_clamped_and_bills_are_complete(self) -> None:
        template = detect_template("gather 9999 logs")
        self.assertEqual(256, template["params"]["count"])
        template = detect_template("template:mine_ore type=gold n=9999")
        self.assertEqual(128, template["params"]["count"])

        for name, spec in BLUEPRINTS.items():
            self.assertTrue(spec["bill"], name)
            for item, count in spec["bill"].items():
                self.assertRegex(item, r"^minecraft:[a-z_]+$")
                self.assertGreater(count, 0)
        for ore, blocks in ORE_BLOCKS.items():
            self.assertEqual(2, len(blocks), ore)


class StageMachineTest(unittest.TestCase):
    @staticmethod
    def _confirmed(*entries: tuple[int, str, bool]) -> list[dict]:
        return [
            {"event_id": event_id, "action_id": f"a{event_id}", "action_name": name, "success": success}
            for event_id, name, success in entries
        ]

    def test_stages_advance_only_on_ordered_successful_evidence(self) -> None:
        template = detect_template("gather 4 logs")
        # A failed scan does not advance the survey stage.
        updated, advanced = advance_stages(
            template, self._confirmed((1, "scan_blocks", False)), 1
        )
        self.assertFalse(advanced)
        self.assertEqual("survey", current_stage(updated)["name"])

        # One successful mine proves the logs were found AND chops them: the
        # same event satisfies survey and chop, landing on verify_deliver.
        updated, advanced = advance_stages(
            template, self._confirmed((1, "scan_blocks", False), (2, "mine", True)), 2
        )
        self.assertTrue(advanced)
        self.assertEqual("verify_deliver", current_stage(updated)["name"])
        self.assertFalse(is_final_complete(updated))

        # Evidence recorded before the stage began can never satisfy it: the
        # get_self_status from event 1 does not complete verify_deliver.
        early_status = self._confirmed(
            (1, "get_self_status", True), (2, "mine", True)
        )
        updated, _ = advance_stages(template, early_status, 2)
        self.assertEqual("verify_deliver", current_stage(updated)["name"])
        self.assertFalse(is_final_complete(updated))

        # A verification after the mine completes the final stage.
        full = self._confirmed(
            (1, "scan_blocks", True), (2, "mine", True), (3, "get_self_status", True)
        )
        updated, advanced = advance_stages(template, full, 3)
        self.assertTrue(advanced)
        self.assertTrue(is_final_complete(updated))

    def test_stage_budget_exhaustion_and_rearm(self) -> None:
        template = detect_template("gather 4 logs")
        limit = current_stage(template)["max_actions"]
        self.assertFalse(stage_budget_exhausted(template, limit - 1))
        self.assertTrue(stage_budget_exhausted(template, limit))

        rearmed = rearm_stage_budget(template, limit)
        self.assertFalse(stage_budget_exhausted(rearmed, limit))
        self.assertTrue(stage_budget_exhausted(rearmed, 2 * limit))

        done, _ = advance_stages(
            template,
            self._confirmed(
                (1, "mine", True), (2, "get_self_status", True)
            ),
            2,
        )
        self.assertTrue(is_final_complete(done))
        self.assertFalse(stage_budget_exhausted(done, 10_000))

    def test_stage_context_is_compact_and_complete(self) -> None:
        template = detect_template("template:mine_ore type=iron n=8")
        context = stage_context(template, 0)
        self.assertEqual("mine_ore", context["template"])
        self.assertEqual("preflight", context["stage"])
        self.assertEqual(1, context["stage_number"])
        self.assertEqual(3, context["stage_total"])
        self.assertIn("pickaxe", context["stage_goal"])
        self.assertIn("minecraft:iron_ore", context["stage_goal"])
        self.assertFalse(context["final_stage_complete"])

    def test_strict_build_stages_each_require_their_own_build_success(self) -> None:
        template = detect_template("template:simple_build blueprint=watchtower")
        # survey done, then one successful build: foundation advances (its
        # evidence window is non-strict) but the strict walls stage must NOT be
        # satisfied by the same build event.
        evidence = self._confirmed(
            (1, "look_around", True),
            (2, "get_self_status", True),
            (3, "build", True),
        )
        updated, advanced = advance_stages(template, evidence, 3)
        self.assertTrue(advanced)
        self.assertEqual("walls", current_stage(updated)["name"])

        # A second later build satisfies walls only; a third finishes roof.
        evidence = self._confirmed(
            (1, "look_around", True),
            (2, "get_self_status", True),
            (3, "build", True),
            (4, "build", True),
        )
        updated, _ = advance_stages(template, evidence, 4)
        self.assertEqual("roof", current_stage(updated)["name"])

        evidence = self._confirmed(
            (1, "look_around", True),
            (2, "get_self_status", True),
            (3, "build", True),
            (4, "build", True),
            (5, "build", True),
            (6, "inspect_block", True),
        )
        updated, _ = advance_stages(template, evidence, 6)
        self.assertTrue(is_final_complete(updated))

    def test_simple_build_materials_stage_names_the_bill_and_waits(self) -> None:
        template = detect_template("template:simple_build blueprint=shelter_hut")
        materials = template["stages"][1]
        self.assertEqual("materials", materials["name"])
        for item in BLUEPRINTS["shelter_hut"]["bill"]:
            self.assertIn(item, materials["goal"])
        self.assertIn("job_needs_input", materials["goal"])
        self.assertIn("resumes automatically", materials["goal"])


class TemplateJobFlowTest(TempDatabaseTest, unittest.TestCase):
    def service(self, provider: FakeProvider, **overrides: str) -> JobService:
        configured = settings(self.db_path, **overrides)
        return JobService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=provider,
        )

    def test_template_job_runs_stage_by_stage_to_completion(self) -> None:
        provider = FakeProvider(reply("look_around", {}))
        service = self.service(provider)
        started = service.start(job_payload(goal="Gather 6 logs for me"))
        self.assertEqual("ACTION", started["kind"])
        self.assertEqual("look_around", started["action"]["name"])
        first_context = provider.calls[-1][0][1]["content"]
        self.assertIn("template_stage", first_context)
        self.assertIn("gather_wood", first_context)
        self.assertIn('"stage":"survey"', first_context)
        self.assertIn("staged template", provider.calls[-1][0][0]["content"])

        provider.replies.append(reply("mine", {"block_ids": ["minecraft:oak_log"], "count": 6}))
        survey_done = service.result(
            result_payload("r1", "job-1", started["action"]["id"], {"success": True, "blocks": ["minecraft:oak_log"]})
        )
        self.assertEqual("mine", survey_done["action"]["name"])
        self.assertIn('"stage":"chop"', provider.calls[-1][0][1]["content"])

        provider.replies.append(reply("get_self_status", {}))
        chop_done = service.result(
            result_payload("r2", "job-1", survey_done["action"]["id"], {"success": True, "count": 6})
        )
        self.assertEqual("get_self_status", chop_done["action"]["name"])
        self.assertIn('"stage":"verify_deliver"', provider.calls[-1][0][1]["content"])

        provider.replies.append(
            reply(
                "job_finish",
                {
                    "phase": "complete",
                    "summary": "Six logs gathered and verified.",
                    "speech": "Six logs are in my bag.",
                    "evidence_action_ids": [
                        survey_done["action"]["id"],
                        chop_done["action"]["id"],
                    ],
                },
            )
        )
        finished = service.result(
            result_payload(
                "r3",
                "job-1",
                chop_done["action"]["id"],
                {"success": True, "inventory": {"minecraft:oak_log": 6}},
            )
        )
        self.assertEqual("COMPLETED", finished["kind"])

    def test_finish_is_rejected_until_the_final_stage_is_complete(self) -> None:
        provider = FakeProvider(reply("mine", {"block_ids": ["minecraft:oak_log"], "count": 2}))
        service = self.service(provider)
        started = service.start(job_payload(goal="chop 2 logs"))
        mine_id = started["action"]["id"]

        provider.replies.append(
            reply(
                "job_finish",
                {
                    "phase": "complete",
                    "summary": "Logs chopped.",
                    "speech": "Done.",
                    "evidence_action_ids": [mine_id],
                },
            )
        )
        provider.replies.append(reply("get_self_status", {}))
        response = service.result(
            result_payload("r1", "job-1", mine_id, {"success": True, "count": 2})
        )
        # The premature finish became a planner fault; the planner was re-asked
        # and moved to the verification the final stage requires.
        self.assertEqual("ACTION", response["kind"])
        self.assertEqual("get_self_status", response["action"]["name"])
        follow_up = json.dumps(provider.calls[-1][0], ensure_ascii=False)
        self.assertIn("job_finish is rejected", follow_up)
        self.assertIn("verify_deliver", follow_up)

    def test_stage_budget_pause_and_resume_rearm(self) -> None:
        provider = FakeProvider(reply("scan_blocks", {}))
        service = self.service(provider)
        started = service.start(job_payload(goal="template:gather_wood n=4"))
        scan_id = started["action"]["id"]

        # Deterministically shrink every stage budget to one action, then fail
        # the pending action so no stage can advance past survey.
        job = service.store.get_job("job-1")
        template = dict(job.template)
        template["stages"] = [
            {**stage, "max_actions": 1} for stage in template["stages"]
        ]
        service.store.update_job_template(
            job_id="job-1",
            template=template,
            event_type="stage_rearmed",
            payload={"test": "shrink budgets"},
            now=0.0,
        )
        paused = service.result(
            result_payload("r1", "job-1", scan_id, {"success": False, "message": "no scan"})
        )
        self.assertEqual("PAUSED", paused["kind"])
        self.assertTrue(paused["reason"].startswith("stage_budget_exhausted"))
        self.assertIn("survey", paused["reason"])

        # An explicit resume re-arms the current stage window and planning
        # continues instead of pausing again immediately.
        provider.replies.append(reply("look_around", {}))
        resumed = service.resume(
            resume_payload(
                "resume-1",
                "job-1",
                answer="Keep going, the trees are right there.",
                actions_completed=1,
            )
        )
        self.assertEqual("ACTION", resumed["kind"])
        self.assertEqual("look_around", resumed["action"]["name"])

    def test_materials_blocker_waits_and_resumes_within_the_same_stage(self) -> None:
        provider = FakeProvider(reply("look_around", {}))
        service = self.service(provider)
        started = service.start(
            job_payload(goal="template:simple_build blueprint=shelter_hut")
        )
        self.assertEqual("look_around", started["action"]["name"])

        # Survey evidence lands the job in the materials stage, where the model
        # declares the missing bill through the blocker channel and waits.
        provider.replies.append(
            reply(
                "job_needs_input",
                {
                    "phase": "materials",
                    "summary": "Materials are missing for the shelter hut.",
                    "question": "I need 96x minecraft:oak_planks and 36x "
                    "minecraft:oak_slab before I can start building.",
                },
            )
        )
        blocked = service.result(
            result_payload("r1", "job-1", started["action"]["id"], {"success": True})
        )
        self.assertEqual("NEEDS_INPUT", blocked["kind"])
        self.assertIn("oak_planks", blocked["question"])

        # The Forge inventory auto-resume answers with the supplied change; the
        # job continues inside the same materials stage.
        provider.replies.append(reply("get_self_status", {}))
        resumed = service.resume(
            resume_payload(
                "resume-materials",
                "job-1",
                answer="The citizen's inventory changed while paused: "
                "+96x minecraft:oak_planks, +36x minecraft:oak_slab.",
                actions_completed=1,
            )
        )
        self.assertEqual("ACTION", resumed["kind"])
        self.assertEqual("get_self_status", resumed["action"]["name"])
        context = provider.calls[-1][0][1]["content"]
        self.assertIn('"stage":"materials"', context)

    def test_template_state_survives_a_brain_restart(self) -> None:
        provider = FakeProvider(reply("mine", {"block_ids": ["minecraft:oak_log"], "count": 3}))
        service = self.service(provider)
        started = service.start(job_payload(goal="Gather 3 logs now"))
        provider.replies.append(reply("get_self_status", {}))
        moved = service.result(
            result_payload("r1", "job-1", started["action"]["id"], {"success": True, "count": 3})
        )
        self.assertEqual("get_self_status", moved["action"]["name"])

        # Simulate a restart: a brand-new service over the same database must
        # keep the persisted template and resume inside verify_deliver.
        restarted_provider = FakeProvider()
        configured = settings(self.db_path)
        restarted = JobService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=restarted_provider,
        )
        response = restarted.resume(
            resume_payload(
                "resume-restart",
                "job-1",
                pending_action_id=moved["action"]["id"],
                uncertain=True,
                actions_completed=1,
            )
        )
        # The pending read-only observation is re-emitted without a model call.
        self.assertEqual("ACTION", response["kind"])
        self.assertEqual(moved["action"]["id"], response["action"]["id"])
        self.assertEqual([], restarted_provider.calls)

        # Its confirmed result then reaches the planner with the persisted
        # template still on the final verify_deliver stage (now complete).
        restarted_provider.replies.append(reply("look_around", {}))
        follow_up = restarted.result(
            result_payload(
                "r-restart",
                "job-1",
                response["action"]["id"],
                {"success": True, "inventory": {"minecraft:oak_log": 3}},
            )
        )
        self.assertEqual("ACTION", follow_up["kind"])
        context = restarted_provider.calls[-1][0][1]["content"]
        self.assertIn("template_stage", context)
        self.assertIn('"stage":"verify_deliver"', context)
        self.assertIn('"final_stage_complete":true', context)

    def test_queued_batch_from_a_previous_stage_is_discarded_on_advance(self) -> None:
        batch = reply("mine", {"block_ids": ["minecraft:oak_log"], "count": 2})
        batch = batch.__class__(
            content="",
            assistant_message=batch.assistant_message,
            tool_calls=(
                batch.tool_calls[0],
                type(batch.tool_calls[0])("look_around", {}),
            ),
        )
        provider = FakeProvider(batch)
        service = self.service(provider)
        started = service.start(job_payload(goal="chop 2 logs"))
        self.assertEqual("mine", started["action"]["name"])

        # The mine succeeds, satisfying survey+chop; the queued look_around was
        # planned for the old stage and is discarded, so the planner is
        # re-asked inside verify_deliver instead of blindly running it.
        provider.replies.append(reply("get_self_status", {}))
        response = service.result(
            result_payload("r1", "job-1", started["action"]["id"], {"success": True, "count": 2})
        )
        self.assertEqual("get_self_status", response["action"]["name"])
        job = service.store.get_job("job-1")
        self.assertEqual([], job.action_queue)


if __name__ == "__main__":
    unittest.main()
