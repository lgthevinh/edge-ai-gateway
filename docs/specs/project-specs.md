# Edge AI Gateway — Project Specs

## Overview

AI agents/agentic service running with local LLM inference (on the edge). Exposes REST/WebSocket API for service integration.

## Tech Stack

| Layer | Choice |
|---|---|
| LLM model | Gemma 4 E2B (GGUF, Q4 quantization) |
| Inference backend | `llama-server` (llama.cpp) sidecar, OpenAI-compatible HTTP API |
| Language | Java 17+ |
| Build | Gradle Kotlin DSL — `build.gradle.kts` |
| API server | Javalin 7.1.0, default port 8080 |
| HTTP client | `java.net.http.HttpClient` (JDK built-in) |
| JSON | Gson 2.13.2 via `JsonUtil` (`synchronized` wrapper) |
| Database | SQLite via `DaoSqlite` from `desktopplatform.jar` |
| DAO annotations | `@DaoTable` / `@DaoColumn` from `applicationbase.jar` |
| MCP SDK | `io.modelcontextprotocol.sdk:mcp-core:1.1.1` |
| Logging | `ILog` from `applicationbase.jar` |
| Target OS | Linux (primary: RK3588 ARM64; secondary: x86_64) |

**JNI note:** `de.kherud:llama` is unusable with `rk-llama.cpp` (ABI mismatch → SIGSEGV). Fallback: spawn `llama-server` as child process, wrap its HTTP API.

## Project Structure

```
edge-ai-gateway/
├── .env                            # LLAMA_SERVER_URL=http://localhost:8080
├── build.gradle.kts
├── libs/
│   ├── applicationbase.jar         # ILog, Dao, @DaoTable/@DaoColumn, Service, ArrayUtils
│   └── desktopplatform.jar         # DaoSqlite
└── src/main/java/thingai/edge/aigateway/
    ├── Main.java                   # loads .env, wires EdgeAiGateway + ApiServer
    ├── EdgeAiGateway.java          # extends Service, inits DAO, holds llamaServerUrl
    ├── agent/
    │   ├── Agent.java              # concrete class with builder, run(), runAsync(), tool call loop
    │   ├── AgentOrchestrator.java  # pipeline: chains agents, owns session persistence
    │   ├── IAgentTool.java         # tool definition + execution interface
    │   ├── mcp/McpRegistry.java    # stub
    │   └── preset/AssistantAgent.java  # factory: AssistantAgent.create(provider) → Agent
    ├── api/
    │   ├── ApiServer.java          # ApiServer(String llamaServerUrl, AgentOrchestrator orchestrator)
    │   └── routes/
    │       ├── RouteRoot.java      # GET /api, GET /api/health
    │       ├── RouteChat.java      # POST /api/chat (proxy), GET /api/chat/history (stub)
    │       └── RouteAgent.java     # POST /api/agent/chat, SSE /api/agent/chat/stream
    ├── llm/
    │   ├── LlmProvider.java        # abstract class: baseUrl field, 3 abstract methods
    │   ├── LlamaCppProvider.java   # extends LlmProvider
    │   ├── content/
    │   │   ├── Content.java        # messages[], temperature, tools[], toolChoice
    │   │   ├── Tool.java           # wire: {type:"function", function:ToolFunction}
    │   │   └── ToolFunction.java   # wire: {name, description, JsonObject parameters}
    │   ├── message/
    │   │   ├── Message.java        # role, content, ToolCall[] toolCalls, String toolCallId
    │   │   ├── MessageRole.java    # SYSTEM/USER/MODEL constants
    │   │   ├── ToolCall.java       # id, type, ToolCallFunction function
    │   │   └── ToolCallFunction.java  # name, arguments (JSON string)
    │   └── response/
    │       ├── Response.java       # choices[], usage; getMessageContent()
    │       ├── ResponseChoice.java # message, finishReason
    │       ├── ResponseUsage.java  # promptTokens, completionTokens, totalTokens
    │       └── ResponseStreamCallback.java  # onToken, onComplete, onError
    ├── session/
    │   ├── Session.java            # @DaoTable("sessions")
    │   └── SessionMessage.java     # @DaoTable("session_messages")
    └── utils/JsonUtil.java
src/main/resources/public/
    ├── index.html              # dark-theme chat UI
    ├── style.css               # navy dark theme (--bg: #0f1117, --accent: #5b6af0)
    └── app.js                  # EventSource SSE client, marked.js markdown rendering
```

## Key Contracts

### `LlmProvider` (abstract)
```java
protected final String baseUrl;
protected LlmProvider(String baseUrl)
public abstract Response chatCompletion(Content content);
public abstract CompletableFuture<Response> chatCompletionAsync(Content content, ResponseStreamCallback callback);
public abstract boolean healthCheck();
```

### `LlamaCppProvider(String baseUrl, String apiKey, String baseModel)`
- SSE streaming via `BodyHandlers.ofLines()`, explicit `CompletableFuture<Response>` promise
- `healthCheck()`: GET `/health`, checks `{"status":"ok"}`

### `Agent` (concrete class)
```java
// Fields set via Builder: name, systemInstruction, llmProvider, tools, temperature
public String run(Message[] history, String userInput);                              // blocking, handles tool call loop
public CompletableFuture<String> runAsync(Message[] history, String userInput, ResponseStreamCallback callback); // streaming
```
- Pure execution — no Dao, no persistence
- Builder: `new Agent.Builder().name(...).llmProvider(...).build()`
- Presets are static factories: `AssistantAgent.create(provider)` returns a configured `Agent`

### `AgentOrchestrator(Dao dao, Agent... agents)`
- Chains agents in a pipeline: first agent gets session history + user input, subsequent agents get previous agent's output
- All agents stream tokens through the shared callback
- Owns session persistence: `loadHistory()`, `persistMessages()`
- `run(sessionId, userInput)` → `String` (blocking)
- `runAsync(sessionId, userInput, callback)` → `CompletableFuture<String>` (streaming)

### `IAgentTool`
```java
String getName();
String getDescription();
String getParametersJson();        // OpenAI JSON schema string
String execute(String paramsJson); // JSON in, JSON out
default Tool toTool() { ... }      // parses schema, builds Tool wire object
```

### `Content(Message[] history, String input, double temperature)`
- Appends user message at construction; `getMessages()` is plain getter
- `setTools(Tool[])` auto-sets `toolChoice = "auto"`

### `RouteChat(String llamaServerUrl)`
- `POST /api/chat`: transparent proxy to `llamaServerUrl/v1/chat/completions`
- Detects `"stream":true` → pipes `InputStream` with `Content-Type: text/event-stream`
- Non-streaming → returns full response with upstream status code

### `RouteAgent(AgentOrchestrator orchestrator)`
- `POST /api/agent/chat`: blocking — `orchestrator.run(sessionId, message)`, returns `{"reply":"..."}`
- `SSE /api/agent/chat/stream`: Javalin native SSE via `config.routes.sse()` + `SseClient.sendEvent()`
  - Client sends params as `?body=` query param (JSON-encoded `{session_id, message}`)
  - Server emits named events: `token` (`{"token":"..."}`) and `done` (`{}`)
  - Frontend uses `EventSource` with `addEventListener("token", ...)` / `addEventListener("done", ...)`
- Thin HTTP layer — persistence handled by orchestrator

## Persistent Entities

### `Session` — `sessions`
`session_id` (PK), `agent_id`, `created_at`, `updated_at`, `temperature`, `top_p`, `top_k`

### `SessionMessage` — `session_messages`
`message_id` (PK), `session_id`, `sequence`, `role`, `content`, `tool_calls_json`, `tool_call_id`, `created_at`

### DAO pattern
```java
Dao dao = new DaoSqlite(getAppDir() + "/data.db");
dao.initDao(new Class[]{ Session.class, SessionMessage.class });
dao.insertOrUpdate(entity);
dao.query(SessionMessage.class, "session_id = ?", sessionId);
```

## Tool Use Flow (OpenAI protocol)
1. Send `messages` + `tools` → model returns `role:assistant` with `tool_calls`
2. Execute locally via `IAgentTool.execute(arguments)`
3. Append assistant message + `role:tool` message (with `tool_call_id` + result)
4. Send updated messages → model continues to `finish_reason: stop`

## Config (.env)
Loaded in `Main` with plain Java file reading, falls back to defaults if missing.
```
LLAMA_SERVER_URL=http://localhost:8080
```

### Web UI (`src/main/resources/public/`)
- Dark-theme chat interface served as static files via Javalin `config.staticFiles`
- SSE streaming via `EventSource` → `/api/agent/chat/stream?body=...`
- `marked.js` (CDN) for markdown rendering after stream completes
- Session management: UUID input, new session button

## Not Yet Implemented
- `agent/mcp/McpRegistry` — MCP client
- Session memory / context management
- Chat history API endpoint

## AI Guidance
See `docs/specs/ai-guidances.md`. Key: only research/implement when explicitly asked; ask for clarification before acting; only use plan mode when asked.
