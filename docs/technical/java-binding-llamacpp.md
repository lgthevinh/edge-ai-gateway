# java-llama.cpp — Technical Reference

Research notes on [kherud/java-llama.cpp](https://github.com/kherud/java-llama.cpp), the Java JNI binding for llama.cpp. Captured for Edge Agent's Phase 1 integration strategy (standard llama.cpp on x86/CUDA, custom rk-llama.cpp on RK3588).

---

## 1. Project Summary

- **Purpose:** Java bindings around llama.cpp enabling local LLM inference from JVM applications.
- **License:** MIT.
- **Language mix:** C++ ~66%, Java ~33%, CMake ~1%.
- **Current version:** 4.1.0 (based on llama.cpp build `b4916`).
- **Maven coordinates:**
  ```xml
  <dependency>
      <groupId>de.kherud</groupId>
      <artifactId>llama</artifactId>
      <version>4.1.0</version>
  </dependency>
  ```
- **Java requirement:** Java 11+.

---

## 2. Supported Platforms (prebuilt)

CPU inference works out of the box on:

| OS      | Architectures          |
|---------|------------------------|
| Linux   | x86-64, aarch64        |
| macOS   | x86-64, aarch64 (M-series) |
| Windows | x86-64, x64            |

GPU backends (CUDA, Metal, Vulkan, HIP, SYCL, etc.) require **building from source** — see §6.

---

## 3. Repository Layout

```
src/main/
├── cpp/                         # JNI bridge, compiled to libjllama
│   ├── jllama.cpp               # JNI function implementations
│   ├── jllama.h                 # JNI header (javac -h generated)
│   ├── server.hpp               # Adapted from llama.cpp's server example
│   └── utils.hpp
└── java/de/kherud/llama/
    ├── LlamaModel.java          # Core class — model lifecycle + inference
    ├── ModelParameters.java     # Model load config (builder)
    ├── InferenceParameters.java # Per-call inference config
    ├── LlamaOutput.java         # Streaming token result
    ├── LlamaIterable.java       # Streaming iterator wrappers
    ├── LlamaIterator.java
    ├── LlamaLoader.java         # Native library loader (priority search)
    ├── LlamaException.java
    ├── LogLevel.java
    ├── CliParameters.java
    ├── JsonParameters.java
    ├── OSInfo.java              # Platform detection
    ├── Pair.java
    ├── ProcessRunner.java
    └── args/                    # Argument enums/types
```

The JNI layer internally reuses logic from **llama.cpp's `server` example** (`server.hpp`), which is why chat templates, grammars, slots, and OpenAI-like request shapes are exposed.

### 3.1 Parameter base classes (verified from source)

The two parameter builders inherit from different base classes because they cross the JNI boundary in different formats:

```
CliParameters (abstract)              JsonParameters (abstract)
  └─ ModelParameters                    └─ InferenceParameters
     (load-time CLI args)                  (per-call JSON body)
```

- **`CliParameters`** holds a `Map<String, @Nullable String>` and exposes:
  - `toString()` — space-separated `key value …`
  - `toArray()` — C-style `argv` (first element empty as the program-name slot)
- **`JsonParameters`** serializes to a JSON object — mirroring llama.cpp server's HTTP request body.

**Why this matters:** java-llama.cpp does not expose llama.cpp's C API directly. Instead it embeds llama.cpp's **server** code and feeds it CLI-style args at load and JSON bodies at inference time. Anything supported by `llama-server`'s flags or its `/completion` JSON schema is reachable; anything outside that surface requires JNI changes.

---

## 4. Native Library Loading

One shared library, platform-named:
- Linux: `libjllama.so`
- macOS: `libjllama.dylib`
- Windows: `jllama.dll`

`LlamaLoader` searches in this priority order:
1. `-Dde.kherud.llama.lib.path=/path/to/dir` (JVM system property)
2. `java.library.path` (system library paths)
3. Prebuilt binaries bundled inside the JAR at `de/kherud/llama/<OS>/<arch>/`

**Implication for Edge Agent:** You can ship your rk-llama.cpp-linked `libjllama.so` and point to it via `-Dde.kherud.llama.lib.path`, without modifying the JAR.

---

## 4a. End-to-End Flow (How It Works Internally)

This section walks the full request path from JVM startup through inference to shutdown — useful for reasoning about performance, concurrency, and how rk-llama.cpp slots into the build.

### Architectural diagram

```
┌──────────────────────────────────────────────────────────────┐
│  Application (Java)                                          │
│  new LlamaModel(modelParams).complete(inferParams)           │
└────────────────────────┬─────────────────────────────────────┘
                         │
            ┌────────────▼──────────────┐
            │  LlamaModel.java          │  Java public API
            │  - native method decls    │  + AutoCloseable
            └────────────┬──────────────┘
                         │ JNI
            ┌────────────▼──────────────┐
            │  libjllama.so             │  C++ JNI bridge
            │  jllama.cpp + server.hpp  │  (server.hpp = llama.cpp's
            │                           │   examples/server, embedded)
            └────────────┬──────────────┘
                         │ direct C++ calls
            ┌────────────▼──────────────┐
            │  llama.cpp + ggml         │  statically linked into
            │  - GGUF loader            │  the same libjllama.so
            │  - sampler, KV cache      │
            └────────────┬──────────────┘
                         │
                ┌────────┴────────┐
                ▼                 ▼
          CPU (BLAS)      GPU (CUDA/Metal/Vulkan)
                          or NPU (rk-llama.cpp → RKNN)
```

### Stage 1: JVM startup — native library load

`LlamaModel`'s static initializer runs `LlamaLoader.initialize()`, which:

1. Detects OS + architecture via `OSInfo.java`
2. Searches the priority list from §4 for `libjllama.{so,dylib,dll}`
3. Calls `System.load()` to map the library into the JVM process
4. The OS dynamic linker resolves transitive deps (libstdc++, CUDA runtime, librknnrt, etc.)
5. JNI wires Java `native` method declarations to their C symbols in the loaded library

After this, the binding is "armed" — but no model is loaded yet.

### Stage 2: `new LlamaModel(params)` — model load (expensive, once)

```java
public LlamaModel(ModelParameters parameters) {
    loadModel(parameters.toArray());
}
```

`ModelParameters.toArray()` produces a C-style `argv` from the CLI param map:

```
["", "--model", "models/gemma.gguf", "--ctx-size", "4096",
 "--n-gpu-layers", "43", "--threads", "4", ...]
```

Crossing JNI as a `String[]`, on the C++ side `loadModel`:

1. Feeds the args into llama.cpp **server's argument parser** (the same parser `llama-server` uses on the command line)
2. Calls `llama_model_load_from_file()` — GGUF read from disk into RAM, optionally offloaded to GPU/NPU
3. Creates a `llama_context` — KV cache buffers allocated based on `ctx_size`
4. Stores the native context pointer on the Java object as a `long` handle (standard JNI pattern)

This is the **only expensive step** in normal operation. After construction the model lives in native memory until `close()` — see §5.2.

### Stage 3: Inference — `complete()` / `generate()` / `embed()`

`InferenceParameters.toString()` (via `JsonParameters`) serializes to a JSON object:

```json
{
  "prompt": "Hello",
  "temperature": 0.7,
  "n_predict": 256,
  "stop": ["</s>"],
  "stream": false
}
```

The JSON crosses JNI as a `String`. On the C++ side:

1. `jllama.cpp` invokes the **server's request handler** — the same code path that handles `POST /completion` in `llama-server`
2. The request is queued into a **slot** (server.hpp's slot abstraction)
3. Tokenization, prompt processing, and the sampling loop run entirely in native code
4. **Blocking mode (`complete`)**: returns the full string when generation finishes
5. **Streaming mode (`generate`)**: returns a `LlamaIterable`. Under the hood Java repeatedly calls a native `receiveCompletion()` that blocks for the next chunk and returns a `LlamaOutput`. This is a poll model, **not** C-to-Java callbacks — simpler and avoids JNI callback complexity.

### Stage 4: `model.close()` — frees native memory

`close()` calls native `delete()`, which:
1. Frees the `llama_context` (KV cache, sampler state)
2. Frees the `llama_model` (weights from RAM/VRAM)
3. Releases slot resources

The Java object is unusable afterwards. See §5.2 — in steady-state servers this runs only at shutdown.

### Key architectural choices

**A. Embeds the server, not the bare C API.** This is the single biggest design decision. java-llama.cpp does not wrap `llama.h` one-to-one; it links in `server.hpp` (from `llama.cpp/examples/server`) and feeds it CLI args + JSON requests.

- *Pros:* free chat templates, grammar/JSON-schema, OpenAI-shaped requests, slot management, less Java code to maintain.
- *Cons:* API surface limited to what the server exposes. Adding new params (e.g., rk-llama.cpp NPU knobs) requires modifying both the server's arg parser AND the JNI bridge. More memory overhead than a minimal wrapper would have.

**B. Two parameter formats reflect two boundaries.**

| Builder              | Format       | Crosses JNI as | Consumed by              |
|----------------------|--------------|----------------|--------------------------|
| `ModelParameters`    | CLI argv     | `String[]`     | server's arg parser      |
| `InferenceParameters`| JSON object  | `String`       | server's request handler |

Mirrors exactly how `llama-server` is used in practice: CLI flags at startup, JSON bodies per HTTP request.

**C. Native handle stored in Java object.** The C++ `llama_context*` is held as a `long` field on `LlamaModel`. All native methods take this handle implicitly via `this`. The handle is invalidated by `close()`.

**D. Single shared library.** JNI bridge + llama.cpp + ggml + chosen backend (CUDA/Metal/RKNN/etc.) all link into one `libjllama.so`. There is no separate `libllama.so` to ship. **Swapping backends means rebuilding `libjllama.so` against a different ggml configuration (or against rk-llama.cpp).**

### Implications for Edge Agent

1. **rk-llama.cpp swap is a build problem, not a Java-code problem.** Rebuild `libjllama.so` against rk-llama.cpp, drop it in `native/linux-aarch64-rk/`, point `-Dde.kherud.llama.lib.path` at it. Java code is unchanged across backends.
2. **Chat templates may already work natively.** llama.cpp's server template engine is exposed via `applyTemplate()` + `setUseChatTemplate(true)`. The Gemma 4 template stored in GGUF metadata may render without a Java template renderer. Probe this before designing a custom renderer.
3. **The slot model constrains concurrency.** One `LlamaModel` instance effectively serves one in-flight request at a time. To handle concurrent requests with isolated KV caches you need either multiple `LlamaModel` instances (multiplies RAM/VRAM) or a single instance with serialized access. This is a key decision for the `InferenceEngine` layer design.

---

## 5. Core Java API

### 5.1 LlamaModel

Main entry point. Implements `AutoCloseable` — must be closed to release native memory.

**Verified source structure:**

```java
public class LlamaModel implements AutoCloseable {
    static { LlamaLoader.initialize(); }            // loads libjllama once per JVM

    public LlamaModel(ModelParameters parameters) {
        loadModel(parameters.toArray());            // native call — loads GGUF
    }

    @Override public void close() { delete(); }     // native — frees model

    // public API
    public String        complete(InferenceParameters p);
    public LlamaIterable generate(InferenceParameters p);
    public float[]       embed(String text);
    public int[]         encode(String text);
    public String        decode(int[] tokens);
    public List<Pair<String, Float>> rerank(boolean normalize, String query, String... docs);
    public String        applyTemplate(InferenceParameters p);
    // + static setLogger(LogFormat, BiConsumer<LogLevel,String>)
}
```

### 5.2 Lifecycle: model loads ONCE, not per call

Critical for server-style apps. The constructor is the only point that loads the GGUF into RAM/VRAM:

| Operation                        | Cost                                  |
|----------------------------------|---------------------------------------|
| `new LlamaModel(params)`         | **Expensive** — loads GGUF, allocates KV buffers, GPU offload |
| `complete()` / `generate()` / `embed()` / `encode()` / `decode()` / `rerank()` / `applyTemplate()` | **Cheap relative to load** — operates on already-loaded native state |
| `close()`                        | Frees native memory (model + buffers) |

**Anti-pattern — do NOT do this** (loads + frees on every request):

```java
// BAD: reloads the model for every call
try (LlamaModel model = new LlamaModel(params)) {
    return model.complete(prompt);
}
```

**Correct pattern — hold as a long-lived singleton:**

```java
public class InferenceService implements AutoCloseable {
    private final LlamaModel model;

    public InferenceService(Config cfg) {
        this.model = new LlamaModel(
            new ModelParameters()
                .setModel(cfg.modelPath())
                .setCtxSize(4096)
                .setGpuLayers(cfg.gpuLayers())
        );
    }

    public String chat(String prompt) {
        return model.complete(new InferenceParameters(prompt));
    }

    @Override public void close() { model.close(); }
}
```

Wire shutdown via Javalin lifecycle:

```java
Javalin app = Javalin.create()
    .events(e -> e.serverStopping(inferenceService::close))
    .start(8080);
```

In steady-state operation the server **never calls `close()`** between requests. `AutoCloseable` is for graceful shutdown, hot model swapping, and tests — not for per-call resource handling. Native memory lives outside the JVM heap and is invisible to the GC, which is why explicit `close()` exists.

### 5.3 No conversation memory between calls

The model itself is **stateless across inference calls** at the application level: each `complete()` / `generate()` sees only the prompt you pass. To maintain a conversation you must re-send the full history on every call.

What java-llama.cpp/llama.cpp *does* keep across calls is an internal **prompt KV cache** for prefix reuse — a performance optimization that makes growing-history requests fast, not a memory feature. The model has no awareness of prior turns unless they appear in the current prompt.

This is why the layering for Edge Agent needs an explicit conversation/session manager above the inference engine — see the chat-template doc.

### 5.4 ModelParameters (load-time, CLI-style)

Fluent builder, extends `CliParameters`. Highlights:

- **Threading / CPU:** `setThreads`, `setThreadsBatch`, `setCpuMask`, `setCpuRange`, `setPriority`
- **Context / batching:** `setCtxSize`, `setPredict`, `setBatchSize`, `setUbatchSize`
- **Sampling defaults:** `setTemp`, `setTopK`, `setTopP`, `setMirostat`, `setRepeatPenalty`, `setPresencePenalty`
- **Model loading:** `setModel`, `setGpuLayers`, `setDevices`, `enableMlock`, `disableMmap`
- **Constraints / adapters:** `setGrammar`, `setJsonSchema`, `addLoraAdapter`, `addControlVector`

All values map to llama-server CLI flags, then go through `toArray()` → JNI → native arg parser at construction time.

### 5.5 InferenceParameters (per-call, JSON body)

Fluent builder, extends `JsonParameters`. Constructor takes the prompt: `new InferenceParameters(String prompt)`. Highlights:

- **Sampling:** `setTemperature`, `setTopK`, `setTopP`, `setMinP`, `setMiroStat`, `setSamplers`
- **Token control:** `setNPredict`, `setRepeatPenalty`, `setFrequencyPenalty`, `setPresencePenalty`, `setTokenIdBias`, `disableTokenIds`
- **Constraints:** `setGrammar` (BNF), `setStopStrings`
- **Chat:** `setMessages` (structured chat messages), `setUseChatTemplate` (apply model's built-in template from GGUF metadata)

The presence of `setMessages` + `setUseChatTemplate` + `LlamaModel.applyTemplate()` means the **chat template stored in the GGUF can be applied natively** without rendering it in Java — provided the template's features (tool calls, thinking channel, multimodal) are supported by llama.cpp's template engine. This is worth probing before designing a custom Java template renderer for Gemma 4.

### 5.6 Inference modes

**Streaming (token-by-token):**
```java
InferenceParameters ip = new InferenceParameters("Hello")
    .setTemperature(0.7f)
    .setNPredict(256);

for (LlamaOutput out : model.generate(ip)) {
    System.out.print(out);
}
```

**Blocking (full string):**
```java
String response = model.complete(ip);
```

**Embeddings:**
```java
float[] vec = model.embed("some text");
```

**Tokenization / detokenization:**
```java
int[] tokens = model.encode("hello world");
String text  = model.decode(tokens);
```

**Reranking:**
```java
List<Pair<String, Float>> ranked = model.rerank(true, "query", "doc1", "doc2", "doc3");
```

**Template application:**
```java
String prompt = model.applyTemplate(
    new InferenceParameters("").setMessages(...).setUseChatTemplate(true));
```

### 5.7 Additional features
- **Grammar / JSON schema constraints** — GBNF grammars for constrained decoding.
- **Infilling** — `setInputPrefix()` + `setInputSuffix()`.
- **LoRA adapters** — via `ModelParameters`.
- **Logger injection** — `LlamaModel.setLogger(LogFormat.TEXT | JSON, callback)` to route native logs into SLF4J/log4j.
- **Prompt cache reuse** — across calls with overlapping prefixes (performance only, see §5.3).

---

## 6. Building from Source (custom backends)

This is the primary path for Edge Agent's phased integration.

### 6.1 Standard flow
```bash
# Clone with llama.cpp submodule
git clone --recursive https://github.com/kherud/java-llama.cpp.git
cd java-llama.cpp

# Compile Java first (generates JNI headers)
mvn compile

# Configure + build native
cmake -B build [BACKEND FLAGS]
cmake --build build --config Release
```

### 6.2 Common backend flags (passed to CMake)
| Flag                    | Backend             |
|-------------------------|---------------------|
| `-DGGML_CUDA=ON`        | NVIDIA CUDA         |
| `-DGGML_METAL=ON`       | Apple Metal         |
| `-DGGML_VULKAN=ON`      | Vulkan              |
| `-DGGML_HIPBLAS=ON`     | AMD ROCm            |
| `-DGGML_BLAS=ON`        | OpenBLAS / MKL      |

### 6.3 Output
Compiled `libjllama.*` lands in:
```
src/main/resources/de/kherud/llama/<OS>/<arch>/
```
…which means the built JAR will embed it, OR you can copy the `.so` elsewhere and use `-Dde.kherud.llama.lib.path`.

### 6.4 Swapping llama.cpp for rk-llama.cpp
Two practical options for Phase 1:

**Option A — submodule replacement.** Replace the `llama.cpp` git submodule with your rk-llama.cpp fork. Works cleanly if rk-llama.cpp keeps the same public C API (`llama.h`, `ggml.h`).

**Option B — CMake pointer.** Leave the submodule alone; override the CMake llama target to point at a prebuilt rk-llama.cpp static/shared library.

Produces a second artifact: `libjllama-rk.so`, deployed separately from the x86/CUDA build.

## 7. Known Limitations / Gotchas

- **Server-derived code path.** The JNI layer wraps llama.cpp's `server.hpp`, which pulls in slot/request plumbing — more surface area than a minimal binding, but gives chat template + grammar support for free.
- **API surface is fixed.** You cannot expose new parameters (e.g., rk-llama.cpp's NPU-specific layer offload knobs) without modifying JNI. For Phase 1, use env vars or hardcoded defaults inside the C++ layer; solve properly in Phase 2.
- **Single native lib name.** All backends compile to `libjllama`. Use directory separation, not filename variants, to ship multiple builds.
- **Memory.** `LlamaModel` holds large native allocations. Always `close()` on shutdown; do not rely on GC. In steady state, however, the server holds exactly one long-lived instance — never close per-request (see §5.2).
- **No conversation memory.** Every inference call is independent at the model level. The application must maintain chat history and re-send it in the prompt. llama.cpp's internal prefix cache accelerates this but does not replace it (§5.3).
- **Thread safety.** A `LlamaModel` instance should be serialized per request; use a dedicated worker thread or pool for inference.
- **llama.cpp pin.** Version 4.1.0 = llama.cpp b4916. GGUF files produced by much newer llama.cpp releases may fail to load; pin model conversion to a compatible build.


## 8. References

- Repo: https://github.com/kherud/java-llama.cpp
- llama.cpp: https://github.com/ggml-org/llama.cpp
- Build base: llama.cpp `b4916`
- Maven Central: `de.kherud:llama:4.1.0`
