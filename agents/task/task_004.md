# Draft research and design for ai agents

## Note

## Task Description
Research and design architecture for ai agents, how ai agents will gonna work with this project. Research about AI agent preset, memory, agents orchestrations, and tool, mcps

## Requirements
- The research should cover the topic above and how it work (the concept, the architecture, and the implementation)
- The research should be in the form of a document that can be shared with the team
- The research should be based on the latest research and development in the field of ai agents

## Deliverables

- D1. AI Agent Presets — concept, structure, and storage
- D2. Agent Memory — types, strategies, and implementation
- D3. Agent Orchestration — patterns and coordination models
- D4. Tool Use — function calling, schema, execution loop
- D5. MCP (Model Context Protocol) — architecture, transport, client/server roles

## Output (agent response content here)

---

### D1 — AI Agent Presets

#### Concept

An **agent preset** is a static configuration bundle that defines the identity and behavior of an agent. It is not a running object — it is a declaration. At runtime, the preset is loaded and combined with live session state (memory, user input) to produce an agent invocation.

Presets serve two purposes:
1. **Persona** — who the agent is: name, role, system instruction
2. **Capability boundary** — what the agent can do: which tools it can call, what model parameters it uses

#### Structure

A well-designed agent preset contains:

| Field | Type | Purpose |
|---|---|---|
| `id` | String | Unique identifier for routing and delegation |
| `name` | String | Human-readable label |
| `systemInstruction` | String | System prompt injected as the first message on every turn |
| `toolKit` | List<String> | Whitelist of tool names this agent is allowed to call |
| `maxToolIterations` | int | Upper bound on the tool-call loop per turn (default: 8) |
| `temperature` | double | Sampling temperature (creativity vs. determinism) |
| `topP` | double | Nucleus sampling threshold |
| `topK` | int | Top-K sampling limit |
| `maxContextMessages` | int | Sliding window size for in-context history |

#### System instruction design

The system instruction is the most impactful part of a preset. It defines:
- **Role**: "You are a data analysis assistant specialized in time-series data."
- **Scope**: "Only answer questions related to the user's uploaded datasets."
- **Output format**: "Always respond in structured JSON unless asked otherwise."
- **Tool usage guidance**: "Prefer the `query_database` tool over reasoning from memory when data is requested."

A good system instruction is precise and constraining. Vague instructions like "be helpful" produce unpredictable behavior at inference time. Specific instructions produce consistent, trustworthy agents.

#### Storage and loading

Presets are loaded once at startup into an in-memory registry (e.g., `AgentManager`) backed by an LFU cache. Sources:

1. **File-based** (YAML/JSON in `config/agents/`): simple, version-controllable, hot-reloadable via file-watch
2. **Database-backed** (SQLite `agents` table): supports runtime creation/update via API
3. **Hard-coded defaults** (Java classes implementing `Agent`): always available, not editable at runtime

For this project: file-based presets for built-in agents, database for user-defined agents. `AgentManager` merges both sources at startup.

#### Relation to sessions

A preset is **assigned to a session at session creation**. Once assigned, it does not change for the lifetime of the session. The session's memory accumulates under that preset's constraints (tool whitelist, context window). Swapping the preset mid-session requires clearing memory to avoid tool/history inconsistency.

---

### D2 — Agent Memory

#### Concept

Memory is how an agent maintains continuity across turns. Without memory, every turn is independent — the agent cannot refer to prior context. There are three fundamentally different types of memory in agent systems, each serving a different purpose.

#### Memory types

**1. In-context memory (short-term)**

The message array passed to the LLM on each call. This is what the model literally reads. It is bounded by the context window (`-c` in llama-server). Everything in context is immediately accessible to the model with no retrieval step.

Structure:
```
[system] → [user] → [assistant] → [tool] → [assistant] → [user] → ...
```

Trade-off: infinite in-context memory is impossible. Every model has a hard token limit. On edge hardware (RK3588, 8GB RAM), the effective limit is much lower than the model's theoretical maximum due to KV cache memory pressure.

**2. External memory (long-term)**

Stored outside the context window — in a database, vector store, or file. Retrieved selectively before each LLM call and injected into context. Two retrieval strategies:

- **Sliding window**: keep the N most recent messages. Simple, predictable. Loses distant context but maintains recent coherence. Best for conversation-style agents.
- **Summarization**: periodically compress older messages into a summary message. Preserves semantic gist at the cost of exact detail. Requires an LLM call to summarize (extra latency/cost on edge).
- **RAG (Retrieval-Augmented Generation)**: embed messages/documents into a vector space, retrieve the top-K most semantically relevant chunks for each new user input. Best for knowledge-base agents. Requires an embedding model — expensive on edge hardware.

For this project: **sliding window** is the right default. RAG and summarization are future extensions.

**3. Semantic/procedural memory (agent knowledge)**

Not conversation history — rather, facts and skills the agent has access to regardless of session:
- **Semantic**: static knowledge injected into the system prompt ("The current date is X", "The user's timezone is Y")
- **Procedural**: tool definitions themselves — the agent "knows how" to call a tool because its schema is in the context

This type of memory is managed at the preset level, not the session level.

#### Two-tier memory architecture for this project

```
Hot tier (LRU cache, in-process)
  └── ChatMemory per sessionId
        └── List<ChatMessage> (recent N messages in RAM)

Cold tier (SQLite, persistent)
  └── session_messages table
        └── all historical messages, serialized per session
```

**Write path**: every new message is added to the hot-tier `ChatMemory`. On hot-tier eviction (session not accessed for a while), `EvictionListener` flushes to SQLite.

**Read path**: on session resume, load from SQLite into hot-tier if not already cached.

**Overflow strategy** (sliding window):
1. Before each LLM call, count messages excluding the system message.
2. If count > `maxContextMessages`, drop oldest pairs (user + assistant) from the beginning.
3. Orphaned tool result messages (whose tool call was evicted) are also dropped — the LLM cannot correlate them anyway.
4. System message is never evicted — always injected as `messages[0]`.

#### Message roles

| Role | Purpose | Notes |
|---|---|---|
| `system` | Agent identity and instructions | Always first, never stored in `ChatMemory`, injected per turn |
| `user` | Human or upstream agent input | Stored in memory |
| `assistant` | LLM response | Stored in memory; may contain `tool_calls` |
| `tool` | Tool execution result | Stored in memory; paired with a prior `tool_call_id` |

---

### D3 — Agent Orchestration

#### Concept

Orchestration is coordination between multiple agents. A single agent handles one domain or one task type well. Complex tasks that span multiple domains — research + analysis + formatting, for example — benefit from multiple specialized agents working together under coordination.

The key insight from LangChain4j's `langchain4j-agentic` and similar frameworks: **the most robust orchestration primitive is "agents as tools."** A supervisor agent has a `delegateTo(agentId, input)` tool in its tool kit. When it decides to hand off work, it emits a tool call. The runner executes it synchronously, runs the sub-agent, and injects the result back. This means the supervisor's reasoning loop naturally governs orchestration — no separate orchestration engine is needed.

#### Orchestration patterns

**1. Supervisor / Worker**

```
SupervisorAgent
  ├── tool: delegateTo("researchAgent", ...)
  ├── tool: delegateTo("codeAgent", ...)
  └── tool: delegateTo("summaryAgent", ...)
```

The supervisor decides which worker to call and in what order. Workers are unaware of each other. The supervisor synthesizes a final answer from their results. Best for open-ended tasks where the decomposition strategy is not known in advance.

**2. Sequential Pipeline**

```
InputCleaner → Classifier → SpecialistAgent → Formatter
```

Each agent's output is the next agent's input. No agent sees the others' internal reasoning — only their final output. Best for ETL-style workflows with well-defined stages.

**3. Parallel Fan-Out**

```
                  ┌→ ResearchAgent A ─┐
Input → Router ──├→ ResearchAgent B ─├→ MergerAgent → Final
                  └→ ResearchAgent C ─┘
```

Multiple agents run concurrently (via `CompletableFuture`), results collected and fed to a merger agent. Best for information gathering where subtopics are independent.

**4. Router / Classifier**

```
UserInput → RouterAgent → routes to one of:
  ├── TechSupportAgent
  ├── BillingAgent
  └── GeneralAgent
```

A lightweight classifier agent reads the user's intent and routes to a specialist. The router itself does not answer — it only delegates. Best for multi-domain chatbots where each domain has a specialized agent.

#### Implementation in this project

```java
interface AgentSwarm {
    static AgentSwarm supervisor(Agent supervisor, List<Agent> workers);
    static AgentSwarm pipeline(List<Agent> agents);
    static AgentSwarm parallel(Agent merger, List<Agent> workers);
    void run(String sessionId, String input, AgentEventSink sink);
}
```

Sub-agent sessions are namespaced: `parentSessionId + "_sub_" + subAgentId`. This gives each sub-agent isolated `ChatMemory` while keeping the parent-child relationship traceable in the database.

#### Session isolation rules

| Scenario | Session ID strategy |
|---|---|
| Single agent | `sessionId` as-is |
| Supervisor delegates to worker | `sessionId + "_sub_" + workerId` |
| Sequential pipeline stage N | `sessionId + "_pipe_" + N` |
| Parallel worker | `sessionId + "_par_" + workerId` |

Sub-session histories can be persisted for audit or discarded after the parent turn completes, depending on the use case.

---

### D4 — Tool Use

#### Concept

Tools extend what an agent can do beyond text generation. Without tools, an agent can only reason over its training data and in-context memory. With tools, it can query live data, execute code, call APIs, read files, control systems.

The mechanism: the LLM does not call tools itself. It **requests** a tool call by including a structured `tool_calls` field in its response. The runner interprets this, executes the tool, and injects the result back as a `tool` role message. The LLM then reads the result and continues reasoning.

#### OpenAI tool schema format

Tools are described to the LLM in the request payload:

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Get current weather for a city",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "City name, e.g. Hanoi"
            },
            "unit": {
              "type": "string",
              "enum": ["celsius", "fahrenheit"],
              "description": "Temperature unit"
            }
          },
          "required": ["city"]
        }
      }
    }
  ]
}
```

The LLM responds with `finish_reason: "tool_calls"` and a `tool_calls` array when it wants to invoke a tool:

```json
{
  "choices": [{
    "finish_reason": "tool_calls",
    "message": {
      "role": "assistant",
      "tool_calls": [{
        "id": "call_abc123",
        "type": "function",
        "function": {
          "name": "get_weather",
          "arguments": "{\"city\": \"Hanoi\", \"unit\": \"celsius\"}"
        }
      }]
    }
  }]
}
```

#### Tool execution loop

```
1. LLM responds with finish_reason == "tool_calls"
2. For each tool_call in response:
   a. Look up tool by name in ToolRegistry
   b. Deserialize arguments JSON → Java types
   c. Execute ToolExecutor.execute(call, sessionId)
   d. Inject result as role=tool message: {tool_call_id, content}
3. Re-call LLM with updated message history
4. Repeat until finish_reason != "tool_calls" OR maxIterations reached
```

#### Tool registration — reflection-based

```java
class WeatherTool {
    @AgentTool(description = "Get current weather for a city")
    public String getWeather(
        @ToolParam(description = "City name") String city,
        @ToolParam(description = "Unit: celsius or fahrenheit") String unit
    ) {
        return weatherService.query(city, unit);
    }
}

toolRegistry.register(new WeatherTool());
// → reflects @AgentTool methods, generates schema, registers ToolExecutor
```

`ToolSchemaGenerator.fromMethod(Method)` handles:
- `String` → `"type": "string"`
- `int` / `Integer` → `"type": "integer"`
- `boolean` / `Boolean` → `"type": "boolean"`
- `List<T>` → `"type": "array"`
- `enum` → `"type": "string", "enum": [...]`
- `Optional<T>` → parameter is not in `required` array

Parameters annotated `@ToolSessionId` are injected by the runner at call time and excluded from the schema (the LLM never sees them).

#### Error handling

Each tool call is individually wrapped:
```java
try {
    result = toolRegistry.execute(call, sessionId);
} catch (ToolExecutionException e) {
    result = "ERROR: " + e.getMessage();
}
// inject result regardless — let the LLM decide what to do with the error
```

This is the correct pattern: **never abort the agent loop on tool failure**. The LLM reads the error message in context and can retry with corrected arguments, choose a different tool, or inform the user. Silent failures or exceptions that escape the loop destroy session continuity.

#### Tool call in streaming mode

When `stream: true`, tool call data arrives in SSE deltas and must be **buffered until the stream ends** before execution. The `arguments` field arrives fragmented across multiple chunks. Full assembly:

```
delta 1: {"tool_calls":[{"index":0,"id":"call_x","function":{"name":"get_weather","arguments":""}}]}
delta 2: {"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}
delta 3: {"tool_calls":[{"index":0,"function":{"arguments":"\"Hanoi\"}"}}]}
```

Only after `finish_reason: "tool_calls"` arrives is the arguments string complete. The runner then executes tools, appends results, and issues a new streaming request for the next LLM turn.

---

### D5 — MCP (Model Context Protocol)

#### Concept

MCP is an open protocol (Anthropic, 2024) for connecting AI agents to external tools and data sources in a standardized way. Instead of every agent framework implementing its own tool integration, MCP defines a single wire protocol that any agent can speak and any tool server can implement.

The analogy: MCP is to AI tools what LSP (Language Server Protocol) is to code editors. One protocol, many implementations.

#### Architecture

MCP defines two roles:

**MCP Client** — the agent or agent framework. Connects to one or more MCP servers, discovers tools via `tools/list`, calls them via `tools/call`.

**MCP Server** — a process that exposes tools. Can be a local subprocess, a remote HTTP service, or anything in between. Examples: filesystem access, database query, web search, code execution, IoT device control.

```
Agent (MCP Client)
  │
  ├── MCP Server A (stdio subprocess): filesystem tools
  ├── MCP Server B (HTTP SSE): web search tools
  └── MCP Server C (WebSocket): IoT control tools
```

#### Wire protocol — JSON-RPC 2.0

All MCP communication is JSON-RPC 2.0. Key methods:

**`tools/list`** — discover available tools:
```json
// Request
{"jsonrpc":"2.0","id":1,"method":"tools/list"}

// Response
{"jsonrpc":"2.0","id":1,"result":{
  "tools":[{
    "name":"read_file",
    "description":"Read contents of a file",
    "inputSchema":{
      "type":"object",
      "properties":{"path":{"type":"string"}},
      "required":["path"]
    }
  }]
}}
```

**`tools/call`** — invoke a tool:
```json
// Request
{"jsonrpc":"2.0","id":2,"method":"tools/call",
 "params":{"name":"read_file","arguments":{"path":"/etc/hosts"}}}

// Response
{"jsonrpc":"2.0","id":2,"result":{
  "content":[{"type":"text","text":"127.0.0.1 localhost\n..."}],
  "isError":false
}}
```

#### Transport types

| Transport | Use case | Notes |
|---|---|---|
| **stdio** | Local subprocess MCP servers | Server spawned as child process; stdin/stdout are the channel. Simplest for local tools. |
| **HTTP SSE** | Remote or long-lived MCP servers | Client POSTs requests; server streams responses as SSE. Supports server-push notifications. |
| **Streamable HTTP** | High-throughput remote servers | Bidirectional streaming over a single HTTP connection. |
| **WebSocket** | Real-time / low-latency | Full duplex; suitable for IoT or event-driven tool servers. |

For this project: **stdio** for local tool servers (e.g., a filesystem MCP server spawned alongside the gateway), **HTTP SSE** for remote servers.

#### Gateway as MCP client

```java
// Using mcp-core:1.1.1 (already on classpath)
McpClient mcpClient = McpClient.builder()
    .transport(new StdioTransport("path/to/mcp-server"))
    .build();

List<McpTool> tools = mcpClient.listTools();
tools.forEach(tool -> toolRegistry.register(McpToolAdapter.adapt(tool, mcpClient)));
```

Tools discovered via MCP are registered into `ToolRegistry` identically to native Java tools. `AgentRunner` does not distinguish — it calls `ToolExecutor.execute(call, sessionId)` regardless.

#### Gateway as MCP server

The gateway can expose its own built-in tools to other local processes over MCP:

```java
McpServer mcpServer = McpServer.builder()
    .serverInfo("edge-ai-gateway", "0.1.0")
    .tool(
        new McpToolDefinition("query_agent",
            "Send a query to the AI agent and get a response",
            inputSchema),
        args -> {
            String response = agentRunner.runBlocking(args.get("input").getAsString());
            return McpToolResult.text(response);
        }
    )
    .build();
mcpServer.start(StdioServerTransport.create());
```

This allows other local services (home automation, monitoring daemons) to invoke the AI agent via a standardized MCP interface rather than calling the HTTP API directly.

#### Tool lifecycle in MCP

MCP tools are **not re-fetched on every request**. The correct lifecycle:
1. Fetch tool list at startup via `tools/list`
2. Register into `ToolRegistry`
3. Refresh on a background schedule (e.g., every 60 seconds) or on demand
4. On MCP server disconnect: mark tools as unavailable, do not remove from registry until reconnection confirmed

The `ToolRegistry` map is swapped atomically on refresh — no lock needed if using a `volatile` reference or `ConcurrentHashMap`.

#### MCP vs native tools — decision guide

| Criteria | Use native `@AgentTool` | Use MCP |
|---|---|---|
| Tool is in the same JVM | Yes | No |
| Tool needs to be shared with other processes | No | Yes |
| Tool server already implements MCP | No | Yes |
| Tool requires a specific runtime (Python, Node) | No | Yes |
| Rapid development / testing | Yes | No |

For this project: native tools for core built-ins (datetime, device info, local file access); MCP for integrations with external services or other local daemons.

---

### Sources (for reference only, not part of the final output)

- Anthropic MCP specification: https://modelcontextprotocol.io/specification
- MCP SDK: `io.modelcontextprotocol.sdk:mcp-core:1.1.1`
- LangChain4j agentic module: https://github.com/langchain4j/langchain4j/tree/main/langchain4j-agentic
- OpenAI function calling reference: https://platform.openai.com/docs/guides/function-calling
- llama.cpp server tool calling docs: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
- Project task_002.md (architecture), task_003.md (LangChain4j reverse engineering + agent framework design)
