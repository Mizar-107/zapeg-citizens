# ZapeG Citizens shared brain

This is the private LLM sidecar for ZapeG Citizens. One instance can plan for every citizen while the Minecraft mod remains authoritative for identity, permissions, tools, movement, inventory, and combat. Players never receive the Ollama key.

The service uses only the Python standard library. It provides Ollama-native `/api/chat` tool calling, SQLite conversation memory scoped to `citizen.id + actor.id`, authenticated HTTP, idempotent starts, persisted tool-waiting turns, and conservative resource limits. A process restart can resume a turn waiting for a Minecraft tool result. A turn that was inside an Ollama request cannot be resumed, so startup marks it failed immediately instead of blocking its citizen until the active-turn TTL; retry it with a new `request_id`.

## Run it

Copy `.env.example` to `.env`, replace every placeholder, then build and run:

```sh
docker build -t zapeg-citizen-brain .
docker run --rm --name zapeg-citizen-brain \
  --env-file .env \
  --mount source=zapeg-citizen-brain-data,target=/data \
  --publish 127.0.0.1:8787:8787 \
  zapeg-citizen-brain
```

For the game host, put Minecraft and this container on the same private Docker network and use `http://citizen-brain:8787` from the mod. Prefer `expose: ["8787"]` with no host `ports` entry. The bearer token is a separate service credential; it is not the Ollama key.

For Ollama Cloud, keep `CITIZENS_LLM_URL=https://ollama.com/api/chat` and set the API key. For a local Ollama daemon reached from Docker Desktop, use `http://host.docker.internal:11434/api/chat` and omit `CITIZENS_LLM_API_KEY` entirely. Configuration fails closed if a key is paired with plain HTTP, and provider redirects are disabled so an authorization header cannot cross origins.

Secrets may instead be mounted as files with `CITIZENS_LLM_API_KEY_FILE` and `CITIZENS_BRAIN_TOKEN_FILE`. The service rejects configurations that set both a direct value and a file for the same secret. It never logs authorization headers, request bodies, provider payloads, or provider responses.

## Protocol 2

All `/v1/*` requests require `Authorization: Bearer <CITIZENS_BRAIN_TOKEN>` and `Content-Type: application/json`. `GET /healthz` is intentionally unauthenticated for container health checks.

Start a turn with `POST /v1/turn/start`:

```json
{
  "protocol": 2,
  "request_id": "a-unique-id-for-retries",
  "citizen": {
    "id": "citizen-uuid",
    "name": "Atlas",
    "owner_kind": "PLAYER",
    "owner_id": "player-uuid",
    "role": "miner",
    "faction": "village",
    "interaction_mode": "TASK",
    "persona": "A practical village miner who speaks plainly."
  },
  "actor": {"id": "speaker-uuid", "name": "PlayerName"},
  "prompt": "go collect iron",
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "collect_items",
        "description": "Collect an item from the world.",
        "parameters": {
          "type": "object",
          "properties": {"item": {"type": "string"}},
          "required": ["item"]
        }
      }
    }
  ]
}
```

`interaction_mode` is required and accepts exactly `TASK` or `DIALOGUE`.
`TASK` turns may receive the server's validated world tools. `DIALOGUE` turns must
send an empty `tools` array; the sidecar omits tools from the Ollama request and
rejects any tool call a provider nevertheless returns. This makes dialogue-only
lore characters incapable of initiating world actions through the brain protocol.

`persona` is required but may be an empty string. The authenticated Minecraft
server supplies it together with name, role, and faction as a trusted character
profile. The model uses that profile for identity, lore, goals, knowledge, and
speaking style, while the fixed operational prompt remains authoritative. Persona
text cannot grant permissions, authorize combat targets, or enable tools. Persona
length is bounded by `CITIZENS_MAX_PERSONA_CHARS` (4,096 by default, configurable
up to the hard maximum of 16,384).

The response is either final speech:

```json
{"protocol":2,"turn_id":"turn_...","kind":"final","speech":"On it."}
```

or one server-validated tool request:

```json
{
  "protocol": 2,
  "turn_id": "turn_...",
  "kind": "tool_call",
  "tool_call": {
    "id": "call_...",
    "name": "collect_items",
    "arguments": {"item": "iron_ore"}
  }
}
```

After the mod executes that call, it posts the result to `POST /v1/turn/continue`:

```json
{
  "protocol": 2,
  "turn_id": "turn_...",
  "tool_call_id": "call_...",
  "result": {"ok": true, "collected": 8}
}
```

Ollama can request parallel calls. The sidecar persists all of them, emits them one at a time, attaches each result using Ollama's `tool_name` field, and asks the model again only after the entire batch finishes. No call is silently discarded. Total emitted calls are bounded by `CITIZENS_MAX_TOOL_STEPS`.

Cancel a waiting or executing turn with `POST /v1/turn/cancel`:

```json
{"protocol":2,"turn_id":"turn_..."}
```

If the initial HTTP response was lost before the mod learned `turn_id`, cancel by the original idempotency key instead:

```json
{"protocol":2,"request_id":"a-unique-id-for-retries"}
```

Provide exactly one of `turn_id` or `request_id`. Both forms resolve to the stored turn and repeated cancellation is safe. If request-ID cancellation arrives before start has inserted its row, the sidecar returns `kind: "canceled"` with `turn_id: null` and stores a bounded tombstone. A later start with that request ID is refused, closing the asynchronous stop/start race.

`request_id` makes start retries idempotent. Only one turn may be active for each citizen regardless of actor, old active turns expire, the global number of active turns is capped, request/provider bodies are capped, and Ollama concurrency defaults to one. A request waits at most `CITIZENS_LLM_QUEUE_TIMEOUT_SECONDS` (20 seconds by default) for that slot and fails safely as busy instead of blocking forever. The default provider socket-operation timeout is 90 seconds. It is not a hard wall-clock ceiling for a peer that continuously trickles bytes; the mod's HTTP timeout and whole-turn watchdog are the authoritative caller-side bounds, although an already-running provider request may still consume one call after cancellation.

Tool descriptions are limited individually by `CITIZENS_MAX_TOOL_DESCRIPTION_CHARS` (4,096 by default), which accommodates Numen's detailed BuildTool contract. The normalized collection remains independently bounded by `CITIZENS_MAX_TOOL_SCHEMA_BYTES` (131,072 bytes by default) and `CITIZENS_MAX_TOOLS` (64 by default). The per-description cap can be raised up to 65,536 characters when a pinned Numen version requires it without removing the aggregate request bound.

Completed conversational memory is retained up to `CITIZENS_MAX_HISTORY_MESSAGES` for the exact citizen/actor pair. Separate terminal turn records support idempotent replay and contain the bounded execution trace; API maintenance prunes records older than `CITIZENS_TERMINAL_TURN_TTL_SECONDS` (one day by default) and caps them globally at `CITIZENS_MAX_TERMINAL_TURNS` (1,000 by default). Request-cancellation tombstones use the same maintenance-triggered age and count bounds. A `request_id` can be replayed or kept canceled only while its record remains inside both limits.

For normal provider responses, budget the Minecraft mod's `CITIZENS_BRAIN_REQUEST_TIMEOUT_MS` above the sidecar queue timeout plus provider timeout and network overhead. The defaults are 20 + 90 seconds on the sidecar versus 150 seconds in the mod. This reduces ordinary queued-request timeouts but is not a hard total deadline because Python's provider timeout applies to socket operations. The mod also defaults `CITIZENS_BRAIN_TURN_TIMEOUT_MS` to 600,000 ms, which clears local/physical work if a complete model-and-tool loop hangs.

`owner_kind` accepts `PLAYER` or `SERVER`. A player-owned citizen requires `owner_id`; a server-owned lore citizen may use `null`.

## Test

From this directory:

```sh
python -m unittest discover -s tests -v
```

The suite uses temporary SQLite databases, a fake provider, and loopback HTTP servers; it needs no Ollama key or external network access.
