# Lore characters, enemies, and bosses

Server-owned citizens are supported, but Numen 0.1.1 cannot safely model them by
passing a null owner. It assumes an owner UUID for persistence, chunk loading,
death recovery, roster sync, and client result delivery.

The registry therefore separates three concerns now:

- **logical owner**: a player UUID or a named server principal;
- **brain controller**: the shared server brain;
- **faction**: allegiance and target policy.

`/citizen spawn-server` therefore uses a stable world-specific technical UUID while
this addon supplies home-based spawn/recovery, continuously refreshed chunk tickets,
public dialogue, private per-player memory, and server result routing. The body keeps
its UUID and inventory across orderly restarts. Death recovery waits 600 ticks, checks
that the configured home has standing room, and fails closed if Numen's persisted
owner identity no longer matches.

Public `@Name` conversation is deliberately dialogue-only. Operators use
`/citizen task <name> <prompt>` when a lore citizen should move, build, fight, or use
containers. This makes character conversation predictable while retaining the full
Numen tool surface for authored events.

## Recommended body and brain by role

| Role | Recommended implementation |
|---|---|
| Player worker/companion | Numen body + shared brain |
| Rare mobile guard or embodied lore character | Numen + deterministic faction/combat policy + LLM dialogue/high-level intent |
| Stationary questgiver/shopkeeper | Easy NPC + optional shared dialogue brain |
| Common enemy | Normal Minecraft mob/Goal AI; no per-enemy LLM |
| Boss | Scripted/custom entity and deterministic phases; LLM only for dialogue or bounded strategic choices |

Numen's melee and ranged actuators have real pathfinding, cooldown, aiming, and
retreat behavior, so an operator-controlled guard can already be useful. They accept
raw entity targets, lack faction/friendly-fire policy, and can inherit navigation
that breaks or places blocks. Automated guards still need deterministic faction and
target selection before they can choose fights without an operator.

Real-time boss actions must never wait for a model response. A server state machine
keeps the fight responsive and fair when Ollama is slow or unavailable; the LLM can
generate taunts, remember encounters, or choose among a small set of server-approved
phase strategies.

## Safe implementation order

1. Prove shared-brain player workers and cancellation. *(Complete.)*
2. Add personas, home anchors, server-owned wake/hibernate, and death recovery.
   *(Complete.)*
3. Add proximity hibernation and schedules for larger lore populations.
4. Add faction relationships and a server-side target validator.
5. Add guards with deterministic target selection and Numen combat actuation.
6. Try one humanoid rival in an arena.
7. Keep ordinary enemy populations and boss mechanics deterministic.
