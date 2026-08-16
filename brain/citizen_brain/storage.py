"""SQLite persistence for conversation memory and in-flight turns."""

from __future__ import annotations

from contextlib import closing
from dataclasses import dataclass
import json
from pathlib import Path
import sqlite3
from typing import Any


ACTIVE_STATES = ("calling", "waiting_tool")
TERMINAL_STATES = ("completed", "failed", "canceled", "expired")


class StoreError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True, slots=True)
class TurnRecord:
    turn_id: str
    request_id: str
    input_hash: str
    citizen_id: str
    actor_id: str
    prompt: str
    tools: list[dict[str, Any]]
    messages: list[dict[str, Any]]
    tool_steps: int
    state: str
    pending_calls: list[dict[str, Any]]
    last_response: dict[str, Any] | None
    created_at: float
    updated_at: float


@dataclass(frozen=True, slots=True)
class ContinueTransition:
    call_provider: bool
    turn: TurnRecord
    response: dict[str, Any] | None


class SQLiteStore:
    def __init__(self, path: str) -> None:
        self._path = path
        if path != ":memory:":
            Path(path).expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._path, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    def _initialize(self) -> None:
        with closing(self._connect()) as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS turns (
                    turn_id TEXT PRIMARY KEY,
                    request_id TEXT NOT NULL UNIQUE,
                    input_hash TEXT NOT NULL,
                    citizen_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    tools_json TEXT NOT NULL,
                    messages_json TEXT NOT NULL,
                    tool_steps INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL,
                    pending_calls_json TEXT NOT NULL DEFAULT '[]',
                    last_response_json TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );

                CREATE INDEX IF NOT EXISTS turns_state_updated
                    ON turns(state, updated_at);

                CREATE TABLE IF NOT EXISTS memory_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    turn_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    citizen_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
                    content TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    UNIQUE(turn_id, ordinal)
                );

                CREATE INDEX IF NOT EXISTS memory_session_id
                    ON memory_messages(citizen_id, actor_id, id);

                CREATE TABLE IF NOT EXISTS cancel_tombstones (
                    request_id TEXT PRIMARY KEY,
                    created_at REAL NOT NULL
                );

                CREATE INDEX IF NOT EXISTS cancel_tombstones_created
                    ON cancel_tombstones(created_at);

                DROP INDEX IF EXISTS one_active_turn_per_session;

                UPDATE turns
                   SET state = 'failed', pending_calls_json = '[]', last_response_json = NULL,
                       updated_at = CAST(strftime('%s', 'now') AS REAL)
                 WHERE state = 'calling';

                UPDATE turns
                   SET state = 'expired', pending_calls_json = '[]', last_response_json = NULL
                 WHERE turn_id IN (
                     SELECT turn_id
                       FROM (
                           SELECT turn_id,
                                  ROW_NUMBER() OVER (
                                      PARTITION BY citizen_id
                                      ORDER BY updated_at DESC, created_at DESC, turn_id DESC
                                  ) AS active_rank
                             FROM turns
                            WHERE state IN ('calling', 'waiting_tool')
                       )
                      WHERE active_rank > 1
                 );

                CREATE UNIQUE INDEX IF NOT EXISTS one_active_turn_per_citizen
                    ON turns(citizen_id)
                    WHERE state IN ('calling', 'waiting_tool');

                PRAGMA user_version = 3;
                """
            )

    @staticmethod
    def _dump(value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)

    @staticmethod
    def _record(row: sqlite3.Row) -> TurnRecord:
        return TurnRecord(
            turn_id=row["turn_id"],
            request_id=row["request_id"],
            input_hash=row["input_hash"],
            citizen_id=row["citizen_id"],
            actor_id=row["actor_id"],
            prompt=row["prompt"],
            tools=json.loads(row["tools_json"]),
            messages=json.loads(row["messages_json"]),
            tool_steps=row["tool_steps"],
            state=row["state"],
            pending_calls=json.loads(row["pending_calls_json"]),
            last_response=(
                json.loads(row["last_response_json"])
                if row["last_response_json"] is not None
                else None
            ),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )

    @staticmethod
    def _expire(connection: sqlite3.Connection, cutoff: float) -> None:
        connection.execute(
            """
            UPDATE turns
               SET state = 'expired', pending_calls_json = '[]', last_response_json = NULL
             WHERE state IN ('calling', 'waiting_tool') AND updated_at < ?
            """,
            (cutoff,),
        )

    @staticmethod
    def _prune_terminal(
        connection: sqlite3.Connection,
        *,
        cutoff: float,
        max_terminal_turns: int,
    ) -> None:
        placeholders = ",".join("?" for _ in TERMINAL_STATES)
        connection.execute(
            f"DELETE FROM turns WHERE state IN ({placeholders}) AND updated_at < ?",
            (*TERMINAL_STATES, cutoff),
        )
        connection.execute(
            f"""
            DELETE FROM turns
             WHERE turn_id IN (
                 SELECT turn_id
                   FROM turns
                  WHERE state IN ({placeholders})
                  ORDER BY updated_at DESC, turn_id DESC
                  LIMIT -1 OFFSET ?
             )
            """,
            (*TERMINAL_STATES, max_terminal_turns),
        )

    @staticmethod
    def _prune_tombstones(
        connection: sqlite3.Connection,
        *,
        cutoff: float,
        max_tombstones: int,
    ) -> None:
        connection.execute(
            "DELETE FROM cancel_tombstones WHERE created_at < ?",
            (cutoff,),
        )
        connection.execute(
            """
            DELETE FROM cancel_tombstones
             WHERE request_id IN (
                 SELECT request_id
                   FROM cancel_tombstones
                  ORDER BY created_at DESC, request_id DESC
                  LIMIT -1 OFFSET ?
             )
            """,
            (max_tombstones,),
        )

    def prune_terminal(self, *, cutoff: float, max_terminal_turns: int) -> None:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._prune_terminal(
                connection,
                cutoff=cutoff,
                max_terminal_turns=max_terminal_turns,
            )
            self._prune_tombstones(
                connection,
                cutoff=cutoff,
                max_tombstones=max_terminal_turns,
            )
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def health(self) -> None:
        with closing(self._connect()) as connection:
            connection.execute("SELECT 1").fetchone()

    def history(self, citizen_id: str, actor_id: str, limit: int) -> list[dict[str, str]]:
        if limit <= 0:
            return []
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """
                SELECT role, content
                  FROM (
                    SELECT id, role, content
                      FROM memory_messages
                     WHERE citizen_id = ? AND actor_id = ?
                     ORDER BY id DESC
                     LIMIT ?
                  )
                 ORDER BY id ASC
                """,
                (citizen_id, actor_id, limit),
            ).fetchall()
        return [{"role": row["role"], "content": row["content"]} for row in rows]

    def create_turn(
        self,
        *,
        turn_id: str,
        request_id: str,
        input_hash: str,
        citizen_id: str,
        actor_id: str,
        prompt: str,
        tools: list[dict[str, Any]],
        messages: list[dict[str, Any]],
        now: float,
        cutoff: float,
        max_active_turns: int,
        terminal_cutoff: float,
        max_terminal_turns: int,
    ) -> tuple[bool, TurnRecord]:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._expire(connection, cutoff)
            self._prune_terminal(
                connection,
                cutoff=terminal_cutoff,
                max_terminal_turns=max_terminal_turns,
            )
            self._prune_tombstones(
                connection,
                cutoff=terminal_cutoff,
                max_tombstones=max_terminal_turns,
            )
            existing = connection.execute(
                "SELECT * FROM turns WHERE request_id = ?", (request_id,)
            ).fetchone()
            if existing is not None:
                connection.commit()
                return False, self._record(existing)
            canceled = connection.execute(
                "SELECT 1 FROM cancel_tombstones WHERE request_id = ?", (request_id,)
            ).fetchone()
            if canceled is not None:
                raise StoreError("request_canceled")

            active_for_citizen = connection.execute(
                """
                SELECT 1 FROM turns
                 WHERE citizen_id = ?
                   AND state IN ('calling', 'waiting_tool')
                """,
                (citizen_id,),
            ).fetchone()
            if active_for_citizen is not None:
                raise StoreError("citizen_busy")
            active_count = connection.execute(
                "SELECT COUNT(*) FROM turns WHERE state IN ('calling', 'waiting_tool')"
            ).fetchone()[0]
            if active_count >= max_active_turns:
                raise StoreError("capacity_reached")

            connection.execute(
                """
                INSERT INTO turns (
                    turn_id, request_id, input_hash, citizen_id, actor_id, prompt,
                    tools_json, messages_json, tool_steps, state,
                    pending_calls_json, last_response_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'calling', '[]', NULL, ?, ?)
                """,
                (
                    turn_id,
                    request_id,
                    input_hash,
                    citizen_id,
                    actor_id,
                    prompt,
                    self._dump(tools),
                    self._dump(messages),
                    now,
                    now,
                ),
            )
            row = connection.execute("SELECT * FROM turns WHERE turn_id = ?", (turn_id,)).fetchone()
            connection.commit()
            assert row is not None
            return True, self._record(row)
        except sqlite3.IntegrityError as exc:
            connection.rollback()
            raise StoreError("citizen_busy") from exc
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def get_turn(self, turn_id: str, *, cutoff: float) -> TurnRecord | None:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._expire(connection, cutoff)
            row = connection.execute("SELECT * FROM turns WHERE turn_id = ?", (turn_id,)).fetchone()
            connection.commit()
            return self._record(row) if row is not None else None
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def save_waiting(
        self,
        *,
        turn_id: str,
        messages: list[dict[str, Any]],
        pending_calls: list[dict[str, Any]],
        tool_steps: int,
        response: dict[str, Any],
        now: float,
    ) -> bool:
        with closing(self._connect()) as connection:
            cursor = connection.execute(
                """
                UPDATE turns
                   SET messages_json = ?, pending_calls_json = ?, tool_steps = ?,
                       state = 'waiting_tool', last_response_json = ?, updated_at = ?
                 WHERE turn_id = ? AND state = 'calling'
                """,
                (
                    self._dump(messages),
                    self._dump(pending_calls),
                    tool_steps,
                    self._dump(response),
                    now,
                    turn_id,
                ),
            )
            return cursor.rowcount == 1

    def finish_turn(
        self,
        *,
        turn_id: str,
        messages: list[dict[str, Any]],
        response: dict[str, Any],
        speech: str,
        now: float,
        max_history_messages: int,
    ) -> bool:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT * FROM turns WHERE turn_id = ?", (turn_id,)).fetchone()
            if row is None or row["state"] != "calling":
                connection.rollback()
                return False
            connection.execute(
                """
                UPDATE turns
                   SET messages_json = ?, pending_calls_json = '[]', state = 'completed',
                       last_response_json = ?, updated_at = ?
                 WHERE turn_id = ?
                """,
                (self._dump(messages), self._dump(response), now, turn_id),
            )
            if max_history_messages > 0:
                connection.execute(
                    """
                    INSERT OR IGNORE INTO memory_messages
                        (turn_id, ordinal, citizen_id, actor_id, role, content, created_at)
                    VALUES (?, 0, ?, ?, 'user', ?, ?)
                    """,
                    (turn_id, row["citizen_id"], row["actor_id"], row["prompt"], now),
                )
                connection.execute(
                    """
                    INSERT OR IGNORE INTO memory_messages
                        (turn_id, ordinal, citizen_id, actor_id, role, content, created_at)
                    VALUES (?, 1, ?, ?, 'assistant', ?, ?)
                    """,
                    (turn_id, row["citizen_id"], row["actor_id"], speech, now),
                )
                connection.execute(
                    """
                    DELETE FROM memory_messages
                     WHERE id IN (
                         SELECT id FROM memory_messages
                          WHERE citizen_id = ? AND actor_id = ?
                          ORDER BY id DESC
                          LIMIT -1 OFFSET ?
                     )
                    """,
                    (row["citizen_id"], row["actor_id"], max_history_messages),
                )
            connection.commit()
            return True
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def accept_tool_result(
        self,
        *,
        turn_id: str,
        tool_call_id: str,
        result_content: str,
        now: float,
        cutoff: float,
    ) -> ContinueTransition:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._expire(connection, cutoff)
            row = connection.execute("SELECT * FROM turns WHERE turn_id = ?", (turn_id,)).fetchone()
            if row is None:
                raise StoreError("turn_not_found")
            turn = self._record(row)
            if turn.state != "waiting_tool":
                raise StoreError(f"turn_{turn.state}")
            if not turn.pending_calls or turn.pending_calls[0].get("id") != tool_call_id:
                raise StoreError("tool_call_mismatch")

            current = turn.pending_calls[0]
            messages = [*turn.messages]
            messages.append(
                {
                    "role": "tool",
                    "tool_name": current["name"],
                    "content": result_content,
                }
            )
            remaining = turn.pending_calls[1:]
            response: dict[str, Any] | None = None
            if remaining:
                response = {
                    "protocol": 1,
                    "turn_id": turn_id,
                    "kind": "tool_call",
                    "tool_call": remaining[0],
                }
                state = "waiting_tool"
                last_response = self._dump(response)
            else:
                state = "calling"
                last_response = None

            connection.execute(
                """
                UPDATE turns
                   SET messages_json = ?, pending_calls_json = ?, state = ?,
                       last_response_json = ?, updated_at = ?
                 WHERE turn_id = ?
                """,
                (
                    self._dump(messages),
                    self._dump(remaining),
                    state,
                    last_response,
                    now,
                    turn_id,
                ),
            )
            updated_row = connection.execute(
                "SELECT * FROM turns WHERE turn_id = ?", (turn_id,)
            ).fetchone()
            connection.commit()
            assert updated_row is not None
            return ContinueTransition(
                call_provider=not remaining,
                turn=self._record(updated_row),
                response=response,
            )
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def cancel(
        self,
        *,
        turn_id: str | None,
        request_id: str | None,
        now: float,
        active_cutoff: float,
        tombstone_cutoff: float,
        max_tombstones: int,
    ) -> tuple[str | None, str]:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._expire(connection, active_cutoff)
            self._prune_tombstones(
                connection,
                cutoff=tombstone_cutoff,
                max_tombstones=max_tombstones,
            )
            if request_id is not None:
                connection.execute(
                    "INSERT OR IGNORE INTO cancel_tombstones(request_id, created_at) VALUES (?, ?)",
                    (request_id, now),
                )
            if turn_id is not None:
                row = connection.execute(
                    "SELECT turn_id, request_id, state FROM turns WHERE turn_id = ?", (turn_id,)
                ).fetchone()
            else:
                row = connection.execute(
                    "SELECT turn_id, request_id, state FROM turns WHERE request_id = ?", (request_id,)
                ).fetchone()
            if row is None:
                if request_id is not None:
                    self._prune_tombstones(
                        connection,
                        cutoff=tombstone_cutoff,
                        max_tombstones=max_tombstones,
                    )
                    connection.commit()
                    return None, "canceled"
                raise StoreError("turn_not_found")
            resolved_turn_id = row["turn_id"]
            connection.execute(
                "INSERT OR IGNORE INTO cancel_tombstones(request_id, created_at) VALUES (?, ?)",
                (row["request_id"], now),
            )
            self._prune_tombstones(
                connection,
                cutoff=tombstone_cutoff,
                max_tombstones=max_tombstones,
            )
            state = row["state"]
            if state in ACTIVE_STATES:
                connection.execute(
                    """
                    UPDATE turns
                       SET state = 'canceled', pending_calls_json = '[]',
                           last_response_json = NULL, updated_at = ?
                     WHERE turn_id = ?
                    """,
                    (now, resolved_turn_id),
                )
                state = "canceled"
            connection.commit()
            return resolved_turn_id, state
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def fail_turn(self, turn_id: str, *, now: float) -> None:
        with closing(self._connect()) as connection:
            connection.execute(
                """
                UPDATE turns
                   SET state = 'failed', pending_calls_json = '[]',
                       last_response_json = NULL, updated_at = ?
                 WHERE turn_id = ? AND state = 'calling'
                """,
                (now, turn_id),
            )
