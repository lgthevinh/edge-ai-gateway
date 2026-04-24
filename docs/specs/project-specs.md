# Edge Agent

## Overview
AI agents/agentic service running with local llm inference (on the edge) to provide AI Automation and Assistance for users and through multi-step reasoning. With exposed API for services integrate and web UI for users interaction.

## Tech stack
As the project target is to run on very limited hardware, the tech stack is focused on lightweight.
- LLM: Gemma 4 E2B model, GGUF format, 4 bit quantization.
- Inference: llama.cpp `llama-server` running as a sidecar process, with integrate option of rk-llama.cpp for RK3588 devices. The Java gateway proxies to the OpenAI-compatible HTTP API.
  - **Note (Apr-24):** The Java JNI binding (`de.kherud:llama`) is **unusable for now** when paired with rk-llama.cpp due to ABI incompatibility — kherud's bundled `libjllama.so` is built against a pinned upstream llama.cpp commit whose struct layouts have drifted from rk-llama's current `rknpu2` branch, causing native crashes (SIGSEGV in `libjllama.so`). Rebuilding kherud against rk-llama is possible in principle but blocked on JNI vs. header drift. **Fallback:** spawn `llama-server` as a child process and wrap its HTTP API; Java owns no JNI code. Revisit JNI integration later if/when ABI alignment becomes feasible.
- API: Javalin, REST and WebSocket.
- Agent framework: Not decided yet, maybe self-implemented or based on existing open-source agent framework.
- Web UI: Not decided yet, static web page for now.
- OS: Linux, with focus on RK3588 devices, but also support x86_64 devices.