# Reverse engineering Langchain4j and implement agents (agents swarm) for the project.

## Note

## Task Description
1. Research and reverse engineer Langchain4j's agent framework, focusing on its architecture, components, and how it manages agent interactions and reasoning.
2. Layering out core components and interfaces of the agent framework to be implemented, including but not limited to:
   - Agent class and its lifecycle management
   - Memory management for agents (short-term and long-term memory)
   - Reasoning and decision-making processes (e.g., how agents choose actions based on inputs and memory)
   - Communication protocols for multi-agent interactions (e.g., message passing, shared memory, etc.)
   - Tool integration and execution (e.g., how agents can call external tools or APIs as part of their reasoning)
   - MCP integration 
3. Designing architectural patterns and best practices for implementing the agent framework, ensuring modularity, scalability, and maintainability.
4. Providing detailed documentation and examples for each component and interface, demonstrating how to implement and use the agent framework effectively in the context of the project.

## Deliverables

- D1. LangChain4j architecture reverse-engineered — key patterns, interfaces, and the tool-call loop
- D2. Core interfaces and classes for this project's agent framework
- D3. Agent lifecycle and session memory design
- D4. Tool integration and execution design
- D5. Multi-agent (swarm) communication design
- D6. MCP integration design
- D7. Architectural patterns and best practices

## Output (agent response content here)

### D1 — LangChain4j Reverse Engineering

#### Key insight: no `Agent` class

LangChain4j has no explicit `Agent` class. The agent pattern is encoded entirely in `DefaultAiServices` — a JDK dynamic proxy over a user-defined interface. Each proxy method invocation is one agent "turn." State between turns lives only in `ChatMemory`. This is the right pattern to mirror: **agents are invocation contexts over a memory+tools+model triple, not stateful objects**.

#### AiServices proxy pattern

```
AiServices.builder(MyInterface.class)
    .chatModel(model)
    .tools(toolBean1, toolBean2)
    .chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(20).build())
    .build()
```

Internally (`DefaultAiServices.build()`):
- All config stored in `AiServiceContext`
- `Proxy.newProxyInstance(...)` wraps an `InvocationHandler`
- Each call: resolve memory by `@MemoryId` param → build messages → call LLM → enter tool-call loop → return result

We do not use dynamic proxies (no annotation-driven interface scanning needed for our use case). We implement the `AgentRunner` directly.

#### Tool-call loop (core, verbatim from `ToolService`)

```java
List<ChatMessage> messages = buildInitialMessages(system, memory, userInput);
ChatResponse response = model.chat(messages, toolSpecs);

for (int i = 0; i < maxIterations; i++) {
    AiMessage ai = response.aiMessage();
    memory.add(ai);
    messages.add(ai);
    if (!ai.hasToolExecutionRequests()) break;

    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
        ToolExecutor exec = toolExecutors.get(req.name());
        String result = exec.execute(req, memoryId);
        ToolExecutionResultMessage res = ToolExecutionResultMessage.from(req.id(), req.name(), result);
        memory.add(res);
        messages.add(res);
    }
    response = model.chat(messages, toolSpecs);  // re-call LLM
}
return response;
```

Detection of tool calls: `AiMessage.hasToolExecutionRequests()` — no raw `finish_reason` string parsing; the HTTP client layer normalizes it. For our project: inspect the parsed JSON response for `choices[0].finish_reason == "tool_calls"` in `LlamaServerClient`.

Tool dispatch: purely reflective via `Method.invoke(toolBean, args)`. Arguments deserialized from JSON string → Map → coerced to target Java types.

#### Memory scoping

LangChain4j uses a `ChatMemoryProvider` (functional interface: `Object memoryId → ChatMemory`) stored in a `Map<Object, ChatMemory>`. Memory is created lazily on first access per `memoryId`. `SystemMessage` is always retained — never evicted. When evicting, orphaned `ToolExecutionResultMessage`s (whose `ToolExecutionRequest` was evicted) are also dropped to satisfy OpenAI's constraint.

#### Streaming + tool loop

In streaming mode, LangChain4j **buffers the streamed `AiMessage`** (including tool call deltas — they arrive fragmented and must be merged). When the stream ends, if tool calls exist, it executes tools synchronously and re-calls the LLM with a new streaming request. Only the **final** LLM response text is streamed to the consumer. Intermediate tool-execution rounds are invisible to the stream consumer.

For our project: we stream `token` events from the final LLM response only; tool_call and tool_result are emitted as discrete SSE events synchronously before resuming the stream.

#### Multi-agent (LangChain4j `langchain4j-agentic`)

LangChain4j implements agent swarms by **wrapping sub-agents as tools of a supervisor agent**. The supervisor's `@Tool`-annotated adapter methods call into the sub-agent's service interface. Key primitives: `SupervisorAgentService`, `PlannerBasedService`, `SequentialAgentService`, `ParallelAgentService`, `LoopAgentService`, `ConditionalAgentService`. This is the pattern we adapt.

#### MCP integration (`langchain4j-mcp`)

- `McpToolProvider` — calls MCP `tools/list`, converts to `ToolSpecification[]`
- `McpToolExecutor` — calls MCP `tools/call`, returns string result
- Transports: stdio (subprocess), HTTP SSE, streamable HTTP, WebSocket
- All JSON-RPC 2.0 over the wire

Our project already has `mcp-core:1.1.1` on the classpath — we use that SDK directly rather than LangChain4j's own MCP client.

---

### D2 — Core Interfaces

All in package `thingai.edge.aigateway.agent` (new package, not `agents` — avoids collision with existing `AgentEntity`/`AgentManager` stubs).

```java
// Represents one defined agent — its identity, system prompt, tools, sampling config
interface Agent {
    String id();
    String name();
    String systemInstruction();
    List<String> toolKit();       // tool names this agent is permitted to use
    SamplingParams samplingParams();
}

// One message in a conversation
interface ChatMessage {
    Role role();          // SYSTEM, USER, ASSISTANT, TOOL
    String content();
    String id();          // null unless role == TOOL (tool_call_id)
    List<ToolCall> toolCalls();  // non-null when role == ASSISTANT with tool calls
}

// A single tool call requested by the LLM
record ToolCall(String id, String name, String argumentsJson) {}

// A tool call result to be injected back into the message history
record ToolResult(String callId, String name, String result, String error) {}

// The conversation history for one session
interface ChatMemory {
    String sessionId();
    void add(ChatMessage message);
    List<ChatMessage> messages();
    void trimToFit(int maxMessages);       // sliding window
    void clear();
}

// Persistence backend for ChatMemory
interface ChatMemoryStore {
    List<ChatMessage> load(String sessionId);
    void save(String sessionId, List<ChatMessage> messages);
    void delete(String sessionId);
}

// Factory for per-session memory (lazy creation)
@FunctionalInterface
interface ChatMemoryProvider {
    ChatMemory get(String sessionId);
}

// Executes a named tool given JSON arguments, returns a string result
interface ToolExecutor {
    String execute(ToolCall call, String sessionId) throws ToolExecutionException;
}

// A registered tool with its OpenAI-style schema
interface Tool {
    String name();
    String description();
    JsonObject parametersSchema();   // OpenAI tools[].function.parameters JSON
    ToolExecutor executor();
}

// The agent reasoning loop
interface AgentRunner {
    void run(String sessionId, Agent agent, String userInput, AgentEventSink sink);
}

// SSE event sink — AgentRunner pushes events; RouteChat serializes them
interface AgentEventSink {
    void onLlmStart(int turn);
    void onToken(String text);
    void onToolCall(ToolCall call);
    void onToolResult(ToolResult result);
    void onFinal(String fullText);
    void onError(String code, String message);
}
```

---

### D3 — Agent Lifecycle and Session Memory

#### Agent lifecycle

```
1. Client POST /api/chat {sessionId, input}
2. RouteChat → resolves/creates Session in SessionStore
3. RouteChat → resolves Agent from AgentManager (default or requested)
4. RouteChat → opens SSE sink, calls AgentRunner.run(sessionId, agent, input, sink)
5. AgentRunner:
   a. Load ChatMemory for sessionId (LRU cache → SQLite fallback)
   b. memory.add(UserMessage(input))
   c. Build request: [system] + memory.messages() + toolSchemas
   d. POST llama-server /v1/chat/completions (stream=true)
   e. Tool-call loop (see D4)
   f. memory.add(FinalAssistantMessage)
   g. ChatMemoryStore.save(sessionId, messages)
   h. sink.onFinal(...)
6. RouteChat closes SSE stream
```

An agent instance (`AgentEntity`) is **stateless** — it holds only config (system prompt, tool whitelist, sampling params). All runtime state is in `ChatMemory` scoped to `sessionId`.

#### Session memory design — two-tier

**Tier 1 — Hot cache (LRU):**
- `org.thingai.platform.cache.LRUCache<String, ChatMemory>` (already available in `desktopplatform.jar`)
- Bounded by session count (e.g. 32 hot sessions max)
- Eviction via `EvictionListener` → triggers `ChatMemoryStore.save(...)` to SQLite

**Tier 2 — Cold storage (SQLite):**
- `ChatMemoryStore` backed by `org.thingai.platform.dao.DaoSqlite`
- Two tables:

```java
@DaoTable(name = "sessions")
class SessionRecord {
    @DaoColumn(primaryKey = true) String sessionId;
    @DaoColumn() String agentId;
    @DaoColumn() long createdAt;
    @DaoColumn() long updatedAt;
    @DaoColumn() int temperature;
    @DaoColumn() int topK;
    @DaoColumn() int topP;
}

@DaoTable(name = "session_messages")
class SessionMessageRecord {
    @DaoColumn(primaryKey = true) String messageId;   // UUID
    @DaoColumn() String sessionId;
    @DaoColumn() int sequence;
    @DaoColumn() String role;                         // SYSTEM/USER/ASSISTANT/TOOL
    @DaoColumn() String content;
    @DaoColumn() String toolCallsJson;                // null unless ASSISTANT with tool_calls
    @DaoColumn() String toolCallId;                   // null unless TOOL
    @DaoColumn() long createdAt;
}
```

**Context overflow strategy — sliding window:**
- Before each LLM call: count messages excluding the system message.
- If count > `maxMessages` (default 50), drop oldest non-system messages in pairs (user+assistant) to preserve coherence.
- Pre-flight token check via `POST /tokenize` if within 20% of the llama-server `-c` limit.
- System message is never evicted (mirrors LangChain4j invariant).

---

### D4 — Tool Integration and Execution

#### Tool registration

`ToolRegistry` (existing stub in `inference/tools/`) manages a `Map<String, Tool>` of registered tools. Two registration paths:

**1. Native Java tools** — annotated methods on a POJO:

```java
class DateTimeTool {
    @AgentTool(description = "Get the current date and time")
    public String getCurrentTime() {
        return LocalDateTime.now().toString();
    }

    @AgentTool(description = "Get weather for a city")
    public String getWeather(
        @ToolParam(description = "City name") String city
    ) { ... }
}
```

`ToolRegistry.register(Object toolBean)` reflects over all `@AgentTool` methods and calls `ToolSchemaGenerator.fromMethod(method)` to build the OpenAI parameters schema.

**2. MCP tools** — discovered from MCP servers via `mcp-core`:

```java
// McpToolBridge — adapts mcp-core's tool listing into ToolRegistry entries
mcpClient.listTools().forEach(mcpTool -> {
    Tool tool = McpToolAdapter.adapt(mcpTool, mcpClient);
    toolRegistry.register(tool);
});
```

Both are unified behind the `Tool` interface — `AgentRunner` sees one flat map.

#### Tool schema generation

`ToolSchemaGenerator.fromMethod(Method method)` — reflection-based:

1. Read `@AgentTool.description()` → `function.description`
2. Read `method.getName()` or `@AgentTool.name()` → `function.name`
3. For each `Parameter` (skip `@ToolSessionId`-annotated ones):
   - Read `@ToolParam.description()`
   - Map Java type → JSON Schema type: `String→"string"`, `int/Integer→"integer"`, `boolean→"boolean"`, `List<T>→"array"`, `Map→"object"`, `enum→"string"+"enum":[]`
   - Mark required unless `Optional<T>`
4. Output:
```json
{
  "type": "function",
  "function": {
    "name": "getWeather",
    "description": "Get weather for a city",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {"type": "string", "description": "City name"}
      },
      "required": ["city"]
    }
  }
}
```

#### Tool-call loop (adapted for llama-server)

```java
// AgentRunner.run() — core loop
List<Map<String,Object>> messages = buildMessages(agent.systemInstruction(), memory);
List<JsonObject> toolSchemas = toolRegistry.schemasFor(agent.toolKit());

int turn = 0;
sink.onLlmStart(turn);
LlamaResponse response = llamaClient.chat(messages, toolSchemas, agent.samplingParams(), tokenSink);

while (turn++ < MAX_ITERATIONS) {
    if (response.finishReason() != FinishReason.TOOL_CALLS) break;

    for (ToolCall call : response.toolCalls()) {
        sink.onToolCall(call);
        String result;
        try {
            result = toolRegistry.execute(call, sessionId);
        } catch (ToolExecutionException e) {
            result = "ERROR: " + e.getMessage();
        }
        ToolResult tr = new ToolResult(call.id(), call.name(), result, null);
        sink.onToolResult(tr);

        messages.add(assistantMessageWith(response.toolCalls()));
        messages.add(toolResultMessage(tr));
        memory.add(AssistantMessage.withToolCalls(response.toolCalls()));
        memory.add(ToolMessage.from(tr));
    }

    sink.onLlmStart(turn);
    response = llamaClient.chat(messages, toolSchemas, agent.samplingParams(), tokenSink);
}

String finalText = response.content();
memory.add(AssistantMessage.of(finalText));
sink.onFinal(finalText);
```

**Max iterations:** 8 by default, configurable per `AgentEntity`.
**Token sink:** lambda `String token → sink.onToken(token)` — passed into `LlamaServerClient` for streaming.

---

### D5 — Multi-Agent (Swarm) Design

Adapted from LangChain4j's `langchain4j-agentic` supervisor/planner patterns.

#### Core concept: agents as tools

A **supervisor agent** has in its tool whitelist a special tool called `delegateTo`. When the LLM calls it, the runner invokes a named sub-agent synchronously, feeds it the input, and returns the sub-agent's final answer as the tool result. The supervisor then synthesizes a final response.

```
SupervisorAgent
  tools: [delegateTo, searchTool, ...]
  systemInstruction: "You coordinate tasks among specialists..."

  turn 1: LLM → tool_call: delegateTo("researchAgent", "find GDP of Vietnam")
  runner → ResearchAgent.run("find GDP of Vietnam")
  runner → tool_result: "GDP: $430B (2024)"
  turn 2: LLM → final answer: "The GDP of Vietnam is..."
```

#### Swarm topology types

| Type | Description | Implementation |
|---|---|---|
| **Supervisor** | One coordinator, N workers. Coordinator picks which worker to call. | `delegateTo(agentId, input)` tool registered in supervisor's toolkit |
| **Sequential pipeline** | Agent A → Agent B → Agent C. Output of each is input to next. | `AgentPipeline.of(agentA, agentB, agentC).run(input)` |
| **Parallel** | Multiple agents run concurrently, results merged. | `AgentSwarm.parallel(agents).run(input)` using `CompletableFuture` |
| **Router** | Input classified, routed to the appropriate specialist. | Router agent whose only tools are the specialized sub-agents |

#### `AgentSwarm` interface

```java
interface AgentSwarm {
    // Supervisor pattern
    static AgentSwarm supervisor(Agent supervisor, List<Agent> workers) { ... }

    // Sequential pipeline
    static AgentSwarm pipeline(List<Agent> agents) { ... }

    // Parallel fan-out, merge results into supervisor
    static AgentSwarm parallel(Agent merger, List<Agent> workers) { ... }

    void run(String sessionId, String input, AgentEventSink sink);
}
```

#### `delegateTo` tool implementation

```java
class AgentDelegateTool {
    private final AgentManager agentManager;
    private final AgentRunner runner;

    @AgentTool(name = "delegateTo",
               description = "Delegate a subtask to a specialized agent")
    public String delegateTo(
        @ToolParam(description = "Agent ID to delegate to") String agentId,
        @ToolParam(description = "Input for the sub-agent") String input,
        @ToolSessionId String sessionId  // injected, excluded from schema
    ) {
        Agent subAgent = agentManager.get(agentId);
        CollectingEventSink sink = new CollectingEventSink();
        runner.run(sessionId + "_sub_" + agentId, subAgent, input, sink);
        return sink.getFinalText();
    }
}
```

The sub-agent's session ID is derived by appending a suffix — it gets its own isolated `ChatMemory` so the sub-conversation doesn't pollute the supervisor's history.

#### Inter-agent communication

- **Synchronous delegation** (default): supervisor blocks on sub-agent, as above.
- **Shared memory** (opt-in): supervisor and sub-agents share the same `sessionId`, so the full conversation is visible across all. Use only when the supervisor needs the sub-agent's intermediate reasoning in context.
- **Message passing** (future): a lightweight `AgentMessageBus` can be added later for async coordination.

---

### D6 — MCP Integration

Gateway acts as both **MCP client** (consuming external MCP servers' tools) and **MCP server** (exposing its own built-in tools over MCP to other processes).

#### MCP client — tool import

Using `io.modelcontextprotocol.sdk:mcp-core:1.1.1` (already on classpath):

```java
class McpToolBridge {
    private final McpClient mcpClient;
    private final ToolRegistry toolRegistry;

    void importTools() {
        mcpClient.listTools().forEach(mcpTool -> {
            Tool adapted = new Tool() {
                public String name() { return mcpTool.getName(); }
                public String description() { return mcpTool.getDescription(); }
                public JsonObject parametersSchema() {
                    return mcpTool.getInputSchema();  // already OpenAI-compatible JSON Schema
                }
                public ToolExecutor executor() {
                    return (call, sessionId) -> {
                        McpCallToolResult result = mcpClient.callTool(
                            new McpCallToolRequest(call.name(),
                                gson.fromJson(call.argumentsJson(), Map.class))
                        );
                        return result.getContent().stream()
                            .filter(c -> "text".equals(c.getType()))
                            .map(McpContent::getText)
                            .collect(Collectors.joining("\n"));
                    };
                }
            };
            toolRegistry.register(adapted);
        });
    }
}
```

Supports both stdio (subprocess MCP servers) and HTTP SSE transports via `mcp-core`'s built-in transport implementations.

#### MCP server — tool export

`EdgeAiGateway` exposes a subset of its native tools as MCP server endpoints, allowing other local services to call them:

```java
McpServer mcpServer = McpServer.builder()
    .serverInfo("edge-ai-gateway", "0.1.0")
    .tool(toolRegistry.getMcpDefinition("get_time"), args -> ...)
    .tool(toolRegistry.getMcpDefinition("search_files"), args -> ...)
    .build();
mcpServer.start(StdioServerTransport.create());  // or HTTP
```

---

### D7 — Architectural Patterns and Best Practices

#### 1. Agents are stateless config; memory holds all state

Never put conversation state on `AgentEntity`. An `AgentEntity` is loaded once into `AgentManager`'s LFU cache and reused across all sessions. `ChatMemory` (keyed by `sessionId`) is the sole runtime state.

#### 2. Tool registry is flat and unified

`ToolRegistry` holds `Map<String, Tool>` regardless of whether tools came from native Java reflection or MCP discovery. `AgentRunner` does not know or care how a tool is implemented — it calls `ToolExecutor.execute(call, sessionId)` and gets a string back. This mirrors LangChain4j's `ToolExecutor` interface.

#### 3. Agent runner is independent of transport

`AgentRunner` only knows about `AgentEventSink` — a push interface for events. `RouteChat` (the Javalin handler) provides the SSE-backed sink. A test can provide a `CollectingEventSink`. The tool-call loop never touches HTTP.

#### 4. Streaming tokens are a side-effect, not the return value

`LlamaServerClient.chat(...)` accepts a `Consumer<String> tokenConsumer`. Tokens are pushed to the consumer as they arrive from llama-server's SSE stream. The method returns `LlamaResponse` (final state: `finishReason`, accumulated `content`, `toolCalls`). This keeps the tool-call loop synchronous while streaming tokens to the client in real time.

#### 5. Error isolation per tool

Each `ToolExecutor.execute(...)` call is wrapped in try/catch. A tool failure produces a `ToolResult` with `error != null` which is injected into the message history as-is. The LLM then sees the error and decides whether to retry, use a different tool, or inform the user. The runner does not throw on tool failure.

#### 6. `SystemMessage` is always first and never evicted

Mirrors LangChain4j invariant: the agent's `systemInstruction` is injected as `messages[0]` on every request, not stored in `ChatMemory`. This ensures it is always present even after memory compaction. When evicting old messages, always count from index 1.

#### 7. Sub-agent sessions are namespaced

Sub-agent sessions use `parentSessionId + "_" + subAgentId` as their session ID. This gives them isolated `ChatMemory` while making the parent-child relationship traceable in `session_messages` table. Sub-session history can be optionally persisted or discarded after the parent turn completes.

#### 8. Max iterations guard is non-negotiable

Always bound the tool-call loop. Default: 8 iterations. Configurable per agent in `AgentEntity.maxToolIterations`. When the bound is hit, emit `sink.onError("TOOL_LOOP_EXCEEDED", "...")` and return whatever the last LLM response was (which may be empty). Never let the loop run unbounded on an edge device with limited RAM and context window.

#### 9. MCP tools are re-imported on a schedule, not on every request

`McpToolBridge.importTools()` is called once at startup and then on a 60-second background refresh (or on demand). The `ToolRegistry` is updated atomically (swap the map reference). `AgentRunner` holds no reference to the map directly — it reads from `ToolRegistry.schemasFor(...)` at the start of each turn.

#### 10. Modularity boundary

```
api/          ← transport (Javalin, SSE) — knows nothing of LLM or agents
agent/        ← new package: AgentRunner, AgentSwarm, AgentEventSink
inference/    ← LlamaServerProcess, LlamaServerClient (existing, extend)
session/      ← ChatMemory, ChatMemoryStore, SessionStore
tools/        ← ToolRegistry, ToolSchemaGenerator, @AgentTool, McpToolBridge
agents/       ← AgentEntity, AgentManager (existing stubs, fill out)
mcp/          ← McpRegistry (existing stub), McpServer, McpClient setup
```

No class in `api/` imports from `inference/` directly. `RouteChat` calls `AgentRunner`. `AgentRunner` calls `LlamaServerClient`. The dependency direction is strictly top-down.

---

### Sources (for reference only, not part of the final output)

- [langchain4j/langchain4j — `DefaultAiServices.java`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/service/DefaultAiServices.java)
- [langchain4j — `ToolService.java`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/service/tool/ToolService.java)
- [langchain4j — `ToolSpecifications.java`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/ToolSpecifications.java)
- [langchain4j — `MessageWindowChatMemory.java`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/memory/chat/MessageWindowChatMemory.java)
- [langchain4j — `AiServiceTokenStream.java`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/service/AiServiceTokenStream.java)
- [langchain4j-mcp module](https://github.com/langchain4j/langchain4j/tree/main/langchain4j-mcp)
- [langchain4j-agentic module](https://github.com/langchain4j/langchain4j/tree/main/langchain4j-agentic)
- [llama.cpp server docs — tool calling + jinja](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- Project: `applicationbase.jar` / `desktopplatform.jar` class inventory
- Project task files: `task_001.md`, `task_002.md`