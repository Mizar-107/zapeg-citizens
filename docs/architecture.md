# Architecture and smoke-test contract

## Flow

```text
OP /citizen spawn Atlas Alice
  -> dedicated server creates a Numen fake ServerPlayer owned by Alice's UUID
  -> addon reserves case-folded name + citizen UUID + owner UUID in world SavedData
  -> Numen persists UUID, owner, location, inventory, and death state
  -> server syncs Alice's roster and tells her client the citizen is ready

Alice: @Atlas go collect iron
  -> Forge ServerChatEvent parses and cancels the addressed message
  -> server resolves a managed UUID and verifies Alice is its owner
  -> private S2C packet carries (citizen UUID, prompt) to Alice only
  -> client calls NumenGateway.enqueue
  -> Numen's LLM loop plans and sends owner-authenticated tool requests
  -> fake-player body moves and acts on the server
```

The explicit `@Name` prefix prevents incidental chat from spending tokens or causing
world actions. A two-second owner cooldown is enforced before forwarding.

## Trust boundary

The server is authoritative for OP permission, the managed-citizen ledger, ownership,
and prompt routing. The ledger is stored in the overworld's `SavedData` and therefore
travels with the world backup. Stock-created Numen companions are not accepted by the
`@Name` router.
The owner client holds its own provider key and performs LLM inference. Numen remains
authoritative for tool-call ownership checks and body execution. No Mineflayer login,
Forge handshake emulation, RCON, or raw server-command surface is involved.

## Beta boundary

`NumenGateway` and `NumenPlayer` are public API. Numen 0.1.1 does not expose a public
server spawn-for-owner method, so `NumenServerCompat` temporarily calls internal
`Companions.summon` and `Companions.syncRosterToOwner` from API 0.0.8-SNAPSHOT. Keep
these versions exact and replace that adapter when Numen publishes a server lifecycle
gateway. Client provider auto-selection also touches Numen internals and is deliberately
confined to `ClientPacketHandlers`.

## Smoke test before ATM9

1. Start a copied/throwaway Forge 47.4.10 world with exact Numen 0.1.1 and this addon.
2. Configure one low-cost provider in Alice's Numen panel.
3. As console/OP run `/citizen spawn Atlas Alice`; verify Atlas appears once.
4. Repeat the command with `atlas`; verify no second UUID is created.
5. Alice sends `@Atlas follow me for 20 blocks`; verify it is not public chat and Atlas acts.
6. Bob sends `@Atlas follow me`; verify Bob gets a private denial and no agent turn starts.
7. Alice sends ordinary chat; verify it remains ordinary chat.
8. Disconnect Alice; verify Atlas becomes dormant. Reconnect and verify its UUID,
   inventory, and position persist.
9. Try a dropped-item pickup, then low-risk mining with tools already supplied.
10. Only after those pass, test one citizen in an unclaimed area of a copied ZapeG world.

## Next engineering slice

- Disable or policy-gate Numen's stock summon/dismiss paths so only `/citizen` controls lifecycle.
- Add an admin adoption/reconciliation command for any Numen bodies created before the ledger.
- Add `/citizen remove`, `/citizen stop`, quotas, audit persistence, and queue caps.
- Cancel work and make bodies dormant on owner disconnect.
- Test FTB Chunks/claims, graves, teams, tab list, sleeping, and chunk-ticket cost.
- Decide whether offline workers justify a server/sidecar-hosted brain in phase two.
