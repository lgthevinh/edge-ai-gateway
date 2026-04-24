# Integrate java llama.cpp binding with rk-llama.cpp

## Task Description
1. Research and understand the java llama.cpp binding
2. Understanding the integration of binding llama.cpp into java using the java llama.cpp binding
   - Explore the documentation and examples provided by the java llama.cpp binding
   - Identify build directory for llama.cpp to work with java llama.cpp binding
3. Giving options to integrate java llama.cpp binding with rk-llama.cpp
   - Compare performance, ease of use, and scalability of options
   - Example of implementation of each option

## Deliverables
Undefined

## Output

### Project context (current state)

The project already depends on **`de.kherud:llama:4.1.0`** (Gradle, Kotlin DSL). The inference layer (`InferenceEngine`, `InferenceTask`, `InferenceHandler`, `Session`, `ToolRegistry`) is scaffolded but not yet wired to a real model. Existing reference docs at `/docs/technical/java-binding-llamacpp.md` and `/docs/technical/rk3588-deploy-note.md` cover related ground.

The integration question is therefore **not** "which Java binding to choose," but **"how do we get kherud's `libjllama.so` to dispatch to the rk-llama.cpp NPU backend on RK3588 while still working on x86_64 dev machines."**

---

### Phase 1 — Java llama.cpp binding (kherud/java-llama.cpp)

- **Latest:** v4.2.0 (Jun 2025), MIT, ~415★. Project pinned at 4.1.0 — bump may be worthwhile.
- **Mechanism:** Hand-written **JNI** in `src/main/cpp/jllama.cpp`. `System.loadLibrary("jllama")` → `libjllama.so` dynamically links against `libllama.so` + `libggml.so`.
- **Native lib search order:**
  1. `-Dde.kherud.llama.lib.path=...`
  2. `java.library.path`
  3. Bundled binaries inside the JAR at `/de/kherud/llama/<os-arch>/`
- **llama.cpp coupling:** Each release pins a specific upstream commit as a Git submodule (v4.1.0 ≈ `b4916`). JNI calls real `llama.h` symbols → **ABI must match exactly.**
- **Build flow:** `mvn compile` → `cmake -B build [...]` → `cmake --build build`. CMake does `add_subdirectory(llama.cpp)` against the in-tree submodule. No `find_package(Llama)` path; externally-built llama.cpp is unsupported at build time but can be **dropped in at runtime** via `LD_LIBRARY_PATH` / `lib.path` provided ABI matches.

**Alternatives surveyed:**
- `QuasarByte/llama-cpp-jna` — pure JNA, no native to recompile, slower per-call.
- `Jlama` — pure Java (Vector API), unrelated to llama.cpp.
- LangChain4j's llama-cpp module — a wrapper *around* kherud, not an independent binding.

---

### Phase 2 — rk-llama.cpp & build directory expectations

**Canonical fork:** [`invisiofficial/rk-llama.cpp`](https://github.com/invisiofficial/rk-llama.cpp), branch `rknpu2`, MIT, actively tracking upstream as of 2026. Successor to the original [`marty1885/llama.cpp`](https://github.com/marty1885/llama.cpp) rknpu2-backend branch (last push Jun 2025).

**Architectural shape:**
- Adds a new GGML backend at `ggml/src/ggml-rknpu2/` — slots in like CUDA/Vulkan/Metal.
- Integrates at the **GGML graph-execution level**, not by replacing llama.cpp with the proprietary RKLLM runtime.
- **`llama.h` C API is preserved** → bindings see standard llama.cpp ABI.
- Standard **GGUF** models (no `.rkllm` conversion needed — that's the *other* path via `airockchip/rknn-llm`).
- Selected via `-DGGML_RKNPU2=ON` at CMake time; runtime activation via `n_gpu_layers > 0`.

**Runtime deps:** `librknnrt.so` (Rockchip RKNN userspace, `/usr/lib/`) + matching `rknpu` kernel driver (mainline 6.1+ Rockchip BSP). **No** `librkllmrt.so` for this fork.

**Build directory mapping for kherud:**
- kherud expects llama.cpp sources at `src/main/cpp/llama.cpp` (the submodule path).
- Output `libjllama.so` lands in `build/`, then is copied to `src/main/resources/de/kherud/llama/linux-aarch64/` so the JAR self-contains binaries.
- For RK3588 you must produce **aarch64** binaries linked against the rk fork.

**Caveat:** rk-llama performance on RK3588 is mixed — prefill benefits more than decode; matmul/quant kernels are limited by what RKNN exposes. Realistic outcome is hybrid CPU+NPU.

---

### Phase 3 — Integration options

| Option | Mechanism | Perf | Maintenance | Risk |
|---|---|---|---|---|
| **A. Runtime swap** | Drop rk-built `libllama.so`/`libggml.so` next to stock `libjllama.so` via `lib.path` | Full NPU | Low day-to-day, high on upgrade | **High** ABI drift |
| **B. Rebuild kherud against rk fork** ⭐ | Fork kherud, point submodule at rk-llama, build with `-DGGML_RKNPU2=ON` | Full NPU | Medium (own a kherud fork) | **Lowest** — ABI correct by construction |
| **C. Bypass kherud — FFM/jextract on `librkllmrt.so`** | Use Rockchip's `rknn-llm` runtime directly via JDK 22+ FFM | Best (vendor-tuned ops) | You own a small binding | Different model format (`.rkllm`), narrower coverage, no llama.cpp samplers/grammar |
| **D. Sidecar `llama-server`** | Run rk-llama's HTTP server as a process; Java talks OpenAI-compatible REST | Localhost HTTP overhead (negligible) | Lowest — fully decoupled | Lowest integration risk; worst feature parity for fine-grained control |

#### Implementation sketches

**A — runtime swap**
```bash
java -Dde.kherud.llama.lib.path=/opt/rk-llama/lib \
     -Djava.library.path=/usr/lib/aarch64-linux-gnu \
     -jar edge-ai-gateway.jar
```
Java code unchanged — keeps using `de.kherud.llama.LlamaModel` from current Gradle dep.

**B — rebuild kherud (recommended)**
```bash
git clone https://github.com/kherud/java-llama.cpp && cd java-llama.cpp
git submodule set-url src/main/cpp/llama.cpp https://github.com/invisiofficial/rk-llama.cpp
git -C src/main/cpp/llama.cpp checkout rknpu2
mvn compile
cmake -B build -DGGML_RKNPU2=ON -DRKNN_RUNTIME_PATH=/usr
cmake --build build --config Release
mvn package  # produces a JAR with bundled aarch64 libjllama.so
```
Publish to a private Maven repo (or `libs/`) and switch the Gradle dep:
```kotlin
// build.gradle.kts
implementation("thingai:llama-rk:4.2.0-rknpu2")
```
`InferenceEngine` code stays identical — drop-in.

**C — FFM on rkllm-runtime**
```java
// jextract -t com.rk.llm rkllm.h --output src/main/java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment handle = rkllm_init(params, callback);
    rkllm_run(handle, prompt, inferParams, userData);
    rkllm_destroy(handle);
}
```
Requires JDK 22+. Means writing a parallel inference path: existing kherud-based `InferenceEngine` for x86, FFM-based engine for RK3588 — pick at runtime via SPI / strategy.

**D — server sidecar**
```bash
/opt/rk-llama/llama-server -m /models/gemma-4-e2b-Q4_K_M.gguf \
    --host 127.0.0.1 --port 8081 -ngl 999
```
```java
// Java side — small OkHttp client to /v1/chat/completions
Request req = new Request.Builder()
    .url("http://127.0.0.1:8081/v1/chat/completions")
    .post(RequestBody.create(json, MediaType.get("application/json")))
    .build();
```
Two processes, but zero JNI fragility and the same setup works for x86 dev.

---

### Recommendation

1. **Short term:** validate end-to-end with **Option D (sidecar)** — proves NPU works, no ABI risk, x86 dev parity.
2. **Production:** move to **Option B (rebuild kherud against rk-llama)** — keeps the Java API stable, eliminates the runtime-swap ABI footgun, and produces a single JAR shippable to both x86 and RK3588 with platform-specific resource directories.
3. Reserve **Option C** for if/when benchmarking shows ggml-rknpu2 is materially behind Rockchip's vendor RKLLM runtime for Gemma 4 E2B.
4. Avoid **Option A** unless rk-llama is pinned to the exact upstream commit kherud's release uses.

---

### Sources

- [kherud/java-llama.cpp](https://github.com/kherud/java-llama.cpp) — JNI binding, MIT, v4.2.0 (Jun 2025)
- [invisiofficial/rk-llama.cpp](https://github.com/invisiofficial/rk-llama.cpp) — RKNPU2 GGML backend fork
- [marty1885/llama.cpp (rknpu2-backend)](https://github.com/marty1885/llama.cpp) — original RK3588 fork
- [airockchip/rknn-llm](https://github.com/airockchip/rknn-llm) — Rockchip's RKLLM runtime + toolkit
- [QuasarByte/llama-cpp-jna](https://github.com/QuasarByte/llama-cpp-jna) — alternative JNA-based Java binding
- [ggml-org/llama.cpp bindings list](https://github.com/ggml-org/llama.cpp#bindings)
