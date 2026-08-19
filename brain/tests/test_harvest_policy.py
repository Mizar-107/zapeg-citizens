from __future__ import annotations

import unittest

from citizen_brain.harvest_policy import HAND_HARVEST_FAULT, optional_harvest_tool_fault


class HarvestPolicyTest(unittest.TestCase):
    def test_wood_jobs_reject_an_axe_requirement(self) -> None:
        fault = optional_harvest_tool_fault(
            "Go chop some wood.",
            "I need an axe in my inventory before I can chop wood.",
        )
        self.assertEqual(HAND_HARVEST_FAULT, fault)
        self.assertIn("hand-breakable", fault)
        self.assertIsNotNone(
            optional_harvest_tool_fault("Gather oak logs", "Please toss me a diamond axe.")
        )

    def test_stone_and_build_requirements_remain_valid(self) -> None:
        self.assertIsNone(
            optional_harvest_tool_fault(
                "Mine five diamonds.",
                "I need an iron pickaxe before I can mine diamond ore.",
            )
        )
        self.assertIsNone(
            optional_harvest_tool_fault(
                "Build a villa here.",
                "I need 64 cobblestone and 32 spruce planks in my inventory.",
            )
        )
        self.assertIsNone(optional_harvest_tool_fault("Go chop some wood.", "Which logs, oak or birch?"))
