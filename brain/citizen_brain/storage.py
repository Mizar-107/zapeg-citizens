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
ACTIVE_JOB_STATES = ("READY", "CALLING", "WAITING_ACTION", "NEEDS_INPUT", "PAUSED")
TERMINAL_JOB_STATES = ("COMPLETED", "CANCELED", "FAILED")
JOB_CANCEL_FENCE_SECONDS = 900


def _successful_tool_result(result_content: str) -> bool:
    try:
        parsed = json.loads(result_content)
    except (json.JSONDecodeError, TypeError):
        return False
    return isinstance(parsed, dict) and parsed.get("success") is True


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


@dataclass(frozen=True, slots=True)
class JobRecord:
    job_id: str
    request_id: str
    input_hash: str
    citizen_id: str
    actor_id: str
    citizen: dict[str, Any]
    actor: dict[str, Any]
    goal: str
    tools: list[dict[str, Any]]
    budgets: dict[str, int]
    state: str
    revision: int
    plan: dict[str, Any]
    checkpoint: dict[str, Any]
    server_checkpoint: dict[str, Any]
    phase: str
    summary: str
    actions_completed: int
    model_calls: int
    active_seconds: int
    pending_action: dict[str, Any] | None
    pending_read_only: bool
    action_queue: list[dict[str, Any]]
    recovery_required: bool
    last_response: dict[str, Any] | None
    pause_reason: str | None
    template: dict[str, Any] | None
    created_at: float
    updated_at: float


@dataclass(frozen=True, slots=True)
class JobOperationTransition:
    job: JobRecord
    duplicate: bool
    cached_response: dict[str, Any] | None
    reemit_pending: bool = False


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

                CREATE TABLE IF NOT EXISTS job_cancel_tombstones (
                    job_id TEXT PRIMARY KEY,
                    created_at REAL NOT NULL
                );

                CREATE INDEX IF NOT EXISTS job_cancel_tombstones_created
                    ON job_cancel_tombstones(created_at);

                CREATE TABLE IF NOT EXISTS jobs (
                    job_id TEXT PRIMARY KEY,
                    request_id TEXT NOT NULL UNIQUE,
                    input_hash TEXT NOT NULL,
                    citizen_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    citizen_json TEXT NOT NULL,
                    actor_json TEXT NOT NULL,
                    goal TEXT NOT NULL,
                    tools_json TEXT NOT NULL,
                    budgets_json TEXT NOT NULL,
                    state TEXT NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0,
                    plan_json TEXT NOT NULL DEFAULT '{}',
                    checkpoint_json TEXT NOT NULL DEFAULT '{}',
                    server_checkpoint_json TEXT NOT NULL DEFAULT '{}',
                    phase TEXT NOT NULL DEFAULT 'planning',
                    summary TEXT NOT NULL DEFAULT 'Job accepted.',
                    actions_completed INTEGER NOT NULL DEFAULT 0,
                    model_calls INTEGER NOT NULL DEFAULT 0,
                    active_seconds INTEGER NOT NULL DEFAULT 0,
                    pending_action_json TEXT,
                    pending_read_only INTEGER NOT NULL DEFAULT 0,
                    action_queue_json TEXT NOT NULL DEFAULT '[]',
                    recovery_required INTEGER NOT NULL DEFAULT 0,
                    last_response_json TEXT,
                    pause_reason TEXT,
                    template_json TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );

                CREATE INDEX IF NOT EXISTS jobs_state_updated
                    ON jobs(state, updated_at);

                CREATE UNIQUE INDEX IF NOT EXISTS one_active_job_per_citizen
                    ON jobs(citizen_id)
                    WHERE state IN ('READY', 'CALLING', 'WAITING_ACTION', 'NEEDS_INPUT', 'PAUSED');

                CREATE TABLE IF NOT EXISTS job_requests (
                    request_id TEXT PRIMARY KEY,
                    job_id TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    input_hash TEXT NOT NULL,
                    response_json TEXT,
                    created_at REAL NOT NULL,
                    FOREIGN KEY(job_id) REFERENCES jobs(job_id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS job_requests_job
                    ON job_requests(job_id, operation);

                CREATE TABLE IF NOT EXISTS job_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    action_id TEXT,
                    request_id TEXT,
                    input_hash TEXT,
                    payload_json TEXT NOT NULL,
                    response_json TEXT,
                    created_at REAL NOT NULL,
                    FOREIGN KEY(job_id) REFERENCES jobs(job_id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS job_events_job_id
                    ON job_events(job_id, id);

                CREATE UNIQUE INDEX IF NOT EXISTS job_event_action_once
                    ON job_events(job_id, event_type, action_id)
                    WHERE action_id IS NOT NULL;

                UPDATE jobs SET state = 'READY', pause_reason = NULL
                 WHERE state = 'CALLING';

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

                PRAGMA user_version = 6;
                """
            )
        with closing(self._connect()) as connection:
            job_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(jobs)").fetchall()
            }
            if "action_queue_json" not in job_columns:
                # Databases created before the ordered action batch existed gain the
                # column in place; a NULL reads as an empty queue.
                connection.execute(
                    "ALTER TABLE jobs ADD COLUMN action_queue_json TEXT NOT NULL DEFAULT '[]'"
                )
            if "template_json" not in job_columns:
                # Databases created before staged job templates existed gain the
                # column in place; a NULL reads as "no template" (freeform job).
                connection.execute("ALTER TABLE jobs ADD COLUMN template_json TEXT")

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
    def _job_record(row: sqlite3.Row) -> JobRecord:
        return JobRecord(
            job_id=row["job_id"],
            request_id=row["request_id"],
            input_hash=row["input_hash"],
            citizen_id=row["citizen_id"],
            actor_id=row["actor_id"],
            citizen=json.loads(row["citizen_json"]),
            actor=json.loads(row["actor_json"]),
            goal=row["goal"],
            tools=json.loads(row["tools_json"]),
            budgets=json.loads(row["budgets_json"]),
            state=row["state"],
            revision=row["revision"],
            plan=json.loads(row["plan_json"]),
            checkpoint=json.loads(row["checkpoint_json"]),
            server_checkpoint=json.loads(row["server_checkpoint_json"]),
            phase=row["phase"],
            summary=row["summary"],
            actions_completed=row["actions_completed"],
            model_calls=row["model_calls"],
            active_seconds=row["active_seconds"],
            pending_action=(
                json.loads(row["pending_action_json"])
                if row["pending_action_json"] is not None
                else None
            ),
            pending_read_only=bool(row["pending_read_only"]),
            action_queue=(
                json.loads(row["action_queue_json"])
                if row["action_queue_json"]
                else []
            ),
            recovery_required=bool(row["recovery_required"]),
            last_response=(
                json.loads(row["last_response_json"])
                if row["last_response_json"] is not None
                else None
            ),
            pause_reason=row["pause_reason"],
            template=(
                json.loads(row["template_json"])
                if "template_json" in row.keys() and row["template_json"] is not None
                else None
            ),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )

    @staticmethod
    def _job_row(connection: sqlite3.Connection, job_id: str) -> sqlite3.Row:
        row = connection.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,)).fetchone()
        if row is None:
            raise StoreError("job_not_found")
        return row

    @staticmethod
    def _request_row(
        connection: sqlite3.Connection,
        request_id: str,
    ) -> sqlite3.Row | None:
        return connection.execute(
            "SELECT * FROM job_requests WHERE request_id = ?", (request_id,)
        ).fetchone()

    @staticmethod
    def _verify_request(
        row: sqlite3.Row,
        *,
        job_id: str,
        operation: str,
        input_hash: str,
    ) -> None:
        if (
            row["job_id"] != job_id
            or row["operation"] != operation
            or row["input_hash"] != input_hash
        ):
            raise StoreError("job_request_reused")

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

    @staticmethod
    def _prune_job_tombstones(
        connection: sqlite3.Connection,
        *,
        cutoff: float,
    ) -> None:
        connection.execute(
            "DELETE FROM job_cancel_tombstones WHERE created_at < ?",
            (cutoff,),
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
                    "protocol": 3,
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

    # ------------------------------------------------------------------
    # Durable protocol-3 jobs. Jobs deliberately have no idle TTL: Forge
    # explicitly pauses, resumes, or cancels them and is authoritative for
    # elapsed active-time accounting.

    def create_job(
        self,
        *,
        job_id: str,
        request_id: str,
        input_hash: str,
        citizen: dict[str, Any],
        actor: dict[str, Any],
        goal: str,
        tools: list[dict[str, Any]],
        budgets: dict[str, int],
        now: float,
        max_active_jobs: int,
        template: dict[str, Any] | None = None,
    ) -> tuple[bool, JobRecord]:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            request = self._request_row(connection, request_id)
            if request is not None:
                self._verify_request(
                    request,
                    job_id=job_id,
                    operation="start",
                    input_hash=input_hash,
                )
                row = self._job_row(connection, job_id)
                connection.commit()
                return False, self._job_record(row)

            canceled_before_start = connection.execute(
                "SELECT 1 FROM job_cancel_tombstones WHERE job_id = ?",
                (job_id,),
            ).fetchone()
            if canceled_before_start is not None:
                raise StoreError("job_canceled")

            existing = connection.execute(
                "SELECT * FROM jobs WHERE job_id = ?", (job_id,)
            ).fetchone()
            if existing is not None:
                record = self._job_record(existing)
                if record.input_hash != input_hash or record.request_id != request_id:
                    raise StoreError("job_id_reused")
                connection.execute(
                    """
                    INSERT INTO job_requests(request_id, job_id, operation, input_hash, created_at)
                    VALUES (?, ?, 'start', ?, ?)
                    """,
                    (request_id, job_id, input_hash, now),
                )
                connection.commit()
                return False, record

            active_for_citizen = connection.execute(
                """
                SELECT 1 FROM jobs WHERE citizen_id = ?
                 AND state IN ('READY', 'CALLING', 'WAITING_ACTION', 'NEEDS_INPUT', 'PAUSED')
                """,
                (citizen["id"],),
            ).fetchone()
            if active_for_citizen is not None:
                raise StoreError("citizen_job_busy")
            active_count = connection.execute(
                """
                SELECT COUNT(*) FROM jobs
                 WHERE state IN ('READY', 'CALLING', 'WAITING_ACTION', 'NEEDS_INPUT', 'PAUSED')
                """
            ).fetchone()[0]
            if active_count >= max_active_jobs:
                raise StoreError("job_capacity_reached")

            connection.execute(
                """
                INSERT INTO jobs (
                    job_id, request_id, input_hash, citizen_id, actor_id,
                    citizen_json, actor_json, goal, tools_json, budgets_json,
                    state, template_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, ?)
                """,
                (
                    job_id,
                    request_id,
                    input_hash,
                    citizen["id"],
                    actor["id"],
                    self._dump(citizen),
                    self._dump(actor),
                    goal,
                    self._dump(tools),
                    self._dump(budgets),
                    self._dump(template) if template is not None else None,
                    now,
                    now,
                ),
            )
            connection.execute(
                """
                INSERT INTO job_requests(request_id, job_id, operation, input_hash, created_at)
                VALUES (?, ?, 'start', ?, ?)
                """,
                (request_id, job_id, input_hash, now),
            )
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, request_id, input_hash, payload_json, created_at)
                VALUES (?, 'started', ?, ?, ?, ?)
                """,
                (
                    job_id,
                    request_id,
                    input_hash,
                    self._dump({"goal": goal, "budgets": budgets}),
                    now,
                ),
            )
            row = self._job_row(connection, job_id)
            connection.commit()
            return True, self._job_record(row)
        except sqlite3.IntegrityError as exc:
            connection.rollback()
            raise StoreError("citizen_job_busy") from exc
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def get_job(self, job_id: str) -> JobRecord | None:
        with closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT * FROM jobs WHERE job_id = ?", (job_id,)
            ).fetchone()
        return self._job_record(row) if row is not None else None

    def tombstone_job_if_absent(
        self,
        *,
        job_id: str,
        now: float,
        cutoff: float,
        max_tombstones: int,
    ) -> bool:
        """Atomically fence a delayed start, unless the job already exists."""
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT 1 FROM jobs WHERE job_id = ?", (job_id,)
            ).fetchone()
            if existing is not None:
                connection.commit()
                return False
            connection.execute(
                "INSERT OR IGNORE INTO job_cancel_tombstones(job_id, created_at) VALUES (?, ?)",
                (job_id, now),
            )
            # Job fences are isolated from dialogue tombstone count pruning. Retain
            # them for at least the longest normal in-flight request window, even
            # when terminal-turn retention is configured unusually low.
            self._prune_job_tombstones(
                connection,
                cutoff=min(cutoff, now - JOB_CANCEL_FENCE_SECONDS),
            )
            connection.commit()
            return True
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def list_jobs(self, citizen_id: str | None = None) -> list[JobRecord]:
        with closing(self._connect()) as connection:
            if citizen_id is None:
                rows = connection.execute(
                    "SELECT * FROM jobs ORDER BY updated_at DESC, job_id DESC"
                ).fetchall()
            else:
                rows = connection.execute(
                    """
                    SELECT * FROM jobs WHERE citizen_id = ?
                    ORDER BY updated_at DESC, job_id DESC
                    """,
                    (citizen_id,),
                ).fetchall()
        return [self._job_record(row) for row in rows]

    def recent_job_events(self, job_id: str, limit: int) -> list[dict[str, Any]]:
        if limit <= 0:
            return []
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """
                SELECT id, event_type, action_id, payload_json, created_at
                  FROM (
                    SELECT id, event_type, action_id, payload_json, created_at
                      FROM job_events WHERE job_id = ?
                     ORDER BY id DESC LIMIT ?
                  ) ORDER BY id ASC
                """,
                (job_id, limit),
            ).fetchall()
        return [
            {
                "type": row["event_type"],
                "action_id": row["action_id"],
                "payload": json.loads(row["payload_json"]),
                "created_at": row["created_at"],
            }
            for row in rows
        ]

    def confirmed_job_action_ids(self, job_id: str) -> set[str]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """
                SELECT action_id FROM job_events
                 WHERE job_id = ? AND event_type = 'result' AND action_id IS NOT NULL
                """,
                (job_id,),
            ).fetchall()
        return {row["action_id"] for row in rows}

    def confirmed_job_actions(self, job_id: str) -> list[dict[str, Any]]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """
                SELECT id, action_id, payload_json FROM job_events
                 WHERE job_id = ? AND event_type = 'result' AND action_id IS NOT NULL
                 ORDER BY id ASC
                """,
                (job_id,),
            ).fetchall()
        actions: list[dict[str, Any]] = []
        for row in rows:
            payload = json.loads(row["payload_json"])
            result_content = payload.get("result")
            actions.append(
                {
                    "event_id": int(row["id"]),
                    "action_id": row["action_id"],
                    "action_name": payload.get("action_name"),
                    "success": isinstance(result_content, str)
                    and _successful_tool_result(result_content),
                    # Raw journaled result content; template quantity gates
                    # (inventory_delta) read possession evidence out of it.
                    "result": result_content if isinstance(result_content, str) else None,
                }
            )
        return actions

    def recent_failed_actions(self, job_id: str, limit: int) -> list[dict[str, Any]]:
        """Most recent confirmed results joined with their requested arguments.

        Returns the newest ``limit`` result events (oldest first), each carrying
        the action name, the exact arguments from the matching 'action' event,
        and whether the result succeeded. Used to detect an identical action
        failing repeatedly so the planner is redirected instead of looping.
        """
        if limit <= 0:
            return []
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """
                SELECT id, action_id, payload_json,
                       (
                           SELECT payload_json FROM job_events requested
                            WHERE requested.job_id = job_events.job_id
                              AND requested.event_type = 'action'
                              AND requested.action_id = job_events.action_id
                       ) AS action_payload_json
                  FROM (
                    SELECT id, job_id, action_id, payload_json
                      FROM job_events
                     WHERE job_id = ? AND event_type = 'result' AND action_id IS NOT NULL
                     ORDER BY id DESC LIMIT ?
                  ) AS job_events ORDER BY id ASC
                """,
                (job_id, limit),
            ).fetchall()
        actions: list[dict[str, Any]] = []
        for row in rows:
            payload = json.loads(row["payload_json"])
            result_content = payload.get("result")
            arguments: Any = None
            if row["action_payload_json"] is not None:
                try:
                    arguments = json.loads(row["action_payload_json"]).get("arguments")
                except (json.JSONDecodeError, TypeError, AttributeError):
                    arguments = None
            actions.append(
                {
                    "event_id": int(row["id"]),
                    "action_id": row["action_id"],
                    "action_name": payload.get("action_name"),
                    "arguments": arguments,
                    "success": isinstance(result_content, str)
                    and _successful_tool_result(result_content),
                }
            )
        return actions

    def update_job_template(
        self,
        *,
        job_id: str,
        template: dict[str, Any],
        event_type: str,
        payload: dict[str, Any],
        now: float,
    ) -> JobRecord:
        """Persist deterministic template/stage state and journal the transition."""
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._job_row(connection, job_id)
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, payload_json, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (job_id, event_type, self._dump(payload), now),
            )
            connection.execute(
                """
                UPDATE jobs SET template_json = ?, revision = revision + 1, updated_at = ?
                 WHERE job_id = ?
                """,
                (self._dump(template), now, job_id),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def begin_job_model_call(self, job_id: str, *, now: float) -> JobRecord:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            if row["state"] != "READY":
                raise StoreError(f"job_{row['state'].lower()}")
            connection.execute(
                """
                UPDATE jobs SET state = 'CALLING', model_calls = model_calls + 1,
                                revision = revision + 1, updated_at = ?
                 WHERE job_id = ? AND state = 'READY'
                """,
                (now, job_id),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def save_job_action(
        self,
        *,
        job_id: str,
        action: dict[str, Any],
        read_only: bool,
        response: dict[str, Any],
        phase: str,
        summary: str,
        now: float,
        action_queue: list[dict[str, Any]] = (),
    ) -> JobRecord:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            if row["state"] != "CALLING":
                raise StoreError(f"job_{row['state'].lower()}")
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, action_id, payload_json, created_at)
                VALUES (?, 'action', ?, ?, ?)
                """,
                (job_id, action["id"], self._dump(action), now),
            )
            connection.execute(
                """
                UPDATE jobs SET state = 'WAITING_ACTION', pending_action_json = ?,
                                pending_read_only = ?, action_queue_json = ?,
                                phase = ?, summary = ?,
                                last_response_json = ?, pause_reason = NULL,
                                revision = revision + 1, updated_at = ?
                 WHERE job_id = ?
                """,
                (
                    self._dump(action),
                    int(read_only),
                    self._dump(list(action_queue)),
                    phase,
                    summary,
                    self._dump(response),
                    now,
                    job_id,
                ),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def pop_job_action_queue(
        self,
        *,
        job_id: str,
        response: dict[str, Any],
        now: float,
    ) -> JobRecord:
        """Dispatch the next queued planner action without a new model call.

        Only legal while the job sits READY after a confirmed result; the queue head
        becomes the persisted pending action and keeps its planner-assigned identity.
        """
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            if row["state"] != "READY":
                raise StoreError(f"job_{row['state'].lower()}")
            queue = (
                json.loads(row["action_queue_json"])
                if row["action_queue_json"]
                else []
            )
            if not queue:
                raise StoreError("job_queue_empty")
            head = dict(queue[0])
            read_only = bool(head.pop("read_only", False))
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, action_id, payload_json, created_at)
                VALUES (?, 'action', ?, ?, ?)
                """,
                (job_id, head["id"], self._dump(head), now),
            )
            connection.execute(
                """
                UPDATE jobs SET state = 'WAITING_ACTION', pending_action_json = ?,
                                pending_read_only = ?, action_queue_json = ?,
                                last_response_json = ?, pause_reason = NULL,
                                revision = revision + 1, updated_at = ?
                 WHERE job_id = ?
                """,
                (
                    self._dump(head),
                    int(read_only),
                    self._dump(queue[1:]),
                    self._dump(response),
                    now,
                    job_id,
                ),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def clear_job_action_queue(
        self,
        *,
        job_id: str,
        reason: str,
        now: float,
    ) -> JobRecord:
        """Discard queued planner actions and journal why, so the planner re-plans them."""
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            queue = (
                json.loads(row["action_queue_json"])
                if row["action_queue_json"]
                else []
            )
            if queue:
                connection.execute(
                    """
                    INSERT INTO job_events(job_id, event_type, payload_json, created_at)
                    VALUES (?, 'queue_discarded', ?, ?)
                    """,
                    (
                        job_id,
                        self._dump(
                            {
                                "reason": reason,
                                "discarded_action_ids": [
                                    item.get("id") for item in queue
                                ],
                            }
                        ),
                        now,
                    ),
                )
                connection.execute(
                    """
                    UPDATE jobs SET action_queue_json = '[]',
                                    revision = revision + 1, updated_at = ?
                     WHERE job_id = ?
                    """,
                    (now, job_id),
                )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def record_job_planner_error(
        self,
        *,
        job_id: str,
        reason: str,
        now: float,
    ) -> JobRecord:
        """Journal one bounded planner fault and return the job to READY.

        The event is part of the model-visible recent-event window, so the next
        planning call can see and correct the rejected reply instead of the job
        pausing on a single transient or malformed planner response.
        """
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            if row["state"] != "CALLING":
                raise StoreError(f"job_{row['state'].lower()}")
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, payload_json, created_at)
                VALUES (?, 'planner_error', ?, ?)
                """,
                (job_id, self._dump({"reason": reason}), now),
            )
            connection.execute(
                """
                UPDATE jobs SET state = 'READY', action_queue_json = '[]',
                                revision = revision + 1,
                                updated_at = ?
                 WHERE job_id = ?
                """,
                (now, job_id),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def save_job_checkpoint(
        self,
        *,
        job_id: str,
        event_type: str,
        plan: dict[str, Any] | None,
        checkpoint: dict[str, Any] | None,
        phase: str,
        summary: str,
        now: float,
    ) -> JobRecord:
        if event_type not in {"plan", "checkpoint"}:
            raise ValueError("unsupported checkpoint event type")
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            if row["state"] != "CALLING":
                raise StoreError(f"job_{row['state'].lower()}")
            current_plan = json.loads(row["plan_json"])
            current_checkpoint = json.loads(row["checkpoint_json"])
            next_plan = current_plan if plan is None else plan
            next_checkpoint = current_checkpoint if checkpoint is None else checkpoint
            payload = {
                "phase": phase,
                "summary": summary,
                "plan": plan,
                "checkpoint": checkpoint,
            }
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, payload_json, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (job_id, event_type, self._dump(payload), now),
            )
            connection.execute(
                """
                UPDATE jobs SET state = 'READY', plan_json = ?, checkpoint_json = ?,
                                phase = ?, summary = ?, last_response_json = NULL,
                                pause_reason = NULL, revision = revision + 1,
                                updated_at = ?
                 WHERE job_id = ?
                """,
                (
                    self._dump(next_plan),
                    self._dump(next_checkpoint),
                    phase,
                    summary,
                    now,
                    job_id,
                ),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def save_job_needs_input(
        self,
        *,
        job_id: str,
        phase: str,
        summary: str,
        question: str,
        response: dict[str, Any],
        now: float,
    ) -> JobRecord:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            if row["state"] != "CALLING":
                raise StoreError(f"job_{row['state'].lower()}")
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, payload_json, response_json, created_at)
                VALUES (?, 'needs_input', ?, ?, ?)
                """,
                (
                    job_id,
                    self._dump({"phase": phase, "summary": summary, "question": question}),
                    self._dump(response),
                    now,
                ),
            )
            connection.execute(
                """
                UPDATE jobs SET state = 'NEEDS_INPUT', phase = ?, summary = ?,
                                action_queue_json = '[]',
                                last_response_json = ?, pause_reason = NULL,
                                revision = revision + 1, updated_at = ?
                 WHERE job_id = ?
                """,
                (phase, summary, self._dump(response), now, job_id),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def finish_job(
        self,
        *,
        job_id: str,
        phase: str,
        summary: str,
        speech: str,
        evidence_action_ids: list[str],
        response: dict[str, Any],
        now: float,
    ) -> JobRecord:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            if row["state"] != "CALLING":
                raise StoreError(f"job_{row['state'].lower()}")
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, payload_json, response_json, created_at)
                VALUES (?, 'finished', ?, ?, ?)
                """,
                (
                    job_id,
                    self._dump(
                        {
                            "phase": phase,
                            "summary": summary,
                            "speech": speech,
                            "evidence_action_ids": evidence_action_ids,
                        }
                    ),
                    self._dump(response),
                    now,
                ),
            )
            connection.execute(
                """
                UPDATE jobs SET state = 'COMPLETED', phase = ?, summary = ?,
                                pending_action_json = NULL, pending_read_only = 0,
                                action_queue_json = '[]',
                                recovery_required = 0, last_response_json = ?,
                                pause_reason = NULL, revision = revision + 1,
                                updated_at = ?
                 WHERE job_id = ?
                """,
                (phase, summary, self._dump(response), now, job_id),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def accept_job_result(
        self,
        *,
        job_id: str,
        request_id: str,
        action_id: str,
        input_hash: str,
        result_content: str,
        now: float,
    ) -> JobOperationTransition:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            request = self._request_row(connection, request_id)
            if request is not None:
                self._verify_request(
                    request,
                    job_id=job_id,
                    operation="result",
                    input_hash=input_hash,
                )
                row = self._job_row(connection, job_id)
                response = (
                    json.loads(request["response_json"])
                    if request["response_json"] is not None
                    else None
                )
                connection.commit()
                return JobOperationTransition(self._job_record(row), True, response)

            prior = connection.execute(
                """
                SELECT input_hash, response_json FROM job_events
                 WHERE job_id = ? AND event_type = 'result' AND action_id = ?
                """,
                (job_id, action_id),
            ).fetchone()
            if prior is not None:
                if prior["input_hash"] != input_hash:
                    raise StoreError("job_result_mismatch")
                connection.execute(
                    """
                    INSERT INTO job_requests(
                        request_id, job_id, operation, input_hash, response_json, created_at
                    ) VALUES (?, ?, 'result', ?, ?, ?)
                    """,
                    (request_id, job_id, input_hash, prior["response_json"], now),
                )
                row = self._job_row(connection, job_id)
                response = (
                    json.loads(prior["response_json"])
                    if prior["response_json"] is not None
                    else None
                )
                connection.commit()
                return JobOperationTransition(self._job_record(row), True, response)

            row = self._job_row(connection, job_id)
            if row["state"] not in {"WAITING_ACTION", "PAUSED"}:
                raise StoreError(f"job_{row['state'].lower()}")
            pending = (
                json.loads(row["pending_action_json"])
                if row["pending_action_json"] is not None
                else None
            )
            if pending is None or pending.get("id") != action_id:
                raise StoreError("job_action_mismatch")

            connection.execute(
                """
                INSERT INTO job_requests(request_id, job_id, operation, input_hash, created_at)
                VALUES (?, ?, 'result', ?, ?)
                """,
                (request_id, job_id, input_hash, now),
            )
            connection.execute(
                """
                INSERT INTO job_events(
                    job_id, event_type, action_id, request_id, input_hash,
                    payload_json, created_at
                ) VALUES (?, 'result', ?, ?, ?, ?, ?)
                """,
                (
                    job_id,
                    action_id,
                    request_id,
                    input_hash,
                    self._dump(
                        {
                            "action_id": action_id,
                            "action_name": pending.get("name"),
                            "result": result_content,
                        }
                    ),
                    now,
                ),
            )
            was_paused = row["state"] == "PAUSED"
            recovered_observation = (
                bool(row["pending_read_only"])
                and bool(row["recovery_required"])
                and pending.get("name") != "load_skill"
                and _successful_tool_result(result_content)
            )
            failed_mutation = (
                not bool(row["pending_read_only"])
                and not _successful_tool_result(result_content)
            )
            connection.execute(
                """
                UPDATE jobs SET state = ?, actions_completed = actions_completed + 1,
                                pending_action_json = NULL, pending_read_only = 0,
                                recovery_required = CASE
                                    WHEN ? THEN 0
                                    WHEN ? THEN 1
                                    ELSE recovery_required
                                END,
                                last_response_json = NULL,
                                pause_reason = CASE WHEN ? THEN pause_reason ELSE NULL END,
                                revision = revision + 1, updated_at = ?
                 WHERE job_id = ?
                """,
                (
                    "PAUSED" if was_paused else "READY",
                    int(recovered_observation),
                    int(failed_mutation),
                    int(was_paused),
                    now,
                    job_id,
                ),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return JobOperationTransition(self._job_record(updated), False, None)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def resume_job(
        self,
        *,
        job_id: str,
        request_id: str,
        input_hash: str,
        answer: str | None,
        server_checkpoint: dict[str, Any],
        now: float,
    ) -> JobOperationTransition:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            request = self._request_row(connection, request_id)
            if request is not None:
                self._verify_request(
                    request,
                    job_id=job_id,
                    operation="resume",
                    input_hash=input_hash,
                )
                row = self._job_row(connection, job_id)
                response = (
                    json.loads(request["response_json"])
                    if request["response_json"] is not None
                    else None
                )
                connection.commit()
                return JobOperationTransition(
                    self._job_record(row), True, response, response is not None
                )

            row = self._job_row(connection, job_id)
            if row["state"] == "CALLING":
                raise StoreError("job_calling")
            pending = (
                json.loads(row["pending_action_json"])
                if row["pending_action_json"] is not None
                else None
            )
            reemit = False
            cached_response: dict[str, Any] | None = None
            next_state = row["state"]
            recovery_required = bool(row["recovery_required"])
            clear_pending = False

            if row["state"] in TERMINAL_JOB_STATES:
                cached_response = (
                    json.loads(row["last_response_json"])
                    if row["last_response_json"] is not None
                    else None
                )
                reemit = cached_response is not None
            elif row["state"] == "NEEDS_INPUT" and answer is None:
                cached_response = (
                    json.loads(row["last_response_json"])
                    if row["last_response_json"] is not None
                    else None
                )
                reemit = cached_response is not None
            elif pending is not None and bool(row["pending_read_only"]) and answer is None:
                cached_response = (
                    json.loads(row["last_response_json"])
                    if row["last_response_json"] is not None
                    else None
                )
                reemit = cached_response is not None
                if reemit:
                    next_state = "WAITING_ACTION"
            else:
                if pending is not None:
                    connection.execute(
                        """
                        INSERT OR IGNORE INTO job_events(
                            job_id, event_type, action_id, payload_json, created_at
                        ) VALUES (?, 'interrupted', ?, ?, ?)
                        """,
                        (
                            job_id,
                            pending.get("id"),
                            self._dump(
                                {
                                    "action": pending,
                                    "reason": "executor resumed without a confirmed result",
                                }
                            ),
                            now,
                        ),
                    )
                    clear_pending = True
                    recovery_required = True
                external_pending = server_checkpoint.get("pending_action_id")
                if (
                    server_checkpoint.get("pending_action_uncertain") is True
                    and isinstance(external_pending, str)
                    and (pending is None or pending.get("id") != external_pending)
                ):
                    connection.execute(
                        """
                        INSERT OR IGNORE INTO job_events(
                            job_id, event_type, action_id, payload_json, created_at
                        ) VALUES (?, 'interrupted', ?, ?, ?)
                        """,
                        (
                            job_id,
                            external_pending,
                            self._dump(
                                {
                                    "action_id": external_pending,
                                    "reason": "Forge reported an uncertain pending action",
                                }
                            ),
                            now,
                        ),
                    )
                    recovery_required = True
                if answer is not None:
                    connection.execute(
                        """
                        INSERT INTO job_events(job_id, event_type, request_id, input_hash,
                                               payload_json, created_at)
                        VALUES (?, 'actor_input', ?, ?, ?, ?)
                        """,
                        (
                            job_id,
                            request_id,
                            input_hash,
                            self._dump({"answer": answer}),
                            now,
                        ),
                    )
                next_state = "READY"

            supplied_actions = server_checkpoint.get("actions_completed", 0)
            supplied_seconds = server_checkpoint.get("active_seconds", 0)
            connection.execute(
                """
                UPDATE jobs SET state = ?, server_checkpoint_json = ?,
                                actions_completed = MAX(actions_completed, ?),
                                active_seconds = MAX(active_seconds, ?),
                                pending_action_json = CASE WHEN ? THEN NULL ELSE pending_action_json END,
                                pending_read_only = CASE WHEN ? THEN 0 ELSE pending_read_only END,
                                action_queue_json = CASE WHEN ? THEN action_queue_json ELSE '[]' END,
                                recovery_required = ?,
                                last_response_json = CASE WHEN ? THEN last_response_json ELSE NULL END,
                                pause_reason = CASE WHEN ? THEN pause_reason ELSE NULL END,
                                revision = revision + 1, updated_at = ?
                 WHERE job_id = ?
                """,
                (
                    next_state,
                    self._dump(server_checkpoint),
                    supplied_actions,
                    supplied_seconds,
                    int(clear_pending),
                    int(clear_pending),
                    int(reemit),
                    int(recovery_required),
                    int(reemit),
                    int(reemit),
                    now,
                    job_id,
                ),
            )
            connection.execute(
                """
                INSERT INTO job_requests(
                    request_id, job_id, operation, input_hash, response_json, created_at
                ) VALUES (?, ?, 'resume', ?, ?, ?)
                """,
                (
                    request_id,
                    job_id,
                    input_hash,
                    self._dump(cached_response) if cached_response is not None else None,
                    now,
                ),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return JobOperationTransition(
                self._job_record(updated), False, cached_response, reemit
            )
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def pause_job_operation(
        self,
        *,
        job_id: str,
        request_id: str,
        input_hash: str,
        reason: str,
        response: dict[str, Any],
        now: float,
    ) -> JobOperationTransition:
        return self._terminal_job_operation(
            job_id=job_id,
            request_id=request_id,
            input_hash=input_hash,
            operation="pause",
            target_state="PAUSED",
            reason=reason,
            response=response,
            clear_pending=False,
            now=now,
        )

    def cancel_job_operation(
        self,
        *,
        job_id: str,
        request_id: str,
        input_hash: str,
        reason: str,
        response: dict[str, Any],
        now: float,
    ) -> JobOperationTransition:
        return self._terminal_job_operation(
            job_id=job_id,
            request_id=request_id,
            input_hash=input_hash,
            operation="cancel",
            target_state="CANCELED",
            reason=reason,
            response=response,
            clear_pending=True,
            now=now,
        )

    def _terminal_job_operation(
        self,
        *,
        job_id: str,
        request_id: str,
        input_hash: str,
        operation: str,
        target_state: str,
        reason: str,
        response: dict[str, Any],
        clear_pending: bool,
        now: float,
    ) -> JobOperationTransition:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            request = self._request_row(connection, request_id)
            if request is not None:
                self._verify_request(
                    request,
                    job_id=job_id,
                    operation=operation,
                    input_hash=input_hash,
                )
                row = self._job_row(connection, job_id)
                cached = (
                    json.loads(request["response_json"])
                    if request["response_json"] is not None
                    else None
                )
                connection.commit()
                return JobOperationTransition(self._job_record(row), True, cached)

            row = self._job_row(connection, job_id)
            preserve_response = (
                operation == "pause"
                and row["state"] in (*TERMINAL_JOB_STATES, "NEEDS_INPUT")
            ) or (operation == "cancel" and row["state"] == "COMPLETED")
            if operation == "pause" and row["state"] in (*TERMINAL_JOB_STATES, "NEEDS_INPUT"):
                target_state = row["state"]
            if operation == "cancel" and row["state"] == "COMPLETED":
                target_state = "COMPLETED"
            effective_response = response
            if preserve_response and row["last_response_json"] is not None:
                effective_response = json.loads(row["last_response_json"])
            pending = (
                json.loads(row["pending_action_json"])
                if row["pending_action_json"] is not None
                else None
            )
            preserve_pending_action_response = (
                operation == "pause"
                and pending is not None
                and bool(row["pending_read_only"])
                and row["last_response_json"] is not None
            )
            stored_response = (
                json.loads(row["last_response_json"])
                if preserve_pending_action_response
                else effective_response
            )
            effective_clear_pending = clear_pending and not preserve_response
            if effective_clear_pending and pending is not None:
                connection.execute(
                    """
                    INSERT OR IGNORE INTO job_events(
                        job_id, event_type, action_id, payload_json, created_at
                    ) VALUES (?, 'interrupted', ?, ?, ?)
                    """,
                    (
                        job_id,
                        pending.get("id"),
                        self._dump(
                            {
                                "action": pending,
                                "reason": (
                                    "job canceled" if operation == "cancel" else "job paused"
                                ),
                            }
                        ),
                        now,
                    ),
                )
            connection.execute(
                """
                INSERT INTO job_requests(
                    request_id, job_id, operation, input_hash, response_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (request_id, job_id, operation, input_hash, self._dump(effective_response), now),
            )
            connection.execute(
                """
                INSERT INTO job_events(
                    job_id, event_type, request_id, input_hash, payload_json,
                    response_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    job_id,
                    operation + "d",
                    request_id,
                    input_hash,
                    self._dump({"reason": reason}),
                    self._dump(effective_response),
                    now,
                ),
            )
            connection.execute(
                """
                UPDATE jobs SET state = ?, pause_reason = ?, last_response_json = ?,
                                pending_action_json = CASE WHEN ? THEN NULL ELSE pending_action_json END,
                                pending_read_only = CASE WHEN ? THEN 0 ELSE pending_read_only END,
                                action_queue_json = CASE WHEN ? THEN '[]' ELSE action_queue_json END,
                                recovery_required = CASE WHEN ? THEN 0 ELSE recovery_required END,
                                revision = revision + 1, updated_at = ?
                 WHERE job_id = ?
                """,
                (
                    target_state,
                    reason,
                    self._dump(stored_response),
                    int(effective_clear_pending),
                    int(effective_clear_pending),
                    int(effective_clear_pending),
                    int(effective_clear_pending),
                    now,
                    job_id,
                ),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return JobOperationTransition(
                self._job_record(updated), False, effective_response
            )
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def pause_job_internal(
        self,
        *,
        job_id: str,
        reason: str,
        response: dict[str, Any],
        now: float,
    ) -> JobRecord:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = self._job_row(connection, job_id)
            if row["state"] in TERMINAL_JOB_STATES:
                connection.commit()
                return self._job_record(row)
            connection.execute(
                """
                INSERT INTO job_events(job_id, event_type, payload_json, response_json, created_at)
                VALUES (?, 'paused', ?, ?, ?)
                """,
                (job_id, self._dump({"reason": reason}), self._dump(response), now),
            )
            connection.execute(
                """
                UPDATE jobs SET state = 'PAUSED', pause_reason = ?,
                                action_queue_json = '[]',
                                last_response_json = ?, revision = revision + 1,
                                updated_at = ? WHERE job_id = ?
                """,
                (reason, self._dump(response), now, job_id),
            )
            updated = self._job_row(connection, job_id)
            connection.commit()
            return self._job_record(updated)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def cache_job_operation_response(
        self,
        *,
        request_id: str,
        response: dict[str, Any],
        now: float,
    ) -> None:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            request = self._request_row(connection, request_id)
            if request is None:
                raise StoreError("job_request_not_found")
            encoded = self._dump(response)
            connection.execute(
                """
                UPDATE job_requests SET response_json = ?
                 WHERE job_id = ? AND operation = ? AND input_hash = ?
                """,
                (
                    encoded,
                    request["job_id"],
                    request["operation"],
                    request["input_hash"],
                ),
            )
            event_type = {
                "result": "result",
                "resume": "actor_input",
                "start": "started",
            }.get(request["operation"])
            if event_type is not None:
                connection.execute(
                    """
                    UPDATE job_events SET response_json = ?
                     WHERE job_id = ? AND event_type = ? AND input_hash = ?
                    """,
                    (encoded, request["job_id"], event_type, request["input_hash"]),
                )
            connection.execute(
                """
                UPDATE jobs SET last_response_json = ?, updated_at = ? WHERE job_id = ?
                """,
                (encoded, now, request["job_id"]),
            )
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()
