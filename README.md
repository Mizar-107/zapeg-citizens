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
only server-allowlisted Numen tools against Atlas's exact UUID. To cancel both the
LLM turn and physical work:

```text
@Atlas stop
```

`/citizen list` shows managed citizens, `/citizen brain-status` reports whether
the shared bridge is configured, and an operator can permanently remove a citizen
with `/citizen remove Atlas`. Removing a live citizen drops its inventory at its
feet before deleting the body and reservation.

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
- The initial worker policy excludes combat, building, item dropping/transfers,
  arbitrary container interaction, and Numen's client-only tools.
- `@Name stop`, logout, bridge failure, and server shutdown cancel pending result
  routes and Numen body work.
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

Player-owned workers are the first supported lifecycle: the logical owner must be
online to issue work, and the body becomes dormant when that player logs out. The
registry already separates logical owner, technical Numen owner, brain controller,
role, and faction so server-owned lore characters can be added without migrating the
data model again.

Stock Numen summon/despawn commands and G-panel summoning are gated; operators use
the managed spawn/remove commands instead. Adoption/reconciliation of companions
created before this addon, true server-owned spawning, offline chunk lifecycle,
home-based death recovery, faction-safe combat, and full ATM9 validation remain
follow-up work. Common enemies and boss combat should use deterministic mob/state
machine AI; see [lore NPC guidance](docs/lore-npcs.md).

## Build and test

```text
./gradlew test build
cd brain
python -m unittest discover -s tests -v
```

The re-obfuscated Forge jar is written to `build/libs/`. Run the isolated smoke
contract in [architecture.md](docs/architecture.md) before copying a build into the
live ZapeG server or client pack.
