# Trusted workflow: combat

Use this workflow for a deliberate hunt or fight.

1. Call `get_self_status` before combat. Verify dimension, health, hunger, armor, weapon, ammunition
   and recovery food. Pause for equipment or food rather than starting an expedition unprepared.
2. Structure lookup is dimension-local. A Nether fortress can only be located while already in the
   Nether. Do not claim that ordinary `goto` can cross dimensions; portal travel needs an explicit
   portal-capable job step and confirmation of the resulting dimension.
3. `scan_nearby_entities` returns at most the 20 nearest entities and filters only broad categories.
   Select targets whose returned type matches the requested species, and pass only their current
   runtime ids to `melee_attack` or `ranged_attack`. Runtime ids are ephemeral: rescan after a restart,
   dimension transition, lost target or completed combat batch.
4. Melee combat switches weapons, chases explicit targets and collects their drops. Ranged combat
   needs a bow/crossbow and ammunition; collect drops afterward. Never manufacture ids or attack a
   nearby entity merely because the desired type was absent from a truncated scan.
5. A quota hunt is a repeated observe/attack/verify loop. Count confirmed target deaths, not attack
   attempts or model assertions. Checkpoint the quota and inventory after every batch. Patrol or wait
   only inside the authorized structure/area and within a bounded time budget. Keep looping until the
   original quota is met; do not wait for the player to say continue after each attack.
6. Recheck health and food between batches. Retreat or pause below the configured thresholds. On
   death or restart, rescan and reconcile the confirmed quota before taking another action.
7. Completion requires postcondition evidence: requested number of the exact entity type defeated,
   relevant drops collected when possible, citizen alive or explicitly recovered, and any requested
   return-home step confirmed.
