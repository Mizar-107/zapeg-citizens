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
        self.assertEqual(4_096, configured.max_tool_description_chars)
        self.assertEqual(4_096, configured.max_persona_chars)
        self.assertEqual(262_144, configured.max_tool_argument_chars)
        self.assertEqual(86_400, configured.terminal_turn_ttl_seconds)
        self.assertEqual(1_000, configured.max_terminal_turns)
        self.assertEqual(16, configured.max_active_jobs)
        self.assertEqual(65_536, configured.max_job_context_chars)
        self.assertEqual(16_384, configured.max_job_checkpoint_chars)
        self.assertEqual(8, configured.max_job_recent_events)
        self.assertEqual(8, configured.max_job_internal_steps)

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

    def test_tool_description_limit_is_configurable_but_bounded(self) -> None:
        configured = Settings.from_env(
            {
                "CITIZENS_LLM_MODEL": "model",
                "CITIZENS_BRAIN_TOKEN": "token",
                "CITIZENS_MAX_TOOL_DESCRIPTION_CHARS": "8192",
            }
        )
        self.assertEqual(8_192, configured.max_tool_description_chars)

        with self.assertRaisesRegex(ValueError, "between 1 and 65536"):
            Settings.from_env(
                {
                    "CITIZENS_LLM_MODEL": "model",
                    "CITIZENS_BRAIN_TOKEN": "token",
                    "CITIZENS_MAX_TOOL_DESCRIPTION_CHARS": "65537",
                }
            )

    def test_persona_limit_is_configurable_but_bounded(self) -> None:
        configured = Settings.from_env(
            {
                "CITIZENS_LLM_MODEL": "model",
                "CITIZENS_BRAIN_TOKEN": "token",
                "CITIZENS_MAX_PERSONA_CHARS": "8192",
            }
        )
        self.assertEqual(8_192, configured.max_persona_chars)

        with self.assertRaisesRegex(ValueError, "between 1 and 16384"):
            Settings.from_env(
                {
                    "CITIZENS_LLM_MODEL": "model",
                    "CITIZENS_BRAIN_TOKEN": "token",
                    "CITIZENS_MAX_PERSONA_CHARS": "16385",
                }
            )

    def test_job_action_argument_limit_is_separate_and_bounded(self) -> None:
        configured = Settings.from_env(
            {
                "CITIZENS_LLM_MODEL": "model",
                "CITIZENS_BRAIN_TOKEN": "token",
                "CITIZENS_MAX_RESULT_CHARS": "16000",
                "CITIZENS_MAX_TOOL_ARGUMENT_CHARS": "524288",
            }
        )
        self.assertEqual(16_000, configured.max_result_chars)
        self.assertEqual(524_288, configured.max_tool_argument_chars)

        with self.assertRaisesRegex(ValueError, "between 1024 and 1048576"):
            Settings.from_env(
                {
                    "CITIZENS_LLM_MODEL": "model",
                    "CITIZENS_BRAIN_TOKEN": "token",
                    "CITIZENS_MAX_TOOL_ARGUMENT_CHARS": "1048577",
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
