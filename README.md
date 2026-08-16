# ZapeG Citizens

Forge 1.20.1 addon for operator-provisioned, LLM-controlled citizens. A single
host-side brain and provider key can serve every citizen; players never create,
receive, or configure API keys.

Numen supplies the fake-player body, inventory, pathfinding, and Minecraft tools.
This project owns provisioning, logical ownership, chat routing, the shared brain
protocol, tool policy, cancellation, and persistent conversation memory.

## Player flow

An operator assigns a named citizen to an online player:

```text
/citizen spawn Atlas Alice
```

Alice gives Atlas a private natural-language task in ordinary chat:

```text
@Atlas go collect iron
```

The addressed message is removed from public chat. The server verifies the managed
citizen and logical owner, sends the turn to the private brain service, and executes
the explicitly registered server-capable Numen tools against Atlas's exact UUID.
To cancel both the LLM turn and physical work:

```text
@Atlas stop
```

`/citizen list` shows managed citizens, `/citizen brain-status` reports whether
the shared bridge is configured, and an operator can permanently remove a citizen
with `/citizen remove Atlas`. Removing a live citizen drops its inventory at its
feet before deleting the body and reservation.

## Server-owned lore citizens

An operator can create a citizen that belongs to the world rather than a player:

```text
/citizen spawn-server Edda lore village An old road warden who remembers every traveler.
```

Any player can then speak to Edda in ordinary public chat with `@Edda <message>`.
The addressed message remains public, the reply is broadcast as `[Edda] ...`, and
conversation memory stays isolated per player. Public lore dialogue receives no
world tools. An in-game operator can deliberately assign physical work—with all 32
server-capable Numen tools—using `/citizen task Edda <task>`, and cancel it with
`/citizen stop Edda`.

Server citizens have a persistent persona, role, faction, technical owner, and home
anchor. `/citizen persona Edda <text>` changes the profile for future turns;
`/citizen set-home Edda` moves its recovery anchor to the command source; and
`/citizen wake Edda` retries lifecycle reconciliation. Healthy server citizens stay
awake without a human owner, keep the same UUID and inventory across restarts, and
recover at home 30 seconds after death. Each always-awake body refreshes Numen's
radius-two chunk ticket, so use this lifecycle for a modest number of important lore
characters rather than ordinary enemy populations.

## One key, not one key per player

The provider key exists only in the brain container's environment or secret file.
It is never placed in a client profile, mod config, world data, Minecraft packet,
or distributable pack. The Minecraft server talks to the brain over a private HTTP
endpoint protected by a separate bridge token.

The recommended production placement is next to the Minecraft container on the
host's private Docker network. Running it on a personal computer is useful for
development, but production then depends on that computer and requires a secured
VPN/tunnel. Never expose Ollama's local API or the brain port directly to the public
internet.

See [deployment](docs/deployment.md) for the exact split between host, server, and
client pack.

## Safety boundary

- One active turn per citizen; the mod applies a ten-minute overall watchdog, and
  the sidecar also bounds global model concurrency, prompt/body sizes, tool steps,
  and turn lifetime.
- Tool names and arguments are model output, not authority. The Forge mod resolves
  an explicit allowlist and Numen validates typed arguments again.
- The worker policy exposes all 32 Numen 0.1.1 tools that can run through the
  dedicated-server bridge, including building, combat, item transfers, blueprints,
  and container interaction. Numen's `todowrite` and `load_skill` remain excluded
  because they are client-only helpers with no dedicated-server execution context.
- Player `@Name stop`, operator `/citizen stop Name`, logout, bridge failure, and
  server shutdown cancel pending result routes and Numen body work.
- Pinned Mixin hooks capture completed Numen tasks for the host brain, reject
  independent client tool/cancel/dismiss control of managed bodies, and block stock
  summons that could bypass global name reservations.
- Audit logs contain identities, step counts, and tool names—not API keys or prompt
  text.

“Collect iron” is real agent work, not `/give`: success still depends on tools,
reachable ore, claims/protection, hazards, loaded chunks, and model quality. Start
with a copied world and one citizen in an unclaimed vanilla-like area.

## Runtime

- Minecraft 1.20.1
- Java 17
- Forge 47.4.10
- Official Numen 0.1.1 beta (CurseForge file `8551640`)
- Numen API `0.0.8-SNAPSHOT`, embedded by Numen
- Python 3.12 sidecar with no third-party runtime packages

The build pins and verifies the exact Numen/API SHA-256 values before compilation.
Numen's internal task/lifecycle seams are isolated because its public API does not
yet expose a server-side result sink.

## Current scope

Player-owned workers and always-awake server-owned lore citizens are supported.
Player workers become dormant when their owner logs out. Server citizens use a
world-specific technical principal, keep their body ticket refreshed, snapshot
their last position, hibernate during orderly shutdown, and wake or recover with
the same identity when the server returns.

Stock Numen summon/despawn commands and G-panel summoning are gated; operators use
the managed spawn/remove commands instead. Adoption/reconciliation of companions
created before this addon, proximity-based hibernation, scheduled autonomous work,
faction/ally-aware combat validation, and full ATM9 validation remain follow-up
work. Raw Numen combat is available to operator-assigned tasks; the addon does not
yet add faction or friendly-fire rules around it. Common enemies and boss combat
should use deterministic mob/state machine AI; see
[lore NPC guidance](docs/lore-npcs.md).

## Build and test

```text
./gradlew test build
cd brain
python -m unittest discover -s tests -v
```

The re-obfuscated Forge jar is written to `build/libs/`. Run the isolated smoke
contract in [architecture.md](docs/architecture.md) before copying a build into the
live ZapeG server or client pack.
