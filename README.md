# ZapeG Citizens

Forge 1.20.1 addon for OP-provisioned, player-owned LLM worker citizens. It uses
[Numen](https://github.com/Dwinovo/minecraft-numen) for the fake-player body,
pathfinding, tools, and owner-side LLM loop; this repository owns provisioning and
safe chat routing.

## First vertical slice

An operator creates a named citizen for an **online** player:

```text
/citizen spawn Atlas Alice
```

The assigned player gives that specific citizen a natural-language task in chat:

```text
@Atlas go collect iron
```

The addressed message is canceled before public broadcast. The server verifies that
Atlas is in the addon's persistent managed registry and belongs to the sender, then
forwards the prompt only to Alice's client. Numen's built-in planner turns it into
movement/mining/crafting tool calls. Ordinary chat is untouched. `/citizen list`
shows live and dormant managed citizens to operators.

Managed names are globally reserved case-insensitively, so `Atlas` and `atlas` cannot
become two citizens. Repeating the spawn command for an already-live citizen is safe.

## Exact runtime

- Minecraft 1.20.1
- Java 17
- Forge 47.4.10
- Official Numen 0.1.1 beta on the server and every participating client
- This addon on the server and every participating client

The development runtime pins the official 1.20.1 Numen release by CurseForge file ID
`8551640`; it is a runtime dependency, never bundled into this addon's jar.

Each assigned player configures their own OpenAI-compatible provider/API key in
Numen's `G` panel. If the player has exactly one provider when the citizen is spawned,
the addon selects it automatically. An existing valid binding is preserved. Numen
0.1.1 has no picker for rebinding an existing companion, so an unbound citizen with
zero or multiple providers requires temporarily configuring exactly one provider and
then having an OP repeat the same `/citizen spawn` command.

## What “go collect iron” means

It is a real agent task, not a scripted `/give`: the LLM inspects the world and asks
the fake-player body to navigate, obtain/equip a suitable pickaxe, mine, and manage its
inventory. Completion is not unconditional: tools, reachable ore, loaded chunks,
claims/protection mods, hazards, and model quality can make a task fail. Start testing
with one citizen in an unclaimed vanilla-like area before trying ATM9 machinery.

## Current constraints

- The owner must be online; the LLM brain runs on their client.
- Managed bodies become dormant when their owner logs out and Numen revives them when
  the owner reconnects.
- Conversations/provider assignments live in that player's client profile.
- The exact Numen beta is pinned. Numen has no public server-side spawn API yet, so
  `NumenServerCompat` isolates the two internal lifecycle calls used by this MVP.
- This first slice adds an OP provisioning path and only routes citizens recorded in
  its world `SavedData`, but it does not yet disable Numen's stock `G`-panel/`/numen`
  self-summon paths. Exclusive OP lifecycle policy is the next patch.
- The addressed input is private. Numen's current speech bubbles are visible to nearby
  players, so responses are not yet private.
- Numen can retain pending prompts on the owner's client across reconnects. A reliable
  `@Name stop` plus queue-clear patch is required before destructive tasks are enabled.
- `@Name stop`, addon-controlled queue caps, quotas, claim integration, offline
  autonomy, and server-held provider keys are not implemented yet.

## Build

```text
./gradlew build
```

The release jar is written under `build/libs/`. Do not copy it into `zapeg-server`
until the isolated client/server smoke test in [`docs/architecture.md`](docs/architecture.md)
passes.

Current automated verification covers five unit tests, exact Numen dependency
checksums, the re-obfuscated Forge build, and an isolated dedicated-server boot. The
two-client interaction/tool test and full ATM9 compatibility test are still pending.
