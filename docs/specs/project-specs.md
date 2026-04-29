# Edge AI Gateway — Project Specs

## Overview

AI agents/agentic service running with local LLM inference (on the edge) to provide AI automation and assistance through multi-step reasoning. Exposes a REST/WebSocket API for service integration and a web UI for user interaction.

## Tech Stack

| Layer | Choice |
|---|---|
| LLM model | Gemma 4 E2B (GGUF, Q4 quantization) |
| Inference backend | `llama-server` (llama.cpp) as sidecar process, OpenAI-compatible HTTP API |
| Language | Java 17+ |
| Build | Gradle (Kotlin DSL) — `build.gradle.kts` |
| API server | Javalin 7.1.0 (REST + WebSocket), default port 8080 |
| HTTP client | `java.net.http.HttpClient` (JDK built-in) for LLM calls |
| JSON | Gson 2.13.2 via `JsonUtil` wrapper |
| Database | SQLite via `DaoSqlite` (from `desktopplatform.jar`) |
| DAO annotations | `@DaoTable` / `@DaoColumn` from `applicationbase.jar` |
| MCP SDK | `io.modelcontextprotocol.sdk:mcp-core:1.1.1` |
| Logging | `ILog` from `applicationbase.jar` |
| Target OS | Linux (primary: RK3588 ARM64; secondary: x86_64) |

### JNI Note (as of Apr-2024)
The Java JNI binding (`de.kherud:llama`) is **unusable** with `rk-llama.cpp` due to ABI incompatibility — struct layouts have drifted from rk-llama's `rknpu2` branch, causing SIGSEGV. **Fallback:** spawn `llama-server` as a child process and wrap its HTTP API. Revisit JNI later if ABI alignment becomes feasible.

## Project Structure

```
edge-ai-gateway/
├── build.gradle.kts
├── settings.gradle.kts
├── libs/
│   ├── applicationbase.jar    # ILog, Dao interface, @DaoTable/@DaoColumn, Service base class
│   └── desktopplatform.jar    # DaoSqlite implementation (SQLite + HikariCP)
├── docs/
│   ├── specs/
│   │   ├── project-specs.md   # This file
│   │   └── ai-guidances.md    # AI agent behavior guidelines
│   ├── technical/
│   │   ├── gemma4-chat-template.md   # Gemma 4 Jinja chat template
│   │   └── rk3588-deploy-note.md     # RK3588 deployment commands
│   └── draft/
│       └── draft.md           # llama-server API response samples
├── agents/
│   └── task/                  # Task research documents for AI agents
└── src/main/java/thingai/edge/aigateway/
    ├── Main.java
    ├── EdgeAiGateway.java
    ├── agent/
    │   ├── Agent.java
    │   └── AgentRegistry.java
    ├── api/
    │   ├── ApiServer.java
    │   └── routes/
    │       ├── RouteRoot.java
    │       └── RouteChat.java
    ├── callback/
    │   └── RequestCallback.java
    ├── llm/
    │   ├── LlmClient.java
    │   ├── content/
    │   │   ├── Content.java
    │   │   └── SamplerParams.java
    │   ├── message/
    │   │   ├── Message.java
    │   │   └── MessageRole.java
    │   └── response/
    │       ├── Response.java
    │       ├── ResponseChoice.java
    │       ├── ResponseUsage.java
    │       └── ResponseStreamCallback.java
    ├── mcp/
    │   ├── McpRegistry.java
    │   └── McpServer.java      # Persistent entity
    ├── session/
    │   ├── Session.java        # Persistent entity
    │   └── SessionMessage.java # Persistent entity
    └── utils/
        └── JsonUtil.java
```

## Entry Points

- **`Main.java`** — creates `EdgeAiGateway`, calls `service.init()`, then starts `ApiServer`
- **`EdgeAiGateway.java`** — extends `Service` (from `applicationbase.jar`); `onServiceInit()` initializes the SQLite DAO and registers persistent entity classes

## Key Classes

### `EdgeAiGateway` (`thingai.edge.aigateway`)
- Extends `org.thingai.base.Service`
- Service name: `"edge-ai-gateway"`, version `"0.1.0"`, app dir `"edge-ai-gateway"`
- `onServiceInit()`: creates `DaoSqlite` at `getAppDir() + "/data.db"`, calls `dao.initDao(Session.class, SessionMessage.class)`
- `ILog.ENABLE_LOGGING = true`, `ILog.logLevel = ILog.DEBUG`

### `LlmClient` (`thingai.edge.aigateway.llm`)
- Wraps the llama-server OpenAI-compatible HTTP API
- Constructed with `(String baseUrl, String apiKey, String baseModel)`
- Static shared `HttpClient` with 30s connect timeout
- **`chatCompletion(Content)`** → `Response` (blocking)
- **`chatCompletionAsync(Content, ResponseStreamCallback)`** → `CompletableFuture<Response>` (SSE streaming)
  - Uses `BodyHandlers.ofLines()` to process `data: ` SSE lines
  - Accumulates tokens in `StringBuilder`, fires `callback.onToken(token)` per chunk
  - On `[DONE]`: fires `callback.onComplete(fullText)` and completes the future with a constructed `Response`
  - On error: fires `callback.onError(ex)` and completes the future exceptionally
- **`healthCheck()`** → `boolean` — GET `/health`, checks HTTP 200 + `{"status":"ok"}`
- Private `buildRequest(Content, boolean stream)` — assembles the JSON POST body
- Private `buildResponse(String fullText)` — constructs a `Response` from streamed text

### `Content` (`thingai.edge.aigateway.llm.content`)
- Holds `Message[] messages` and `double temperature`
- Constructor `Content(Message[] history, String input, double temperature)` appends the user input to history at construction time; `getMessages()` is a plain getter (no allocation)
- `Message[]` passed to `buildRequest` directly

### `Message` / `MessageRole` (`thingai.edge.aigateway.llm.message`)
- `Message(String role, String content)` — plain POJO
- `MessageRole` constants: `SYSTEM = "system"`, `USER = "user"`, `MODEL = "assistant"`

### `Response` / `ResponseChoice` / `ResponseUsage` (`thingai.edge.aigateway.llm.response`)
- `Response`: holds `ResponseChoice[] choices` + `ResponseUsage usage`; `getMessageContent()` returns `choices[0].getMessage().getContent()` or `null`
- `ResponseChoice`: holds `Message message` + `String finishReason`
- `ResponseUsage`: holds `promptTokens`, `completionTokens`, `totalTokens`
- All use `@SerializedName` for Gson deserialization from llama-server JSON

### `ResponseStreamCallback` (`thingai.edge.aigateway.llm.response`)
```java
public interface ResponseStreamCallback {
    void onToken(String token);       // fired per SSE chunk
    void onComplete(String fullText); // fired when [DONE] received
    void onError(Exception e);        // fired on network/parse error
}
```

### `ApiServer` (`thingai.edge.aigateway.api`)
- Wraps Javalin; default port 8080
- All routes under `/api` path via `config.routes.apiBuilder`
- `start()` / `stop()` lifecycle

### Routes
- `RouteRoot`: `GET /api` → `"Hello, World!"`, `GET /api/health` → `{"status":"ok"}`
- `RouteChat`: `POST /api/chat` → stub `{"reply":"echo"}`, `GET /api/chat/history` → stub `[]`

### `Agent` (`thingai.edge.aigateway.agent`)
- Fields: `name`, `systemInstruction`, `model`, `String[] toolKit`, `int[] samplingParams` (temperature, top_p, top_k — length-3 validated)
- `AgentRegistry` — placeholder, not yet implemented

### `McpRegistry` (`thingai.edge.aigateway.agent.mcp`)
- Placeholder, not yet implemented

### `JsonUtil` (`thingai.edge.aigateway.utils`)
- `static Gson gson` (shared instance, methods are `synchronized`)
- `toJson(Object)` → `String`, `fromJson(String, Class<T>)` → `T`

## DAO Usage Pattern

```java
// Initialize (done in EdgeAiGateway.onServiceInit)
Dao dao = new DaoSqlite(getAppDir() + "/data.db");
dao.initDao(new Class[]{ Session.class, SessionMessage.class });

// Common operations
dao.insertOrUpdate(session);
dao.readAll(Session.class);
dao.deleteByColumn(SessionMessage.class, "session_id", sessionId);
dao.query(SessionMessage.class, "session_id = ?", sessionId);
```

## LLM API (llama-server)

Base URL: configurable (e.g. `http://localhost:8080`)

- `POST /v1/chat/completions` — OpenAI-compatible chat completion
- `GET /health` — returns `{"status":"ok"}` when ready

### Request body
```json
{
  "model": "<model-id>",
  "messages": [{"role": "user", "content": "Hello"}],
  "temperature": 0.7,
  "stream": false
}
```

### Response body (non-streaming)
```json
{
  "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "..."}}],
  "usage": {"prompt_tokens": 10, "completion_tokens": 11, "total_tokens": 21}
}
```

### Streaming (SSE)
Lines of `data: <json>` ending with `data: [DONE]`. Each chunk has `choices[0].delta.content`.

## Gemma 4 Chat Template

Gemma 4 uses a custom Jinja2 template (see `docs/technical/gemma4-chat-template.md`) with tokens:
- Turn boundaries: `<|turn>role\n` ... `<turn|>\n`
- Tool declarations: `<|tool>declaration:name{...}<tool|>`
- Tool calls: `<|tool_call>call:name{args}<tool_call|>`
- Tool responses: `<|tool_response>response:name{...}<tool_response|>`
- Thinking: `<|channel>thought\n...\n<channel|>`

The llama-server applies this template automatically; the Java gateway passes raw `messages` arrays.

## RK3588 Deployment

```bash
ulimit -n 65536
echo performance | tee /sys/devices/system/cpu/cpufreq/policy*/scaling_governor
echo performance | tee /sys/class/devfreq/ff9a0000.gpu/governor
taskset -c 4-7 <llama-server command>   # bind to big cores
```

## Dependencies (build.gradle.kts)

```
libs/applicationbase.jar     # Service, ILog, Dao, @DaoTable/@DaoColumn, ArrayUtils
libs/desktopplatform.jar     # DaoSqlite
org.xerial:sqlite-jdbc:3.43.2.0
com.zaxxer:HikariCP:5.1.0
com.google.code.gson:gson:2.13.2
io.modelcontextprotocol.sdk:mcp-core:1.1.1
io.javalin:javalin:7.1.0
org.slf4j:slf4j-simple:2.0.17
com.squareup.okhttp3:okhttp:5.3.2   # available but not yet used; HttpClient used for LLM calls
```

## AI Guidance

See `docs/specs/ai-guidances.md` for behavior rules. Key points:
- Only research the whole codebase when explicitly asked
- Only implement when explicitly asked; provide suggestions otherwise
- Ask for clarification on ambiguous tasks before reading code or implementing
- Only use plan mode when explicitly asked
