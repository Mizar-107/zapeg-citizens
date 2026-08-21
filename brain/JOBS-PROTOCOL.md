# Durable jobs: protocol 3

Durable jobs are the multi-step path for goals such as sorting a storage room, building a villa, mining diamonds, or hunting mobs. Minecraft remains the executor and source of truth. The brain plans from persisted state and emits at most one world action in each response.

All routes below are `POST`, require `Authorization: Bearer <CITIZENS_BRAIN_TOKEN>` and `Content-Type: application/json`, and require the integer `"protocol": 3`. Unknown or invalid input returns a non-2xx response:

```json
{"protocol":3,"error":{"code":"invalid_request","message":"safe bounded message"}}
```

`error.code` is a machine contract, not display text. In particular, every "the job is
still waiting on a model call" collision — a retried `/start`, a `/result`, or a `/resume`
arriving while the job is `CALLING` — returns **409 `job_in_progress`**. Forge classifies
that code as "healthy, still planning": it re-enters its silent retry wait without burning
a bounded retry attempt and without announcing an outage. The cancel-before-start
tombstone fence stays distinct (409 `job_not_ready`, message "job is canceled"), so a
fenced job fails fast instead of retrying.

## Start

`POST /v1/job/start`

```json
{
  "protocol": 3,
  "request_id": "job-start-018f...",
  "job_id": "job-018f...",
  "citizen": {
    "id": "citizen-uuid",
    "name": "Atlas",
    "owner_kind": "PLAYER",
    "owner_id": "player-uuid",
    "role": "builder",
    "faction": "village",
    "persona": "A methodical village builder.",
    "interaction_mode": "TASK"
  },
  "actor": {
    "id": "player-uuid",
    "name": "PlayerName",
    "dimension": "minecraft:overworld",
    "x": 12.5,
    "y": 64,
    "z": -8.5,
    "yaw": 90,
    "pitch": 0,
    "look_target": {
      "kind": "BLOCK",
      "dimension": "minecraft:overworld",
      "x": 13,
      "y": 64,
      "z": -8
    }
  },
  "goal": "Build a two-storey spruce villa here.",
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "look_around",
        "description": "Inspect nearby terrain.",
        "parameters": {"type":"object","properties":{}}
      }
    }
  ],
  "budgets": {
    "max_actions": 256,
    "max_model_calls": 512,
    "max_active_seconds": 10800
  }
}
```

`look_target` is either `null` or an object whose `kind` is `BLOCK` or `ENTITY`; an entity target may also contain `id`. A durable job requires `citizen.interaction_mode` to be `TASK`. `owner_kind` is `PLAYER` or `SERVER`; only player-owned citizens require a non-null `owner_id`.

`job_id` is the durable identity. `request_id` is the idempotency identity for this start attempt. Retrying the same start requires the same normalized body. Only one nonterminal job may exist per citizen, and the global default is 16 nonterminal jobs.

## Return a world-action result

`POST /v1/job/result`

```json
{
  "protocol": 3,
  "request_id": "result-action-123",
  "job_id": "job-018f...",
  "action_id": "action_123",
  "result": {"success":true,"observed_blocks":42}
}
```

The result must correspond to the currently pending action. A retry with the same `request_id` and body is accepted idempotently, but returns the job's current authoritative projection rather than an old executable response. A new request ID may also acknowledge an already accepted action result when the normalized result is identical; conflicting content for that action is rejected. Result content uses `CITIZENS_MAX_RESULT_CHARS` (16,000 by default), independently of the larger world-action argument limit.

## Resume or answer a question

`POST /v1/job/resume`

```json
{
  "protocol": 3,
  "request_id": "resume-018f...",
  "job_id": "job-018f...",
  "answer": "Use spruce with a stone foundation.",
  "checkpoint": {
    "state": "PAUSED",
    "actions_completed": 17,
    "active_seconds": 803,
    "last_confirmed_action_id": "action_122",
    "pending_action_id": "action_123",
    "pending_action_uncertain": true,
    "progress": {"phase":"walls","detail":"server restart reconciliation"}
  }
}
```

`answer` is optional. It is durably recorded as authenticated actor input and included as task context, never as a system instruction. Omitting it while a job is in `NEEDS_INPUT` replays the existing question without another model call.

The Forge checkpoint is persisted and counters only move forward. If a read-only pending action survived the restart, it is re-emitted unchanged. A pending mutating action with no confirmed result is recorded as interrupted. The next model pass is then restricted to supplied read-only observation tools; mutation and completion remain unavailable until one observation result has been recorded. The fail-closed read-only set currently includes the status, scan, inspect, locate, recipe, blueprint, `task_status`, and `load_skill` gateways. Unknown future tools are treated as mutating.

## Pause and cancel

`POST /v1/job/pause` and `POST /v1/job/cancel` use the same shape:

```json
{
  "protocol": 3,
  "request_id": "pause-018f...",
  "job_id": "job-018f...",
  "reason": "operator requested a pause"
}
```

Forge treats cancellation as a persisted handshake rather than a fire-and-forget message. It
revokes the physical/result lane immediately, retains a nonterminal `CANCELING` ledger row, retries
the same idempotent cancel request, and permits replacement work only after acknowledgement. If
cancel reaches the brain before a delayed start, SQLite stores a bounded job tombstone and rejects
that later start.

Pause retains a pending action so a later resume can reconcile it. Cancel is terminal and records any pending action as interrupted. Both operations are idempotent. Canceling or pausing an already completed job preserves its completed response.

Forge-side queue note: a player "stop" cancels the driving job through this handshake AND terminally cancels the citizen's mod-side waiting-in-line jobs. Waiting rows were never started against the brain (their `/start` happens only at promotion), so they are canceled purely in the world ledger — no `/v1/job/cancel` and no tombstone is issued for them, and their job ids never reach this API.

## Status and list

`POST /v1/job/status`:

```json
{"protocol":3,"job_id":"job-018f..."}
```

`POST /v1/job/list` optionally filters by citizen:

```json
{"protocol":3,"citizen_id":"citizen-uuid"}
```

Without `citizen_id`, list returns all stored job projections:

```json
{"protocol":3,"jobs":[{"protocol":3,"job_id":"job-018f...","kind":"PAUSED","progress":{"phase":"walls","summary":"First floor complete.","actions_completed":17,"actions_limit":256},"reason":"operator requested a pause"}]}
```

Status and list never return the raw goal or pending action arguments. An `ACTION` status projection therefore has `kind: "ACTION"` and progress but omits `action`; only start, result, and resume can deliver an executable action.

## Flow response union

Start, result, and resume return exactly one of these four flow shapes.

An executable action:

```json
{
  "protocol": 3,
  "job_id": "job-018f...",
  "kind": "ACTION",
  "progress": {
    "phase": "survey",
    "summary": "Checking the build site.",
    "actions_completed": 0,
    "actions_limit": 256
  },
  "action": {
    "id": "action_123",
    "name": "look_around",
    "arguments": {}
  }
}
```

One blocking question:

```json
{
  "protocol": 3,
  "job_id": "job-018f...",
  "kind": "NEEDS_INPUT",
  "progress": {
    "phase": "materials",
    "summary": "A palette choice is required.",
    "actions_completed": 3,
    "actions_limit": 256
  },
  "question": "Should I use oak or spruce?"
}
```

Confirmed completion:

```json
{
  "protocol": 3,
  "job_id": "job-018f...",
  "kind": "COMPLETED",
  "progress": {
    "phase": "complete",
    "summary": "The villa was inspected and completed.",
    "actions_completed": 94,
    "actions_limit": 256
  },
  "speech": "The villa is finished."
}
```

Paused, canceled, failed, budget-exhausted, or safely halted work:

```json
{
  "protocol": 3,
  "job_id": "job-018f...",
  "kind": "PAUSED",
  "progress": {
    "phase": "roof",
    "summary": "Roof framing is in progress.",
    "actions_completed": 80,
    "actions_limit": 256
  },
  "reason": "model-call budget exhausted"
}
```

`progress` is always present. The kind-specific field is `action`, `question`, `speech`, or `reason`. `phase`, `summary`, speech, questions, reasons, identifiers, and nested structures are bounded before persistence or return.

A provider pass produces either exactly one private planner call or an ordered batch of one to eight world-tool calls. `job_define_plan`, `job_checkpoint`, `job_needs_input`, and `job_finish` are private planner tools and are never sent to Minecraft as actions; a pass mixing planner and world tools, or a batch larger than eight, is rejected with feedback. Several bounded internal planner passes may occur within one HTTP request, but the response contains no more than one world action: the first batch element is returned and the validated remainder is persisted as the job's action queue. Each confirmed successful result releases the next queued action without another model call; a failed result discards the queue and the planner re-plans. Pausing, cancellation, completion, and planner faults also discard any queued remainder, so a stale batch never survives a state change. The original player instruction is persisted for the whole job lifetime and remains the completion bar. After a successful world action the planner is asked for the next remaining step toward that instruction. `job_finish` is rejected unless it cites successful, meaningful confirmed evidence and any implied later steps (deposit, craft, and similar) are proven by matching world actions; workflow loading alone is insufficient, one mine is not completion unless that was the whole instruction, and every mutating result must be followed by a cited successful read-only verification.

`job_needs_input` is the explicit blocker/requirement channel, not only a literal question. The planner is told its hard capability limits (on-foot movement within the current dimension only; no portal, teleport, or dimension-crossing tool) and is instructed to call `job_needs_input` immediately when a goal needs an unavailable capability, an unreachable place, or owner-supplied materials/tools, stating exactly what is needed or what cannot be done and why. The `question` field carries that requirement/blocker text; Forge delivers it to the owner as the citizen's own speech and the job waits for an answer or cancellation.

A world action whose exact `(name, arguments)` signature already failed twice consecutively (with no later identical success) is rejected as a planner fault telling the model to change the target, arguments, or approach, or to state the real blocker; bounded retries then pause the job with that reason instead of burning the action budget on a doomed loop.

## Machine-mapped pause reasons

Machine-readable `reason` shapes carry contract semantics for Forge:

- `provider_unavailable: ...` — a genuinely retryable transport/capacity outage (busy queue slot, socket failure, HTTP 408/429/5xx, undecodable body). It consumes no planner-fault retry; Forge maps it to its bounded `PAUSED_BRAIN` backoff and resumes automatically. A stable provider 4xx (bad key, wrong model or URL) is deliberately **not** classified this way: it surfaces as a loud configuration fault ("provider rejected the request with HTTP NNN; check CITIZENS_LLM_URL, CITIZENS_LLM_MODEL, and CITIZENS_LLM_API_KEY") through the ordinary planner-fault path instead of cycling polite outage pauses.
- `planning_in_progress: ...` — the internal planning loop hit the `CITIZENS_MAX_JOB_REQUEST_SECONDS` wall clock and yielded so the HTTP request stays inside the mod's request timeout. Forge resumes automatically; planning continues from the durable checkpoint. Not announced to players.
- `stage_budget_exhausted: ...` — a deterministic staged-template stall (see below). Forge announces it with resume advice; an explicit `/resume` re-arms the stage's action window.
- `... budget exhausted` (the job-level `action budget exhausted`, `model-call budget exhausted`, `active-time budget exhausted` reasons, never the `stage_budget_exhausted` prefix above) — a terminal budget stop. Forge parks the job as `PAUSED_BUDGET`: it is not resumable (resuming brain-side deterministically re-pauses with the same reason and burns no model call), queued work promotes past it, and only cancellation clears it.

## Staged job templates

`detect_template` deterministically matches a small set of goals at `/v1/job/start` — explicit `template:<name> key=value` syntax always wins, plus conservative English/Turkish phrasings for `gather_wood(count)`, `mine_ore(type, count)`, and `simple_build(blueprint)` with the predefined blueprints `shelter_hut`, `storage_hut`, and `watchtower`. Anything else stays a freeform job with unchanged behavior.

A matched template persists with the job (`jobs.template_json`, schema `user_version` 6, migrates in place) as an ordered list of checkpointed stages, each carrying its own goal text, action budget, and exit condition. Stage transitions are server code, never the model: a stage advances only when its exit condition is met by confirmed successful action evidence recorded at or after the previous stage's satisfaction point, so a legitimate jump-ahead (one successful `mine` proving the survey stage) is honored while stale earlier evidence never is. Advancing journals a model-visible `stage_advanced` event and discards any queued action batch planned for the previous stage. The model sees the current stage in a `template_stage` block and is told to pursue only that stage's goal; `job_finish` stays rejected until the final stage's evidence exists. Stage state survives restarts with the job row and resumes mid-stage.

Two `advance.kind` conditions exist (template version 2; persisted version-1 templates keep evaluating unchanged):

- `successful_action` — the stage exits on N confirmed successful results whose action name is in the stage's set (`strict` demands evidence strictly after the previous stage's satisfaction point; consecutive `build` stages use it).
- `inventory_delta` — a quantity gate for the gather/mine act stages. The server takes the citizen's item counts at stage start as a baseline (the latest possession evidence journaled at or before the stage began) and exits only when later confirmed evidence shows `(current − baseline) ≥ count` summed over the stage's item family (`items` entries are exact ids like `minecraft:raw_iron` or family suffixes like `_log`; per item id only gains count). Possession evidence is read from successful `get_self_status`/`mine`/`collect_items`/`take_items`/`fish` results, and only from inventory-context subtrees (`inventory`, `collected`, `items`, ...) in both mapping form (`{"minecraft:oak_log": 6}`) and record form (`{"item": "...", "count": n}`) — a `scan_blocks` listing of visible world blocks never counts. Limitations, by design: payloads carry no NBT (enchanted/named variants count as their plain id), and with no pre-stage snapshot the baseline is empty, so pre-owned stock counts once first observed — the survey/preflight stage goals instruct an initial `get_self_status` to establish the baseline, and the act-stage goals instruct a `get_self_status` after mining because only journaled evidence can advance the stage.

## Persistence and limits

SQLite stores the job snapshot, immutable goal, supplied tools and budgets, structured plan, compact checkpoint, event journal, request-response replay records, at most one pending action, and the persisted remainder of an ordered action batch. A startup recovery changes an interrupted model-call state from `CALLING` to `READY`; it does not discard checkpoints. A waiting action and its queued batch survive restarts. Jobs have no idle TTL and remain until explicitly completed or canceled.

Each model context is rebuilt from the authenticated citizen/actor snapshot, immutable goal, structured plan/checkpoint, Forge checkpoint, counters, and only the most recent bounded events. Verbose old results are compacted rather than replaying an ever-growing transcript.

Relevant service caps and defaults:

- `CITIZENS_MAX_ACTIVE_JOBS=16`
- `CITIZENS_MAX_TOOL_ARGUMENT_CHARS=262144`
- `CITIZENS_MAX_RESULT_CHARS=16000`
- `CITIZENS_MAX_JOB_CONTEXT_CHARS=65536`
- `CITIZENS_MAX_JOB_CHECKPOINT_CHARS=16384`
- `CITIZENS_MAX_JOB_RECENT_EVENTS=8`
- `CITIZENS_MAX_JOB_INTERNAL_STEPS=8`
- `CITIZENS_MAX_JOB_REQUEST_SECONDS=100`

The per-job `max_actions`, `max_model_calls`, and `max_active_seconds` budgets are supplied by Forge and persisted with the job. Exhausting a budget yields `PAUSED`; it does not silently forget the job.
