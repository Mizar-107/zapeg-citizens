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

    def test_turkish_axe_pause_phrase_is_rejected(self) -> None:
        # Regression for the live wood-chopping failure, in Turkish: the model
        # pauses a "topla odun" job asking for a balta (axe).
        fault = optional_harvest_tool_fault("8 odun topla", "Bir balta lazım")
        self.assertEqual(HAND_HARVEST_FAULT, fault)
        # Case-insensitive including Turkish İ/ı folding and suffixed forms.
        self.assertIsNotNone(
            optional_harvest_tool_fault("8 ODUN TOPLA", "BALTAYA İHTİYACIM VAR")
        )
        self.assertIsNotNone(
            optional_harvest_tool_fault(
                "kütük kes ve getir", "Baltam yok, bana bir balta atar mısın?"
            )
        )
        # Question-side punchable nouns carry the net without a wood goal.
        self.assertIsNotNone(
            optional_harvest_tool_fault(
                "şu ağacı hallet", "Odun kırmak için bir balta gerekiyor."
            )
        )
        # Shovels and shears are optional in Turkish too.
        self.assertIsNotNone(
            optional_harvest_tool_fault(
                "biraz topla", "Kum kazmak için bir kürek lazım."
            )
        )

    def test_sugar_cane_hoe_requests_are_rejected(self) -> None:
        # Regression for the 2026-08-21 live failure: a "şeker kamışı topla"
        # job paused asking the owner for a çapa (hoe), then re-asked after
        # the owner answered "elinle kaz". Both asks must fault server-side.
        fault = optional_harvest_tool_fault("şeker kamışı topla", "Bir çapa lazım")
        self.assertEqual(HAND_HARVEST_FAULT, fault)
        self.assertIn("hoe", fault)
        # The repeat ask after the actor's bare-hands answer faults too.
        self.assertIsNotNone(
            optional_harvest_tool_fault(
                "şeker kamışı topla", "Çapa olmadan kamış toplayamam."
            )
        )
        # English hoe phrasing and folded/uppercase Turkish.
        self.assertIsNotNone(
            optional_harvest_tool_fault(
                "collect 20 sugar cane", "I need a hoe to harvest sugar cane."
            )
        )
        self.assertIsNotNone(
            optional_harvest_tool_fault("KAMIŞ KES", "ÇAPAYA İHTİYACIM VAR")
        )
        # Crops family rides the same net.
        self.assertIsNotNone(
            optional_harvest_tool_fault("buğday hasat et", "Orak gerekiyor.")
        )
        # A pickaxe ask on a cane goal is still nonsense-tool → also faulted
        # via the question-side punchable noun.
        self.assertIsNotNone(
            optional_harvest_tool_fault(
                "taş kaz", "Şeker kamışı kırmak için makas lazım."
            )
        )

    def test_turkish_pickaxe_requirement_stays_a_real_blocker(self) -> None:
        self.assertIsNone(
            optional_harvest_tool_fault(
                "5 elmas kaz", "Demir kazma lazım, elmas kazamam."
            )
        )
        self.assertIsNone(
            optional_harvest_tool_fault("20 demir cevheri kaz", "Bir kazma gerekiyor.")
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
        self.assertIsNone(
            optional_harvest_tool_fault("bir ev yap", "64 taş ve 32 tahta lazım.")
        )
