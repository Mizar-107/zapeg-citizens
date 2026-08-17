from __future__ import annotations

from contextlib import closing
import sqlite3
import unittest

from citizen_brain.storage import SQLiteStore

from tests.helpers import TempDatabaseTest


class SQLiteMigrationTest(TempDatabaseTest, unittest.TestCase):
    def test_v1_actor_scoped_index_migrates_to_one_active_turn_per_citizen(self) -> None:
        SQLiteStore(self.db_path)
        with closing(sqlite3.connect(self.db_path)) as connection:
            connection.execute("DROP INDEX one_active_turn_per_citizen")
            connection.execute(
                """
                CREATE UNIQUE INDEX one_active_turn_per_session
                    ON turns(citizen_id, actor_id)
                    WHERE state IN ('calling', 'waiting_tool')
                """
            )
            values = (
                "hash",
                "citizen-1",
                "prompt",
                "[]",
                "[]",
                "[]",
            )
            connection.execute(
                """
                INSERT INTO turns (
                    turn_id, request_id, input_hash, citizen_id, actor_id, prompt,
                    tools_json, messages_json, tool_steps, state,
                    pending_calls_json, created_at, updated_at
                ) VALUES ('turn-old', 'request-old', ?, ?, 'actor-a', ?, ?, ?, 0,
                          'waiting_tool', ?, 1, 1)
                """,
                values,
            )
            connection.execute(
                """
                INSERT INTO turns (
                    turn_id, request_id, input_hash, citizen_id, actor_id, prompt,
                    tools_json, messages_json, tool_steps, state,
                    pending_calls_json, created_at, updated_at
                ) VALUES ('turn-new', 'request-new', ?, ?, 'actor-b', ?, ?, ?, 0,
                          'calling', ?, 2, 2)
                """,
                values,
            )
            connection.execute("PRAGMA user_version = 1")
            connection.commit()

        SQLiteStore(self.db_path)
        with closing(sqlite3.connect(self.db_path)) as connection:
            states = dict(connection.execute("SELECT turn_id, state FROM turns"))
            indexes = {row[1] for row in connection.execute("PRAGMA index_list('turns')")}
            version = connection.execute("PRAGMA user_version").fetchone()[0]

        self.assertEqual("waiting_tool", states["turn-old"])
        self.assertEqual("failed", states["turn-new"])
        self.assertNotIn("one_active_turn_per_session", indexes)
        self.assertIn("one_active_turn_per_citizen", indexes)
        self.assertEqual(4, version)

    def test_reopen_fails_calling_turn_but_preserves_waiting_tool_turn(self) -> None:
        SQLiteStore(self.db_path)
        with closing(sqlite3.connect(self.db_path)) as connection:
            values = ("hash", "prompt", "[]", "[]", "[]")
            connection.execute(
                """
                INSERT INTO turns (
                    turn_id, request_id, input_hash, citizen_id, actor_id, prompt,
                    tools_json, messages_json, tool_steps, state,
                    pending_calls_json, created_at, updated_at
                ) VALUES ('turn-calling', 'request-calling', ?, 'citizen-a', 'actor',
                          ?, ?, ?, 0, 'calling', ?, 1, 1)
                """,
                values,
            )
            connection.execute(
                """
                INSERT INTO turns (
                    turn_id, request_id, input_hash, citizen_id, actor_id, prompt,
                    tools_json, messages_json, tool_steps, state,
                    pending_calls_json, created_at, updated_at
                ) VALUES ('turn-waiting', 'request-waiting', ?, 'citizen-b', 'actor',
                          ?, ?, ?, 1, 'waiting_tool', ?, 1, 1)
                """,
                values,
            )
            connection.commit()

        SQLiteStore(self.db_path)
        with closing(sqlite3.connect(self.db_path)) as connection:
            states = dict(connection.execute("SELECT turn_id, state FROM turns"))

        self.assertEqual("failed", states["turn-calling"])
        self.assertEqual("waiting_tool", states["turn-waiting"])


if __name__ == "__main__":
    unittest.main()
