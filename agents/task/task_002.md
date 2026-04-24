# Architecture of llama.cpp server with edge ai gateway (with agent implementation)

## Note
- init: This task is to define the architecture, not implementation.

## Task Description
1. Define tech stack use in the project, include llama.cpp server as a side process
    - In `applicationbase` and `desktopplatform` module already have LFU, LRU cache implementation, Sqlite implementation and Json Utils
    - Define the agent framework, maybe self-implemented or based on existing open-source agent framework, compare the pros and cons first before decide which one to use
    - Define the MCP client and server stack.
2. Draw component diagram into the output section, include the interaction between components and data flow.
3. Define the API between llama.cpp server and edge ai gateway, include the request and response format, error handling, etc.

## Deliverables

- D1. Tech stack with rationale — inference sidecar, agent framework decision, MCP stack, persistence, HTTP
- D2. Component diagram and end-to-end data flow for an agent chat turn
- D3. Contract between gateway and llama-server — endpoints, request/response shapes, error handling, lifecycle
- D4. Contract between gateway and its HTTP clients — /api/chat SSE event schema

## Output (agent response content here)

### D1 — Tech Stack

#### Inference runtime

**`llama-server` as a sidecar process**, spawned and supervised by the gateway. OpenAI-compatible HTTP API (`/v1/chat/completions`, `/health`, `/tokenize`, `/slots`) consumed by the Java layer. On RK3588 the binary is built from `rk-llama.cpp` (branch `rknpu2`) with `-DGGML_RKNPU2=ON`; on x86_64 it is built from upstream `llama.cpp`. The gateway does not link any native code itself.

*Why not the JNI binding:* see task_001 Output and the note in `docs/specs/project-specs.md` (Apr-24). ABI drift between `kherud/java-llama.cpp`'s pinned upstream commit and `rk-llama.cpp` HEAD causes SIGSEGV in `libjllama.so`. The sidecar approach sidesteps all JNI/ABI fragility and is functionally equivalent for our feature set.

#### Persistence

- **SQLite** via `org.thingai.platform.dao.DaoSqlite` from `desktopplatform`, driving entities annotated with `@DaoTable` / `@DaoColumn` from `applicationbase`.
- **Schema migrations** through `org.thingai.base.dao.Migration` / `MigrationContext`.
- **Connection pool:** HikariCP (already declared in `build.gradle.kts`).
- Tables: `sessions`, `session_messages`, `agents`, `tool_call_records` (optional audit trail).

#### Caching

- **LFU** for agent definitions (small, frequently read): `org.thingai.platform.cache.LFUCache`.
- **LRU** for hot sessions (bounded recency): `org.thingai.platform.cache.LRUCache`.
- All cache types wrap `org.thingai.base.cache.Cache` — eviction hooks available via `EvictionListener`.

#### Agent framework — self-implemented

Trade-off summary of the realistic options on a limited-hardware Java edge target:

| Option | Pros | Cons |
|---|---|---|
| **Self-implemented** (chosen) | Zero external deps, fits edge footprint, full control of tool loop, matches existing stubs (`AgentEntity`, `AgentManager`, `ToolRegistry`), plays cleanly with the MCP SDK already on the classpath | More code to write and maintain; no ecosystem patterns out of the box |
| **LangChain4j** | Mature, rich ecosystem, built-in tool/memory abstractions, OpenAI-compatible out of the box | ~20+ MB of JARs; opinionated abstractions conflict with existing scaffolding; pulls transitive deps; heavy for edge |
| **Spring AI** | Polished agent/tool/memory APIs | Requires Spring context — far too heavy for this project; philosophical mismatch with Javalin |
| **Semantic Kernel for Java** | Microsoft-backed | Immature on JVM, uncertain maintenance, same weight concerns as LangChain4j |

**Decision:** self-implement, using the existing scaffold (`AgentEntity`, `AgentManager`, `InferenceEngine`, `ToolRegistry`, `Session`, `SessionMessage`) as the skeleton. Reuse llama-server's server-side **Jinja tool-call parsing** (`--jinja` flag) so the gateway receives structured `tool_calls` in the response rather than parsing model text — this is the single biggest complexity the framework would otherwise solve for us.

#### MCP stack

- **Client:** `io.modelcontextprotocol.sdk:mcp-core:1.1.1` (already a dependency). Gateway acts as an **MCP client** to external MCP servers (local or remote) to source tools.
- **Server:** gateway also **exposes its own built-in tools over MCP** so other processes/services can consume them. Same mcp-core SDK.
- Discovered MCP tools are registered into the gateway's `ToolRegistry` alongside native Java tools, so the agent loop sees one unified tool surface.
- JSON via Gson (already in `build.gradle.kts`); mcp-core configured to use Gson rather than bundled JSON.

#### HTTP

- **Javalin 7.1.0** for inbound REST + SSE (`/api/chat`, `/api/agents`, `/api/sessions`, `/api/health`).
- **`java.net.http.HttpClient`** (JDK 21 built-in) for outbound calls to llama-server — no extra HTTP client dep. Streaming via JDK's `BodyHandlers.ofLines()` (parses SSE from llama-server).

#### Lifecycle

The existing `org.thingai.base.Service` base class in `EdgeAiGateway` governs startup/shutdown. `LlamaServerProcess` is created in `onServiceInit()` and stopped in `onServiceShutdown()`.

---

### D2 — Component Diagram & Data Flow

#### Component diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Edge AI Gateway (JVM)                      │
│                                                                         │
│  ┌─────────────┐      ┌────────────────┐      ┌───────────────────┐     │
│  │  ApiServer  │──────▶   RouteChat    │──────▶    AgentRunner    │     │
│  │  (Javalin)  │      │  (SSE handler) │      │  (orchestration)  │     │
│  └─────────────┘      └────────────────┘      └────────┬──────────┘     │
│                                                        │                │
│                           ┌────────────────────────────┼─────────────┐  │
│                           ▼                            ▼             ▼  │
│                  ┌────────────────┐         ┌──────────────┐  ┌──────────┐
│                  │ SessionStore   │         │ AgentManager │  │  Tool-   │
│                  │ (LRU + SQLite) │         │  (LFU+DAO)   │  │ Registry │
│                  └────────────────┘         └──────────────┘  └────┬─────┘
│                                                                    │    │
│                                                   ┌────────────────┤    │
│                                                   │                │    │
│                                            ┌──────▼─────┐   ┌──────▼───┐│
│                                            │ Native     │   │  MCP     ││
│                                            │ Java tools │   │  Client  ││
│                                            └────────────┘   └────┬─────┘│
│                                                                  │      │
│  ┌──────────────────────┐                                        │      │
│  │ LlamaServerProcess   │       ┌────────────────────┐           │      │
│  │ (ProcessBuilder,     │──────▶│ LlamaServerClient  │           │      │
│  │  lifecycle, logs)    │       │ (java.net.http)    │           │      │
│  └──────────┬───────────┘       └─────────┬──────────┘           │      │
│             │                             │                      │      │
│  ┌──────────▼───────────┐                 │                      │      │
│  │ SQLite  (DaoSqlite)  │                 │                      │      │
│  │ sessions,messages,…  │                 │                      │      │
│  └──────────────────────┘                 │                      │      │
└───────────────────────────────────────────┼──────────────────────┼──────┘
                                            │                      │
                                            ▼                      ▼
                             ┌──────────────────────┐    ┌────────────────────┐
                             │   llama-server       │    │  External MCP      │
                             │   (child process)    │    │  servers (stdio /  │
                             │   HTTP :8081         │    │  HTTP)             │
                             │   rk-llama.cpp /     │    └────────────────────┘
                             │   llama.cpp binary   │
                             └──────────────────────┘
```

#### Data flow — one agent chat turn

```
Client                 RouteChat          AgentRunner        SessionStore    ToolRegistry      LlamaServerClient      llama-server
  │                        │                  │                   │                │                    │                    │
  │ POST /api/chat         │                  │                   │                │                    │                    │
  │ {sessionId, input}     │                  │                   │                │                    │                    │
  ├───────────────────────▶│ open SSE         │                   │                │                    │                    │
  │                        ├─────────────────▶│ run(session,input,sink)            │                    │                    │
  │                        │                  ├──load session────▶│                │                    │                    │
  │                        │                  │◀──messages[]──────┤                │                    │                    │
  │                        │                  ├──getToolSchemas───────────────────▶│                    │                    │
  │                        │                  │◀──openai-schema[]──────────────────┤                    │                    │
  │                        │                  ├─append user msg──▶│                │                    │                    │
  │                        │                  │                   │                │                    │                    │
  │                        │                  ├──POST /v1/chat/completions (stream)───────────────────▶ │───────────────────▶│
  │                        │                  │                   │                │                    │                    │ infer
  │◀── event: llm_start ───┤◀─────────────────┤                   │                │                    │◀── SSE tokens ─────│
  │◀── event: token ×N ────┤                  │                   │                │                    │                    │
  │                        │                  │◀── tool_calls[] ─ (final msg) ─────│                    │                    │
  │                        │                  ├──execute(toolCall)────────────────▶│                    │                    │
  │◀── event: tool_call ───┤◀─────────────────┤                   │                ├─ invoke (native or MCP)                  │
  │                        │                  │                   │                │                    │                    │
  │◀── event: tool_result ─┤◀─────────────────┤◀── tool result ───┼────────────────┤                    │                    │
  │                        │                  ├─append tool msg──▶│                │                    │                    │
  │                        │                  ├──POST /v1/chat/completions (2nd turn) ──────────────────┼───────────────────▶│
  │◀── event: token ×N ────┤                  │                   │                │                    │                    │
  │                        │                  │◀── final assistant message ────────│                    │                    │
  │                        │                  ├─append assistant─▶│                │                    │                    │
  │◀── event: final ───────┤◀─────────────────┤                   │                │                    │                    │
  │◀── SSE close ──────────┤                  │                   │                │                    │                    │
```

**Notes on flow:**
- SessionStore is two-tier: LRU cache hit is RAM-only; miss hydrates from SQLite.
- AgentRunner owns the tool-call loop and bounds it (e.g. max 8 iterations) to prevent runaway recursion.
- Client disconnect aborts the outbound stream to llama-server and halts the loop.
- All intermediate SessionMessages (user, assistant-with-tool-calls, tool-result, final assistant) are persisted so history on subsequent turns reconstructs the full tool-use trace.

---

### D3 — Gateway ↔ llama-server contract

Gateway is the only consumer; llama-server is managed as a private child process and not exposed to external clients.

#### Endpoints consumed

| Endpoint | Purpose | When used |
|---|---|---|
| `GET /health` | Readiness check | Polled on startup; used by `LlamaServerProcess` to know when to declare "ready" |
| `POST /v1/chat/completions` | Inference (streaming and non-streaming) | Every agent turn |
| `POST /tokenize` | Token count for context-budget decisions | Before building `messages[]` that might exceed `-c` |
| `GET /props` | Model metadata (context size, model name) | Once at startup, cached |

#### Startup

llama-server spawned with:
```
llama-server \
    -m $EDGE_AI_LLAMA_MODEL_PATH \
    --host 127.0.0.1 --port $EDGE_AI_LLAMA_SERVER_PORT \
    -ngl 999 \
    -c 4096 \
    --parallel 1 \
    --jinja \
    --log-disable
```
- `-ngl 999` activates the RKNPU2 backend on RK3588 (ignored on x86_64 CPU builds).
- `--jinja` enables server-side parsing of OpenAI-style `tools` and emission of structured `tool_calls` in responses.
- `--parallel 1` matches single-gateway-single-slot assumption; raise later if concurrency needed.

Readiness: gateway polls `GET /health` every 500ms for up to 60s; treats the process as failed if it exits before ready.

#### Shutdown

On `onServiceShutdown()`: send SIGTERM, wait 5s, SIGKILL if still alive.

#### Request shape — `/v1/chat/completions`

```json
{
  "model": "gemma",
  "stream": true,
  "temperature": 0.7,
  "top_p": 0.95,
  "top_k": 40,
  "max_tokens": 1024,
  "stop": ["<end_of_turn>"],
  "messages": [
    {"role": "system", "content": "<agent.systemInstruction>"},
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "...", "tool_calls": [
      {"id": "call_1", "type": "function",
       "function": {"name": "get_time", "arguments": "{}"}}
    ]},
    {"role": "tool", "tool_call_id": "call_1", "content": "..."}
  ],
  "tools": [
    {"type": "function",
     "function": {"name": "get_time",
                  "description": "...",
                  "parameters": { "type": "object", "properties": {...} }}}
  ]
}
```

Sampling params sourced from: agent defaults (`AgentEntity.samplingParams`) overridden by session (`Session.temperature/topK/topP`).

#### Response shape — streaming

SSE frames (`text/event-stream`), each `data: {...}` line is a JSON chunk:
```
data: {"choices":[{"delta":{"role":"assistant"},"index":0}]}
data: {"choices":[{"delta":{"content":"Hello"},"index":0}]}
data: {"choices":[{"delta":{"content":" there"},"index":0}]}
data: {"choices":[{"delta":{"tool_calls":[{"id":"call_1","type":"function",
        "function":{"name":"get_time","arguments":"{}"}}]},"index":0,
        "finish_reason":"tool_calls"}]}
data: [DONE]
```

Gateway accumulates `delta.content` into `SessionMessage`, and `delta.tool_calls` into a parallel structure. `finish_reason` values: `stop`, `length`, `tool_calls`, `content_filter`.

#### Error handling

| Condition | Detection | Gateway action |
|---|---|---|
| Server not ready | `/health` 503 or connection refused | Abort startup with clear error; don't start `ApiServer` |
| Server died during request | `IOException` on connection | Emit `error` SSE event; mark server unhealthy; attempt one restart, then fail subsequent requests for 30s |
| Context overflow | 400 with "exceeds context" or our own pre-flight `/tokenize` check | Apply session truncation strategy; retry once |
| HTTP 5xx | Status code | Emit `error` event with server body; do not retry |
| Client disconnect | `BodyHandler` throws / SSE sink write fails | Cancel outbound HTTP request; stop tool loop; save partial assistant message to session |
| Malformed SSE frame | JSON parse failure | Skip frame, log WARN, continue |
| Runaway tool loop | Iterations > max (default 8) | Stop loop, emit `error` event "tool loop exceeded" |

---

### D4 — Gateway HTTP contract (for completeness)

#### `POST /api/chat`

Request:
```json
{
  "sessionId": "uuid-optional-creates-new-if-absent",
  "agentId": "optional-defaults-to-default-agent",
  "input": "user message text"
}
```

Response: `text/event-stream`. Event types:

| `event` | `data` payload | Emitted when |
|---|---|---|
| `session` | `{"sessionId":"..."}` | First event; confirms (or creates) session |
| `llm_start` | `{"turn":N}` | Before each LLM call in the loop |
| `token` | `{"text":"..."}` | Each `delta.content` from llama-server |
| `tool_call` | `{"id":"call_1","name":"...","args":{...}}` | Parsed from `delta.tool_calls` |
| `tool_result` | `{"id":"call_1","result":"...","error":null}` | After tool executes |
| `final` | `{"text":"full final assistant message"}` | When `finish_reason=stop` |
| `error` | `{"code":"...","message":"..."}` | Any failure; stream then closes |

Stream closes after `final` or `error`.

#### `GET /api/sessions/{id}/history`

Returns ordered `SessionMessage[]` for rehydration / UI display.

#### `GET /api/agents`, `GET /api/agents/{id}`

List and inspect registered agents.

#### `GET /api/health`

`{"status":"ok","llamaServer":"ready"}` — gateway + child process status.

---

### Component responsibilities — summary table

| Component | Owns | Talks to |
|---|---|---|
| `LlamaServerProcess` | child process lifecycle, stdout/stderr forwarding, readiness probe | OS (ProcessBuilder), `LlamaServerClient` (for `/health`) |
| `LlamaServerClient` | outbound HTTP to llama-server, SSE parsing | llama-server |
| `AgentRunner` | per-turn orchestration, tool-call loop, event emission | SessionStore, ToolRegistry, AgentManager, LlamaServerClient |
| `SessionStore` | LRU cache + SQLite persistence for `Session` and `SessionMessage` | DaoSqlite |
| `AgentManager` | LFU cache + DAO for `AgentEntity`; default-agent registry | DaoSqlite |
| `ToolRegistry` | unified view of native Java tools + MCP-sourced tools; export OpenAI schemas; execute by name | MCP client, native tool impls |
| `RouteChat` | HTTP/SSE surface; translates `AgentEvent` to SSE frames | AgentRunner |
| `ApiServer` | Javalin bootstrap, route registration, port | RouteChat, RouteRoot |
| `EdgeAiGateway` | top-level lifecycle; wires all singletons | all of the above |

### Sources (for reference only, not part of the final output)

- task_001 Output — research on kherud/java-llama.cpp and rk-llama.cpp, ABI analysis, integration options
- `docs/specs/project-specs.md` (Apr-24 note on fallback to sidecar)
- `docs/technical/gemma4-chat-template.md`, `docs/technical/rk3588-deploy-note.md`
- `applicationbase.jar` / `desktopplatform.jar` contents: `Cache`, `AbstractCache`, `EvictionListener`, `DaoTable`, `DaoColumn`, `Dao`, `Migration`, `Service`, `LFUCache`, `LRUCache`, `DaoSqlite`
- llama.cpp server docs: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
- MCP Java SDK: `io.modelcontextprotocol.sdk:mcp-core:1.1.1`