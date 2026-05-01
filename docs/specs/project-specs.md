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
    │   ├── IAgent.java             # preset agent interface
    │   ├── IAgentTool.java         # tool definition + execution interface
    │   ├── mcp/McpRegistry.java    # stub
    │   └── preset/AssistantAgent.java  # stub
    ├── api/
    │   ├── ApiServer.java          # ApiServer(String llamaServerUrl)
    │   └── routes/
    │       ├── RouteRoot.java      # GET /api, GET /api/health
    │       └── RouteChat.java      # POST /api/chat (proxy), GET /api/chat/history (stub)
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

### `IAgent`
```java
String getName();
String getSystemInstruction();
LlmProvider getLlmProvider();   // injected at construction
IAgentTool[] getTools();        // null = no tools
double getTemperature();
```

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

## Not Yet Implemented
- `AgentRunner` — agentic loop (load history → build Content → call LlmProvider → handle tool calls → persist)
- `agent/mcp/McpRegistry` — MCP client
- `agent/preset/AssistantAgent` — example preset
- Session memory / context management
- Chat history API endpoint

## AI Guidance
See `docs/specs/ai-guidances.md`. Key: only research/implement when explicitly asked; ask for clarification before acting; only use plan mode when asked.
