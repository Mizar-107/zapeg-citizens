# Architecture and verification contract

## Dialogue flow

```text
Alice: @Edda what happened on the old road?
  -> registry verifies Edda is a public server-owned lore citizen
  -> shared-brain request carries trusted persona plus Alice's isolated memory
  -> the request contains an empty world-tool array
  -> Ollama returns final in-character speech
  -> Forge broadcasts Alice's addressed message and Edda's reply once
```

HTTP calls run asynchronously. Entity lookup, tool invocation, cancellation, and all
other world mutations run on the Minecraft server thread. The brain initiates no
connection to Minecraft and receives no RCON, log, filesystem, or raw-command access.

A server-owned lore turn uses the same transport with `interaction_mode=DIALOGUE`
and an empty tool array. The player's addressed message and `[Name]` response remain
public, while SQLite history is still keyed by exact citizen and actor UUID. Physical
work does not use this conversational loop; it enters the durable job flow below.

## Durable job flow

```text
operator/player submits a goal
  -> Forge persists job ID, actor anchor, budgets, and state in world SavedData
  -> brain persists plan/checkpoint and returns exactly one action with a stable ID
  -> Forge persists the pending action before invoking Numen
  -> Numen completes; Forge persists its result before acknowledging it to the brain
  -> brain checkpoints/compacts and returns the next action, a question, or evidenced completion
```

Jobs use the same private HTTP service but the protocol-3 `/v1/job/*` routes. The
sidecar's SQLite journal contains the detailed plan and recent evidence; Minecraft's
world ledger remains authoritative for the physical action lane. A player-owned job
pauses when its owner or body is unavailable. A server-owned job may continue after
the submitting operator logs out. Server shutdown preserves every nonterminal job.

If a mutating action was in flight when either process failed, Forge marks it
uncertain. Resume exposes only read-only observation tools until the brain has
reconciled the real world state; the action is never blindly replayed. Start, result,
resume, and control requests are idempotent under their persisted IDs. Completion
requires the brain's internal finish operation to cite confirmed action evidence.

Ordinary dialogue cancellation is immediately authoritative for the local reply lane.
The mod also requests sidecar cancellation, but that remote step is best-effort when
the sidecar is unreachable. A failed dialogue cancel is audit-logged; the stale turn
row is released by the sidecar's active-turn TTL (15 minutes by default) when API
activity resumes. Dialogue cancellation never cancels a separate durable Numen job.

Durable-job cancellation instead enters persisted `CANCELING` state, cancels the
physical lane, retries the sidecar control request, and blocks replacement work until
the sidecar acknowledges cancellation. Per-request generation fences discard late
model replies, and a cancel-before-start tombstone prevents delayed job creation.

## Authority and persistence

The world `SavedData` ledger is authoritative for:

- globally case-folded citizen name and UUID;
- logical owner (`PLAYER` or `SERVER` principal);
- separate technical Numen body-owner UUID;
- brain controller, role, faction, persona, and optional home anchor;
- one world-specific technical principal for headless Numen bodies.

Numen persists the fake player's location, inventory, skin, and death state. The
sidecar's SQLite database stores bounded conversation history by `(citizen UUID,
actor UUID)` plus durable job plans, checkpoints, events, and request replays. World
and brain backups therefore have separate lifecycles and should be taken together.

The sidecar may request only schemas supplied by Forge. Forge independently rejects
anything outside its compiled worker allowlist, and Numen's typed server tool parses
and validates the arguments. Managed bodies reject Numen's ordinary client-originated
tool and cancellation payloads so two brains cannot drive one body. Numen's stock
summon/despawn lifecycle is gated, and only operator `/citizen spawn`,
`/citizen spawn-server`, and `/citizen remove` mutate the managed namespace.

Server-owned bodies are reconciled after levels load. The addon validates both the
live body and Numen's persistent owner row, refreshes Numen's expiring radius-two
chunk ticket every tick, snapshots changed positions every 100 ticks, and hibernates
them during orderly shutdown. A dead row is recreated with the same UUID at its
validated home after 600 ticks. Missing or mismatched Numen identity data is
quarantined rather than silently replaced.

## Compatibility boundary

Public Numen APIs expose `NumenTool.onServerCall`, but Numen 0.1.1 sends later
synchronous task results only to the technical owner's client. A required Mixin on
the exact pinned `TaskQueue.complete` captures result records whose call IDs use the
private `mcp-zapeg-` prefix. Immediate perception results use the normal callback.
Another pinned hook rejects client tool packets targeting managed UUIDs.

These hooks are intentionally small and covered by dependency checksums. Upgrade
Numen only after the Mixin application and an actual movement/mining loop are tested.
The long-term fix is an upstream public server result-sink API.

## Isolated smoke test before ATM9

1. Build the addon and brain; start a throwaway Forge 47.4.10 server with exact Numen
   0.1.1 and this jar.
2. Start the brain with a mock provider. Verify `/healthz`, bearer rejection, dialogue
   final/cancel behavior, all `/v1/job/*` routes, action/result idempotency, checkpoints,
   questions, evidenced completion, and restart reconciliation.
3. Start one client with Numen and the addon. No provider/key configuration should be
   required in Numen's panel.
4. Run `/citizen spawn Atlas Alice`; verify one body and one registry row.
5. Repeat with `atlas`; verify no second case-colliding UUID is minted.
6. Alice sends `@Atlas follow me for 20 blocks`; verify private routing and movement.
7. Bob sends `@Atlas follow me`; verify private denial and no model request.
8. Alice sends normal chat; verify it remains normal public chat.
9. During a job, send `@Atlas stop`; verify the durable job enters cancellation and
   its current Numen action stops.
10. Try the stock Numen summon, cancel, and dismiss controls; verify they cannot
    create or interfere with a managed citizen.
11. Disconnect Alice; verify Atlas becomes dormant. Reconnect and verify UUID,
    inventory, and position survive.
12. Try dropped-item pickup, then low-risk mining with tools already supplied.
13. Run `/citizen remove Atlas`; verify its inventory drops, body and roster entry
    disappear, and the name can be reserved again.
14. Run `/citizen spawn-server Edda lore village <persona>` and verify that two
    players can talk publicly while receiving separate conversation histories.
15. Ask Edda to mine through public `@Edda` chat; verify no tool call is supplied.
    Then run `/citizen task Edda <task>` as an OP and verify physical execution.
16. Restart the server; verify Edda returns with the same UUID and inventory. Kill
    Edda in a copied world and verify home recovery after 600 ticks.
17. Repeat on a copied ZapeG/ATM9 world and test FTB Chunks claims, graves, and
    always-awake chunk cost before live rollout.
18. Start a job with `@Atlas <goal>`; verify `@Atlas status`, `@Atlas stop`, and
    owner logout/reconnect pause/resume behavior.
19. Run `/citizen task Edda <goal>`, disconnect the submitting operator, and verify
    the server-owned job continues. Verify `/citizen status`, `/citizen jobs`,
    `/citizen resume`, and `/citizen stop`.
20. Interrupt a mutating job once by restarting the brain and once by restarting
    Minecraft. Verify it re-observes an uncertain action instead of replaying it.
21. Exercise each packaged workflow (`storage`, `building`, `mining`, `combat`) and
    confirm the workflow is loaded before the first mutating action.

The live server remains gated on this two-client and ATM9 smoke test. Do not point an
experimental build at the production world data directory.
