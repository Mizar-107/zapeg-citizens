from __future__ import annotations

import unittest

from citizen_brain.instruction_policy import (
    PREMATURE_FINISH_FAULT,
    SEQUENCE_REISSUE_FAULT,
    premature_finish_fault,
    sequence_reissue_fault,
)


class InstructionPolicyTest(unittest.TestCase):
    def test_chest_delivery_cannot_finish_after_only_mine(self) -> None:
        fault = premature_finish_fault(
            "Gather 8 wood then put it in the chest.",
            [
                {"action_name": "mine", "success": True},
                {"action_name": "look_around", "success": True},
            ],
        )
        self.assertEqual(PREMATURE_FINISH_FAULT, fault)

    def test_chest_delivery_can_finish_after_a_storage_move(self) -> None:
        self.assertIsNone(
            premature_finish_fault(
                "Gather 8 wood then put it in the chest.",
                [
                    {"action_name": "mine", "success": True},
                    {"action_name": "transfer", "success": True},
                    {"action_name": "inspect_gui", "success": True},
                ],
            )
        )

    def test_one_step_punch_does_not_require_storage(self) -> None:
        self.assertIsNone(
            premature_finish_fault(
                "Punch that oak log.",
                [
                    {"action_name": "mine", "success": True},
                    {"action_name": "look_around", "success": True},
                ],
            )
        )

    def test_craft_sequence_requires_a_craft_action(self) -> None:
        self.assertEqual(
            PREMATURE_FINISH_FAULT,
            premature_finish_fault(
                "Gather oak logs then craft sticks.",
                [{"action_name": "mine", "success": True}],
            ),
        )
        self.assertIsNone(
            premature_finish_fault(
                "Gather oak logs then craft sticks.",
                [
                    {"action_name": "mine", "success": True},
                    {"action_name": "craft", "success": True},
                ],
            )
        )

    def test_asking_the_player_to_continue_is_rejected(self) -> None:
        self.assertEqual(
            SEQUENCE_REISSUE_FAULT,
            sequence_reissue_fault(
                "Gather 8 wood then put it in the chest.",
                "Wood is gathered. Say continue and I will put it in the chest.",
            ),
        )
        self.assertIsNone(
            sequence_reissue_fault(
                "Build a villa here.",
                "Should the villa use spruce or oak?",
            )
        )
        self.assertIsNone(
            sequence_reissue_fault(
                "Mine five diamonds.",
                "I need an iron pickaxe before I can mine diamond ore.",
            )
        )
