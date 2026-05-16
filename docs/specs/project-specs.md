# Edge AI Gateway - Project Specs

## Overview

Edge AI Gateway is a Java 17+ local AI agent service with an Angular client. It runs against an OpenAI-compatible `llama-server` sidecar, persists chat sessions in SQLite, exposes built-in and MCP tools to the agent, and separates long-term Knowledge from dynamic RAG chunks.

The current architecture optimizes for edge inference:

- Keep the `Agent` execution-only.
- Keep persistence, history reconstruction, and tool-loop orchestration in `AgentOrchestrator`.
- Append long-lived Knowledge documents to the system instruction so llama.cpp can reuse KV cache for stable prefixes.
- Use RAG chunks only for short, dynamic semantic-search content.
- Persist full chat history, including assistant tool calls and tool result messages, in DAO-backed tables.

## RK llama.cpp Runtime

Enable performance governors before starting inference on RK3588:

```bash
echo performance | sudo tee /sys/bus/cpu/devices/cpu[0-7]/cpufreq/scaling_governor
echo performance | sudo tee /sys/class/devfreq/fb000000.gpu/governor
echo performance | sudo tee /sys/devices/platform/dmc/devfreq/dmc/governor
echo performance | sudo tee /sys/class/devfreq/fdab0000.npu/governor
```

## Tech Stack

| Layer | Choice |
|---|---|
| LLM model | Gemma-family GGUF model served by llama.cpp |
| Inference backend | `llama-server`, OpenAI-compatible HTTP API |
| Embedding backend | OpenAI-compatible embedding provider |
| Language | Java 17+ |
| Build | Gradle Kotlin DSL |
| API server | Javalin 7.x, default port `8080` |
| Client | Angular standalone components, strict TypeScript, signals |
| HTTP client | `java.net.http.HttpClient` |
| JSON | Gson through `JsonUtil` |
| Database | SQLite through `DaoSqlite` |
| Vector search | SQLite vector extension through DAO vector helpers |
| DAO annotations | `@DaoTable` / `@DaoColumn` |
| MCP SDK | `io.modelcontextprotocol.sdk` |
| Logging | `ILog` |
| Target OS | Linux, primary RK3588 ARM64; x86_64 supported for development |

JNI llama bindings are intentionally not used. `LlamaCppProvider` wraps the HTTP API from `llama-server`; do not add JNI llama bindings.

## Project Structure

```text
edge-ai-gateway/
├── client/                         # Angular UI
│   └── src/app/
│       ├── services/               # chat, documents/RAG, session store
│       └── ui/chat-shell/          # main chat interface
├── docs/
│   ├── specs/                      # project specs and AI guidance
│   └── technical/                  # model/template/deployment notes
├── libs/                           # local ThingAI platform jars
├── mcp-servers.json                # local MCP server config
└── src/main/java/thingai/edge/aigateway/
    ├── Main.java                   # loads env and starts service/API
    ├── EdgeAiGateway.java          # service composition and singleton handlers
    ├── agent/
    │   ├── Agent.java              # execution-only LLM + tool unit
    │   ├── AgentOrchestrator.java  # session history, persistence, tool loop
    │   ├── IAgentTool.java
    │   ├── knowledge/              # dynamic system-instruction provider
    │   ├── mcp/                    # MCP registry, connection, tool adapter
    │   └── tools/                  # built-in tools, including RAG search
    ├── api/
    │   ├── ApiServer.java
    │   └── routes/
    │       ├── RouteAgent.java     # agent chat, stream, history, sessions
    │       ├── RouteChat.java      # raw chat proxy
    │       ├── RouteKnowledge.java # Knowledge CRUD
    │       ├── RouteRag.java       # RAG chunk CRUD/search
    │       └── RouteRoot.java
    ├── handler/
    │   ├── knowledge/              # Knowledge documents, no embedding
    │   ├── rag/                    # RAG chunks and vector search
    │   └── session/                # persisted sessions/messages
    ├── llm/                        # provider/content/message/response models
    └── utils/
```

The Angular build is copied into `src/main/resources/public/` by Gradle and served by Javalin as static assets.

## Core Architecture

### `Agent`

`Agent` is execution-only:

- Owns one LLM call unit and its registered tools.
- Builds messages from system instruction, history, and user input.
- Executes tools through `IAgentTool`.
- Does not know about DAO, sessions, routes, or persistence.

This boundary is important. Do not add persistence or route-level concerns to `Agent`.

### `AgentOrchestrator`

`AgentOrchestrator` owns chat execution across turns:

- Loads session history from `session_messages`.
- Converts stored rows back into OpenAI-style `Message[]`, preserving `tool_calls` and `tool_call_id`.
- Runs the agent tool-call loop until stop or `maxTurns`.
- Persists the full new message chain:
  - user message
  - assistant tool-call messages
  - tool result messages
  - final assistant message
- Manages `sessions` records.
- Exposes history/session APIs to routes through simple methods.

`RouteAgent` must stay thin and delegate these responsibilities to `AgentOrchestrator`.

### LLM Provider

`LlamaCppProvider` wraps the OpenAI-compatible llama.cpp HTTP API:

- `chatCompletion(Content)` for blocking calls.
- `chatCompletionAsync(Content, ResponseStreamCallback)` for streaming.
- Parses streaming SSE chunks and emits token callbacks.
- Sends usage data through `ResponseUsage` when the server provides it.
- Logs upstream non-2xx response bodies and completes the stream promise exceptionally.

The provider is HTTP-only. Do not add native llama bindings.

## Knowledge vs RAG

Knowledge and RAG are separate systems with different purposes.

### Knowledge

Knowledge is long-term, stable user knowledge.

- Stored in `knowledge_documents`.
- CRUD through `RouteKnowledge`.
- Managed by `KnowledgeHandler`.
- No embeddings and no semantic search.
- Ordered oldest-to-newest when appended to the agent system instruction.
- Included through `KnowledgeSystemInstructionProvider`.

Purpose: stable context that should become part of the model prompt prefix. This lets llama.cpp reuse KV cache for the repeated system-instruction prefix, reducing repeated prompt processing when the Knowledge base is stable.

Design rules:

- Keep Knowledge documents relatively stable.
- Use Knowledge for manuals, specs, durable facts, and long-lived project notes.
- Do not use Knowledge for rapidly changing small snippets.
- Do not perform vector search from `KnowledgeHandler`.

### RAG Chunks

RAG chunks are short, dynamic searchable content.

- Stored in `rag_chunks`.
- CRUD and search through `RouteRag`.
- Managed by `RagHandler`.
- Embedded and indexed in SQLite vector search.
- Creation validates content at a maximum of 200 words.
- Search returns multiple chunks and includes vector distance.
- Exposed to the agent through `search_documents`.

Purpose: short dynamic content lookup. RAG chunks are not appended to the system instruction; they are retrieved by tool call when relevant.

Design rules:

- Use RAG for changing snippets, short notes, facts, and content chunks.
- Keep chunks no larger than 200 words.
- Use semantic search to retrieve relevant chunks at runtime.
- Do not rely on RAG for stable prompt-prefix cache behavior.

## KV Cache Technique

llama.cpp can reuse prompt-prefix KV cache when the beginning of the prompt remains stable. The gateway uses this by placing long-term Knowledge after the base system instruction and before conversation turns.

Expected prompt shape:

```text
system:
  base assistant instruction
  tool behavior guidance
  long-term Knowledge documents, oldest to newest

history:
  prior user/assistant/tool messages

user:
  latest request
```

Implications:

- Stable Knowledge improves prefix reuse.
- Reordering Knowledge documents hurts cache reuse.
- Frequently editing Knowledge invalidates the stable prefix.
- RAG chunks should not be appended globally because dynamic retrieved content would change the prefix often.
- Tool descriptions also affect prompt size and prefix stability; keep tool descriptions useful but compact.

## API Contracts

### Agent Chat

`POST /api/agent/chat`

Blocking chat endpoint:

```json
{
  "session_id": "uuid",
  "message": "hello"
}
```

Response:

```json
{
  "reply": "..."
}
```

### Streaming Chat

`POST /api/agent/chat/stream/start`

Starts a stream request:

```json
{
  "session_id": "uuid",
  "message": "hello"
}
```

Response:

```json
{
  "stream_id": "uuid"
}
```

`GET /api/agent/chat/stream?stream_id=...`

SSE events:

- `turn`: tool-call turn metadata.
- `token`: streamed assistant token.
- `final`: final response text and usage.
- `done`: stream complete.

The UI starts with POST, then opens `EventSource` with the returned `stream_id`. This avoids placing large chat bodies in the SSE query string.

### Chat History

`GET /api/agent/chat/history?session_id=...`

Returns DAO-backed persisted messages, including tool-call fields:

```json
{
  "session_id": "uuid",
  "messages": [
    {
      "message_id": "uuid",
      "session_id": "uuid",
      "sequence": 0,
      "role": "assistant",
      "content": "",
      "tool_calls": [],
      "tool_call_id": "call_id",
      "created_at": 1778860000000
    }
  ]
}
```

`GET /api/agent/chat/sessions`

Lists DAO-backed sessions for UI hydration.

`DELETE /api/agent/chat/sessions/{sessionId}`

Deletes both the session row and its `session_messages`.

### Knowledge

`GET /api/documents`

List Knowledge documents.

`GET /api/documents/{title}`

Read one Knowledge document.

`POST /api/documents`

Create or update:

```json
{
  "title": "Project guide",
  "description": "Stable project facts",
  "content": "..."
}
```

`DELETE /api/documents/{title}`

Delete one Knowledge document.

### RAG

`GET /api/rag/chunks`

List RAG chunks.

`GET /api/rag/chunks/{chunkId}`

Read one RAG chunk.

`POST /api/rag/chunks`

Create or update:

```json
{
  "chunk_id": "optional-existing-id",
  "title": "Short fact",
  "source": "optional",
  "content": "200 words max"
}
```

`POST /api/rag/chunks/search`

Semantic search:

```json
{
  "query": "natural language query",
  "top_k": 5
}
```

Response includes multiple chunks and `distance`.

`DELETE /api/rag/chunks/{chunkId}`

Delete one RAG chunk.

## Persistent Entities

### `Session` - `sessions`

- `session_id` primary key
- `agent_id`
- `created_at`
- `updated_at`
- `temperature`
- `top_p`
- `top_k`

### `SessionMessage` - `session_messages`

- `message_id` primary key
- `session_id`
- `sequence`
- `role`
- `content`
- `tool_calls_json`
- `tool_call_id`
- `created_at`

History queries must use the DAO column-query form:

```java
dao.query(SessionMessage.class, "session_id", sessionId);
```

Do not use SQL-placeholder strings such as `"session_id = ?"` with this DAO.

### `KnowledgeDocument` - `knowledge_documents`

- `title` primary key
- `description`
- `content`
- `created_at`
- `updated_at`

No embedding column belongs here.

### `RagChunk` - `rag_chunks`

- `chunk_id` primary key
- `title`
- `source`
- `content`
- `embedding`
- `created_at`
- `updated_at`

RAG vector search is initialized against `RagChunk.embedding`.

## Tool Use Flow

The agent follows the OpenAI tool-call protocol:

1. Send `messages` and `tools`.
2. Model returns an assistant message with `tool_calls`.
3. Gateway executes each tool locally or through MCP.
4. Gateway appends:
   - the assistant tool-call message
   - one `role:tool` result message per call
5. Gateway sends the updated history back to the LLM.
6. Repeat until `finish_reason` is `stop`.
7. Persist all new messages to DAO.

Tool-call persistence is required so future turns can reconstruct valid tool-call history. The UI should not construct request history from `localStorage`.

## MCP

MCP servers are loaded from `mcp-servers.json`.

Current principles:

- MCP tool discovery happens at service initialization.
- MCP tools are adapted into `IAgentTool`.
- HTTP MCP and LLM HTTP behavior should be isolated enough that MCP failures do not block LLM chat.
- Tool names exposed to the LLM may need sanitization or prefixing when upstream names collide with provider restrictions.
- Tool descriptions should be concise; large descriptions increase prompt size and can slow local inference.

## Angular Client

The Angular app is the primary UI and is built into backend static resources.

Current UI behavior:

- Sidebar manages session id, session switching, session creation, and deletion.
- Chat messages are loaded from DAO through `/api/agent/chat/history`.
- The UI does not send full history in chat requests.
- Session list hydrates from `/api/agent/chat/sessions`.
- Session usage counters are persisted per session in `localStorage` for display.
- Context length display is separate from session usage and is based on the latest chat response `usage.totalTokens`.
- Knowledge dialog supports CRUD.
- RAG dialog supports CRUD and semantic search.
- RAG creation validates max 200 words in the UI and backend.

LocalStorage is only UI state/cache:

- active session id
- session list cache
- per-session usage display
- per-session latest context length display

DAO is the source of truth for chat messages and sessions.

## Configuration

`.env` is loaded by `Main`.

Common settings:

```text
LLAMA_SERVER_URL=http://localhost:8080
LLAMA_SERVER_API_KEY=
LLAMA_SERVER_MODEL=
EMBEDDING_SERVER_URL=
EMBEDDING_SERVER_API_KEY=
EMBEDDING_MODEL=text-embedding-v4
```

Do not commit tokens, secrets, or machine-specific paths.

## Engineering Boundaries

- `Agent` stays execution-only.
- `AgentOrchestrator` owns session history, persistence, and multi-turn tool chaining.
- Route classes stay thin.
- `LlamaCppProvider` wraps the HTTP API only.
- `KnowledgeHandler` owns Knowledge CRUD and system-context generation support only.
- `RagHandler` owns RAG CRUD, embedding, and semantic search.
- UI chat history comes from DAO, not from `localStorage`.
- Build/test backend changes with `./gradlew test`.
- Build/test client changes through Gradle or `cd client && npm run build`.

## Known Constraints

- Context length displayed in the UI depends on latest provider usage. If the provider omits usage, the display may remain at the last known value.
- Persisted `session_messages` do not currently store token usage; usage display is cached in browser storage.
- Knowledge changes can invalidate llama.cpp prompt-prefix KV cache.
- RAG chunks are intentionally capped to keep embedding/search behavior tight and predictable.
