# Shared-brain deployment

## Recommended topology

Run the brain on the same host as Minecraft, as a separate container on the same
private Docker network:

```text
players -> Minecraft :25565 -> citizen-brain :8787 -> Ollama HTTPS
                                  |-> SQLite volume
```

Only Minecraft's game port is public. Do not publish brain port 8787, Ollama port
11434, or RCON. The brain needs outbound HTTPS to Ollama but no Minecraft data mount,
RCON password, server logs, or Docker socket.

## Secrets and settings

Generate a long random bridge token. Give the same bridge token to Minecraft and the
brain, but keep the Ollama key only on the brain:

```text
# Minecraft container
CITIZENS_BRAIN_URL=http://citizen-brain:8787
CITIZENS_BRAIN_TOKEN_FILE=/run/secrets/citizens_brain_token
# Budget above sidecar queue timeout + provider socket timeout + overhead.
CITIZENS_BRAIN_REQUEST_TIMEOUT_MS=150000
# Bounds a complete model/tool loop, including a lost Numen completion callback.
CITIZENS_BRAIN_TURN_TIMEOUT_MS=600000

# Brain container
CITIZENS_BRAIN_TOKEN_FILE=/run/secrets/citizens_brain_token
CITIZENS_LLM_URL=https://ollama.com/api/chat
CITIZENS_LLM_API_KEY_FILE=/run/secrets/citizens_ollama_key
CITIZENS_LLM_MODEL=<tool-capable model name>
CITIZENS_LLM_QUEUE_TIMEOUT_SECONDS=20
CITIZENS_LLM_TIMEOUT_SECONDS=90
CITIZENS_DB_PATH=/data/citizens-brain.sqlite3
```

Plain environment variables are supported for development, but Docker secret files
are preferred on the host. Never commit a populated `.env`, copy it into the client
pack, or reuse the bridge token as the provider key.

For a host-local Ollama installation, set the URL to its private/container-network
address. `localhost` inside the brain container refers to the brain container itself.

For ordinary responses, budget the mod request timeout above the sidecar queue
timeout plus provider socket timeout, with network/database overhead. The defaults
are 150 seconds in the mod versus 20 + 90 seconds in the sidecar. If those sidecar
values are increased, raise `CITIZENS_BRAIN_REQUEST_TIMEOUT_MS` as well (the mod's
accepted maximum is 600,000 ms), or concurrent citizens can time out while waiting
for the shared provider slot. This is capacity planning, not a hard provider
wall-clock bound: a peer that continuously trickles bytes can outlive a socket
timeout. The mod request timeout still releases the caller, and the ten-minute
whole-turn watchdog clears local/physical work; an already-running provider request
may consume one call after cancellation.

## What belongs in the modpack

Initial rollout should put the exact official Numen jar and the ZapeG Citizens jar on
both server and clients. The brain service, SQLite database, provider model name, and
all secrets are host infrastructure—not modpack files. Players configure no provider
and need no key.

Once the server-only handshake is validated across the complete pack, the addon can
be omitted from clients; Numen still has client components and remains in the pack.

## Local versus host operation

The host deployment is the production choice: it stays available with the server,
keeps the key in one administrative boundary, and is ready for future server-owned
characters. Running the brain on the project owner's PC is appropriate for testing.
For a remote Minecraft host, connect the two through a private authenticated network
such as Tailscale or an SSH tunnel. Do not open the brain listener to the internet.

## Rollout order

1. Mock-provider sidecar tests.
2. Minimal copied Forge world with one user and movement only.
3. One real Ollama turn with a strict budget.
4. Mining/crafting in an unclaimed disposable area.
5. Two-client ownership and cancellation test.
6. Copied ATM9 world, claims/graves/tab/sleep/chunk-cost test.
7. Versioned prerelease jar and reviewed server/client pin update.

Keep the current live server untouched until all seven stages pass.
