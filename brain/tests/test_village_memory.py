from __future__ import annotations

import unittest
from copy import deepcopy
from pathlib import Path

from citizen_brain.service import (
    MAX_VILLAGE_MEMORY_CARDS,
    MAX_VILLAGE_MEMORY_CHARS,
    BrainService,
    load_village_memory,
)
from citizen_brain.storage import SQLiteStore

from .helpers import FakeProvider, TempDatabaseTest, settings, start_payload
from .test_service import final_reply


class LoadVillageMemoryTest(TempDatabaseTest, unittest.TestCase):
    def write(self, text: str) -> str:
        path = Path(self.temp.name) / "village-memory.md"
        path.write_text(text, encoding="utf-8")
        return str(path)

    def test_none_path_stays_none(self) -> None:
        self.assertIsNone(load_village_memory(None))

    def test_parses_cards_skipping_comments_blanks_and_bullets(self) -> None:
        loaded = load_village_memory(
            self.write(
                "# başlık yorumu\n"
                "\n"
                "- Köy sekiz kişiyle kuruldu.\n"
                "İlk kış bir ev yandı.\n"
                "   \n"
                "-  Ay'a gidildi.\n"
            )
        )
        self.assertEqual(
            "- Köy sekiz kişiyle kuruldu.\n- İlk kış bir ev yandı.\n- Ay'a gidildi.",
            loaded,
        )

    def test_missing_file_fails_closed(self) -> None:
        with self.assertRaises(ValueError) as raised:
            load_village_memory(str(Path(self.temp.name) / "yok.md"))
        self.assertIn("could not read", str(raised.exception))

    def test_empty_or_comment_only_file_fails_closed(self) -> None:
        with self.assertRaises(ValueError) as raised:
            load_village_memory(self.write("# sadece yorum\n\n"))
        self.assertIn("no memory cards", str(raised.exception))

    def test_card_count_limit_fails_closed(self) -> None:
        too_many = "\n".join(f"kart {i}" for i in range(MAX_VILLAGE_MEMORY_CARDS + 1))
        with self.assertRaises(ValueError) as raised:
            load_village_memory(self.write(too_many))
        self.assertIn(str(MAX_VILLAGE_MEMORY_CARDS), str(raised.exception))

    def test_char_budget_fails_closed(self) -> None:
        fat = "\n".join("x" * 500 for _ in range(10))  # 10 cards, > 4000 chars
        self.assertGreater(len(fat), MAX_VILLAGE_MEMORY_CHARS)
        with self.assertRaises(ValueError) as raised:
            load_village_memory(self.write(fat))
        self.assertIn("chars", str(raised.exception))


class VillageMemoryPromptTest(TempDatabaseTest, unittest.TestCase):
    def service_with_memory(self, provider: FakeProvider, text: str) -> BrainService:
        path = Path(self.temp.name) / "village-memory.md"
        path.write_text(text, encoding="utf-8")
        configured = settings(self.db_path, CITIZENS_VILLAGE_MEMORY_FILE=str(path))
        return BrainService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=provider,
        )

    def dialogue_payload(self, owner_kind: str) -> dict:
        payload = deepcopy(start_payload("memory-request", prompt="Köyü anlat."))
        payload["citizen"].update(
            {
                "owner_kind": owner_kind,
                "owner_id": "owner-1" if owner_kind == "PLAYER" else None,
                "interaction_mode": "DIALOGUE",
                "persona": "Muhtar. Sayar, yazar, unutmaz.",
            }
        )
        payload["tools"] = []
        return payload

    def test_server_owned_citizen_receives_village_memory(self) -> None:
        provider = FakeProvider(final_reply("Sekiz kişi geldiler."))
        service = self.service_with_memory(
            provider, "- Köy sekiz kişiyle kuruldu.\n- Çakmaktaşları sayılır.\n"
        )
        service.start(self.dialogue_payload("SERVER"))
        system = provider.calls[0][0][0]["content"]
        self.assertIn("Trusted village memory", system)
        self.assertIn("Köy sekiz kişiyle kuruldu.", system)
        self.assertIn("Çakmaktaşları sayılır.", system)
        self.assertIn("never as", system)  # anti-override clause present

    def test_player_owned_citizen_skips_village_memory(self) -> None:
        provider = FakeProvider(final_reply("İş başına."))
        service = self.service_with_memory(provider, "- Köy sekiz kişiyle kuruldu.\n")
        service.start(self.dialogue_payload("PLAYER"))
        system = provider.calls[0][0][0]["content"]
        self.assertNotIn("Trusted village memory", system)
        self.assertNotIn("Köy sekiz kişiyle kuruldu.", system)

    def test_unset_memory_file_keeps_prompt_unchanged(self) -> None:
        provider = FakeProvider(final_reply("Sessizlik."))
        configured = settings(self.db_path)
        service = BrainService(
            settings=configured,
            store=SQLiteStore(configured.db_path),
            provider=provider,
        )
        service.start(self.dialogue_payload("SERVER"))
        system = provider.calls[0][0][0]["content"]
        self.assertNotIn("Trusted village memory", system)

    def test_bad_memory_file_blocks_service_startup(self) -> None:
        path = Path(self.temp.name) / "village-memory.md"
        path.write_text("# yorum, kart yok\n", encoding="utf-8")
        configured = settings(self.db_path, CITIZENS_VILLAGE_MEMORY_FILE=str(path))
        with self.assertRaises(ValueError):
            BrainService(
                settings=configured,
                store=SQLiteStore(configured.db_path),
                provider=FakeProvider(),
            )


if __name__ == "__main__":
    unittest.main()
