# Lore characters, enemies, and bosses

Server-owned citizens are feasible, but Numen 0.1.1 cannot safely model them by
passing a null owner. It assumes a human owner UUID for persistence, chunk loading,
death recovery, roster sync, and client result delivery.

The registry therefore separates three concerns now:

- **logical owner**: a player UUID or a named server principal;
- **brain controller**: the shared server brain;
- **faction**: allegiance and target policy.

A later `/citizen spawn-server` path can use a stable technical UUID while this addon
supplies home-based spawn/respawn, proximity/task chunk tickets, conversation access,
and server result routing. That lifecycle is deliberately not exposed until it is
safe across restarts and deaths.

## Recommended body and brain by role

| Role | Recommended implementation |
|---|---|
| Player worker/companion | Numen body + shared brain |
| Rare mobile guard or embodied lore character | Numen + deterministic faction/combat policy + LLM dialogue/high-level intent |
| Stationary questgiver/shopkeeper | Easy NPC + optional shared dialogue brain |
| Common enemy | Normal Minecraft mob/Goal AI; no per-enemy LLM |
| Boss | Scripted/custom entity and deterministic phases; LLM only for dialogue or bounded strategic choices |

Numen's melee and ranged actuators have real pathfinding, cooldown, aiming, and
retreat behavior, so a future guard can be useful. They currently accept raw entity
targets, lack faction/friendly-fire policy, and can inherit navigation that breaks or
places blocks. Combat tools are therefore excluded from the worker profile.

Real-time boss actions must never wait for a model response. A server state machine
keeps the fight responsive and fair when Ollama is slow or unavailable; the LLM can
generate taunts, remember encounters, or choose among a small set of server-approved
phase strategies.

## Safe implementation order

1. Prove shared-brain player workers and cancellation.
2. Add home anchors, server-owned wake/hibernate, and death recovery.
3. Add faction relationships and a server-side target validator.
4. Add guards with deterministic target selection and Numen combat actuation.
5. Try one restricted humanoid rival in an arena.
6. Keep ordinary enemy populations and boss mechanics deterministic.
