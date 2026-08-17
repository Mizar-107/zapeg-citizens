# Complex citizen jobs

Physical work uses a durable job controller rather than the short dialogue loop. A job has a
persistent UUID, goal, submission anchor, structured plan/checkpoint, action journal, progress,
and independent action/model/time budgets. The default budget is 128 confirmed world actions,
192 model calls, and three hours of active execution time. Time spent paused for an owner, body,
brain, or answer does not consume that active-time budget.

## Starting and controlling work

A player-owned worker starts work through ordinary addressed chat:

```text
@Atlas sort every chest around here into building blocks, ores, food, gear, and misc; add overflow chests if needed
@Atlas status
@Atlas stop
```

An operator assigns a physical job to a server-owned citizen:

```text
/citizen task Edda build a two-storey Mediterranean villa on the plot I am looking at
/citizen status Edda
/citizen jobs Edda
/citizen stop Edda
/citizen resume Edda
```

If a job needs one missing decision, a player answers with another `@Name <answer>` message. An
operator answers a server-owned job with `/citizen resume <name> <answer>`. Server-owned lore
dialogue remains a separate lane, so players may continue talking to the character while its body
performs an operator job.

At submission, the mod records the actor's dimension, position, rotation, and looked-at block.
Words such as “here,” “this plot,” and “these chests” therefore have a stable server-authenticated
anchor even when the citizen's technical Numen owner is not a real player.

## Planning and recovery

The brain first persists a bounded plan and completion criteria. It then returns exactly one
physical action at a time. Minecraft writes and synchronously asks its `SavedData` store to save the
pending action before calling Numen, and the brain commits it to SQLite before returning it to
Minecraft. Results are journaled under stable action IDs and result requests are idempotent.

Player-owned jobs pause when the owner or body leaves and resume when both return. Server-owned
jobs continue if the submitting operator logs out. On server shutdown, body death, a lost HTTP
reply, or a mid-action crash, the job remains recoverable. A mutating action with an unknown outcome
is never blindly replayed: it is marked uncertain and the resumed planner receives only read-only
observation tools until it has inspected the new world state.

Crash recovery assumes the latest Minecraft `SavedData` write reached disk. Minecraft logs but does
not propagate every underlying save I/O failure, so this is a synchronous save barrier rather than
a separate write-ahead log. Back up and restore the world job ledger and brain SQLite volume as one
coordinated pair; restoring only one side is detected conservatively but can require operator repair
or a fresh job.

The model cannot finish a physical job with an unsupported “done” sentence. It must call the
internal completion operation with successful confirmed action evidence. After any mutating result,
including a failed action that may have changed part of the world, the evidence must include a later
successful read-only verification. Older action detail is compacted into the structured checkpoint
while the most recent events remain verbatim, keeping a long job inside a bounded model context.

## Trusted workflows

The dedicated server exposes a closed `load_skill` helper for four packaged workflows. This is a
server-side replacement for Numen's client-only skill helper; it accepts no paths or arbitrary
files.

- `storage`: scope containers, inventory exact stacks, reserve capacity, transfer one open GUI at a
  time, checkpoint after every closed container, and verify conservation.
- `building`: survey the anchored plot, plan footprint/palette/materials, construct from large forms
  to details, then verify and patch doors, stairs, rooms, roof, furnishings, and lighting.
- `mining`: check gear/food/capacity, include resource block variants, count newly acquired items,
  handle bounded searches, deliver resources, and return when requested.
- `combat`: verify equipment, locate within the current dimension, rescan exact entity types,
  execute bounded combat batches, reconcile the kill quota, and monitor health/food.

The model-facing Numen descriptions are corrected so they no longer recommend unavailable
`break_block`, `place_block`, or `deposit_items` tools. `build` alone accepts up to 256 KiB of UTF-8
arguments for a detailed compact structure plan; other tools keep the 16 KiB cap.

## Practical expectations

- **Diamonds:** a good current job. The mining workflow targets both normal and deepslate diamond
  ore, but Numen 0.1.1 searches known/loaded terrain rather than performing unlimited blind branch
  mining. Give the worker a suitable pick, food, and inventory space.
- **Nearby storage:** the durable workflow can span many open/inspect/transfer/close cycles and may
  craft/place overflow storage. Container-to-container movement still stages through the citizen's
  inventory, so very large warehouses are slower than a purpose-built storage macro.
- **Villa:** the build primitive can resolve up to 16,384 cells. Creative/server-builder bodies are
  the most reliable first use. In survival, one freeform build call preflights all materials against
  the citizen's inventory; a large furnished villa therefore needs staged supplies or a resumable
  blueprint.
- **Wither skeletons:** repeated locate/scan/type-check/attack cycles work when the citizen is already
  in the Nether near an accessible fortress. Numen 0.1.1 has no reliable cross-dimension portal tool,
  so an autonomous Overworld-to-Nether-and-back expedition is not promised yet.

Test long jobs on a copied world before relying on them in the live ATM9 world. The durable journal
protects control flow and recovery; it cannot make an unreachable block, missing tool, unloaded ore,
full container, protected claim, or weak model decision physically succeed.
