from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from citizen_brain.config import Settings


class SettingsTest(unittest.TestCase):
    def test_file_secrets_and_default_single_concurrency(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            brain_secret = root / "brain-token"
            llm_secret = root / "llm-key"
            brain_secret.write_text("brain-secret\n", encoding="utf-8")
            llm_secret.write_text("llm-secret\n", encoding="utf-8")
            configured = Settings.from_env(
                {
                    "CITIZENS_LLM_MODEL": "model",
                    "CITIZENS_BRAIN_TOKEN_FILE": str(brain_secret),
                    "CITIZENS_LLM_API_KEY_FILE": str(llm_secret),
                }
            )
        self.assertEqual("brain-secret", configured.brain_token)
        self.assertEqual("llm-secret", configured.llm_api_key)
        self.assertEqual(1, configured.llm_concurrency)
        self.assertEqual(20, configured.llm_queue_timeout_seconds)
        self.assertEqual(90, configured.llm_timeout_seconds)
        self.assertLessEqual(
            configured.llm_queue_timeout_seconds + configured.llm_timeout_seconds,
            110,
        )
        self.assertEqual(2_048, configured.max_speech_chars)
        self.assertEqual(86_400, configured.terminal_turn_ttl_seconds)
        self.assertEqual(1_000, configured.max_terminal_turns)

    def test_direct_and_file_secret_cannot_both_be_set(self) -> None:
        with self.assertRaisesRegex(ValueError, "set only one"):
            Settings.from_env(
                {
                    "CITIZENS_LLM_MODEL": "model",
                    "CITIZENS_BRAIN_TOKEN": "one",
                    "CITIZENS_BRAIN_TOKEN_FILE": "unused",
                }
            )

    def test_speech_limit_cannot_exceed_minecraft_protocol_limit(self) -> None:
        with self.assertRaisesRegex(ValueError, "between 1 and 2048"):
            Settings.from_env(
                {
                    "CITIZENS_LLM_MODEL": "model",
                    "CITIZENS_BRAIN_TOKEN": "token",
                    "CITIZENS_MAX_SPEECH_CHARS": "2049",
                }
            )

    def test_api_key_requires_https_but_keyless_local_ollama_may_use_http(self) -> None:
        with self.assertRaisesRegex(ValueError, "requires an https"):
            Settings.from_env(
                {
                    "CITIZENS_LLM_MODEL": "model",
                    "CITIZENS_BRAIN_TOKEN": "token",
                    "CITIZENS_LLM_URL": "http://ollama.example/api/chat",
                    "CITIZENS_LLM_API_KEY": "secret",
                }
            )
        configured = Settings.from_env(
            {
                "CITIZENS_LLM_MODEL": "model",
                "CITIZENS_BRAIN_TOKEN": "token",
                "CITIZENS_LLM_URL": "http://127.0.0.1:11434/api/chat",
            }
        )
        self.assertIsNone(configured.llm_api_key)


if __name__ == "__main__":
    unittest.main()
