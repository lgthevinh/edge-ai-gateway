# Edge Agent

Edge Agent is a local AI agent service for edge devices. It runs a Java backend, an Angular web UI, local LLM inference through an OpenAI-compatible `llama-server`, persistent chat sessions in SQLite, optional MCP tools, a long-term Knowledge system, and a short-content RAG system.

The project is designed for local-first agent use: the browser talks to the gateway, the gateway talks to local model servers and tools, and chat history is stored locally.

## What The Agent Does

The assistant is an autonomous local agent that can:

- Chat with streamed responses.
- Preserve sessions and chat history across browser reloads.
- Call built-in tools.
- Call configured MCP tools from `mcp-servers.json`.
- Search short RAG chunks semantically.
- Use long-term Knowledge documents as stable system context.
- Show token speed, elapsed time, and latest context length in the UI when the model server returns usage.

The agent follows OpenAI-style tool calling. When the model requests a tool, the gateway executes it, appends the assistant tool-call message and tool result messages to history, then continues the model turn.

## Tested Models

The project has been tested with:

| Purpose | Model |
|---|---|
| Chat / agent model | `gemma 4 e2b it` GGUF, `q8_0` quantized |
| Embedding model | `embedding-gemma300m` GGUF, `q8_0` quantized |

Both model servers should expose OpenAI-compatible HTTP endpoints. The chat model is used through `/v1/chat/completions`; the embedding model is used through an OpenAI-compatible embedding endpoint.

## Requirements

- Java 17+
- Node.js and npm for the Angular client build
- A running OpenAI-compatible `llama-server` for chat
- A running OpenAI-compatible embedding server for RAG
- Linux is the primary target. RK3588 ARM64 is the intended edge deployment target; x86_64 works for development.

## Install

Clone the repository and install the Angular dependencies:

```bash
cd edge-ai-gateway
cd client
npm install
cd ..
```

Create a local `.env` file:

```bash
cp .env.example .env
```

Edit `.env`:

```text
LLAMA_SERVER_URL=http://localhost:8081
API_KEY=
LLM_MODEL=gemma-4-e2b-it

EMBEDDING_SERVER_URL=http://localhost:8082
EMBEDDING_MODEL=embedding-gemma300m
EMBEDDING_DIMENSIONS=768

KNOWLEDGE_SYSTEM_CONTEXT_MAX_CHARS=12000
```

Adjust URLs, model names, and dimensions to match your local model servers. `API_KEY` can be empty for local servers that do not require authentication.

## Build

Build the backend and Angular client:

```bash
./gradlew build
```

Run tests and rebuild static client resources:

```bash
./gradlew test
```

The Gradle build runs the Angular production build and syncs the output into `src/main/resources/public`.

## Run

Start your chat and embedding model servers first.

Then run the Java main class from your IDE:

```text
thingai.edge.aigateway.Main
```

The Javalin server listens on:

```text
http://localhost:8080/
```

Open that URL in a browser to use the chat UI.

There is currently no Gradle `application` task configured, so the supported development path is running `Main` from the IDE.

## Optional RK3588 Performance Setup

On RK3588 devices, set governors to performance mode before running inference:

```bash
echo performance | sudo tee /sys/bus/cpu/devices/cpu[0-7]/cpufreq/scaling_governor
echo performance | sudo tee /sys/class/devfreq/fb000000.gpu/governor
echo performance | sudo tee /sys/devices/platform/dmc/devfreq/dmc/governor
echo performance | sudo tee /sys/class/devfreq/fdab0000.npu/governor
```

## Using The Web UI

### Chat

Use the input box at the bottom of the page to chat with the agent.

The UI shows:

- Current stream status.
- Tool calls made during a turn.
- Token usage for each assistant message when available.
- Prompt processing speed (`PP`) and token generation speed (`TG`) when returned by the model server.
- End-to-end response time.
- Latest context length, based on the latest chat response `usage.totalTokens`.

### Sessions

The sidebar lets you:

- Create a new session.
- Switch sessions.
- Delete a session.
- Enter or paste a session id manually.

Chat sessions and messages are stored in SQLite through the DAO layer. Browser `localStorage` is only used for UI cache such as the active session id, per-session usage display, and latest context length display.

The local database is stored under the service app directory, typically:

```text
~/.thingai/edge-ai-gateway/data.db
```

## Knowledge System

Knowledge is for long-term, stable information that should always be available to the agent.

Examples:

- Project specifications
- User preferences
- Device manuals
- Stable business rules
- Long-lived notes

Knowledge documents are appended to the agent system instruction, oldest to newest. This is intentional: stable system-prefix content can help llama.cpp reuse KV cache across requests. If the Knowledge content and ordering stay stable, repeated prompt processing can be reduced.

Use Knowledge when the content is durable and worth keeping in the prompt prefix.

Do not use Knowledge for short frequently changing snippets. Frequent edits change the prompt prefix and reduce KV cache reuse.

## RAG Chunk System

RAG chunks are for short, dynamic searchable content.

Examples:

- Small facts
- Temporary notes
- Short excerpts
- Content that changes often
- Searchable snippets that should not always be in the system prompt

RAG chunks are embedded and stored in vector search. The agent can use the `search_documents` tool to retrieve relevant chunks at runtime.

Rules:

- Each chunk is limited to 200 words.
- RAG chunks are not appended to the system instruction.
- Search returns multiple matching chunks with distance.
- Use RAG when retrieval should be dynamic instead of always present in context.

## Knowledge vs RAG

| Feature | Knowledge | RAG Chunks |
|---|---|---|
| Intended content | Stable long-term documents | Short dynamic snippets |
| Storage | SQLite document table | SQLite vector-indexed chunk table |
| Embedding | No | Yes |
| Retrieval | Always appended to system instruction | Retrieved by semantic search tool |
| KV cache impact | Can benefit stable prompt prefix | Does not help prompt-prefix cache |
| Recommended size | Longer documents are acceptable within configured context limit | 200 words max |

Use Knowledge for stable background context. Use RAG for dynamic lookup.

## MCP Tools

MCP servers are configured in:

```text
mcp-servers.json
```

At startup, the gateway loads configured MCP servers and exposes their tools to the agent. MCP tools are optional; the agent can still run with built-in tools when no external MCP server is configured.

Keep MCP tool descriptions concise. Large tool descriptions increase prompt size and can slow local inference.

## Configuration Reference

| Variable | Description |
|---|---|
| `LLAMA_SERVER_URL` | OpenAI-compatible chat model server URL |
| `API_KEY` | Bearer token for chat and embedding servers, if required |
| `LLM_MODEL` | Chat model name sent to the server |
| `EMBEDDING_SERVER_URL` | OpenAI-compatible embedding server URL |
| `EMBEDDING_MODEL` | Embedding model name sent to the server |
| `EMBEDDING_DIMENSIONS` | Embedding vector dimensions used to initialize vector search |

## Useful Commands

Build everything:

```bash
./gradlew build
```

Run backend checks:

```bash
./gradlew test
```

Run the Angular dev server:

```bash
cd client
npm start
```

Build the Angular client only:

```bash
cd client
npm run build
```

## Troubleshooting

### The UI opens but chat fails

Check that `LLAMA_SERVER_URL` points to a running OpenAI-compatible chat server.

### RAG search fails

Check:

- `EMBEDDING_SERVER_URL`
- `EMBEDDING_MODEL`
- `EMBEDDING_DIMENSIONS`

The embedding dimensions in `.env` must match the model output dimensions and the vector index initialization.

### Chat history is missing

The UI loads history from the backend DAO, not from browser message storage. Confirm the server is using the expected app data directory and database:

```text
~/.thingai/edge-ai-gateway/data.db
```

### MCP tools fail at startup

Check `mcp-servers.json`, authorization headers, transport type, and the target MCP server URL. MCP failures should not prevent the core UI from loading, but unavailable MCP tools will not be callable by the agent.

## Development Notes

Important architecture boundaries:

- `Agent` is execution-only.
- `AgentOrchestrator` owns session history, persistence, and tool chaining.
- Route classes should stay thin.
- `KnowledgeHandler` is CRUD/system-context only.
- `RagHandler` owns embeddings and semantic search.
- `LlamaCppProvider` wraps HTTP; do not add JNI llama bindings.

See `docs/specs/project-specs.md` for the deeper architecture specification.
