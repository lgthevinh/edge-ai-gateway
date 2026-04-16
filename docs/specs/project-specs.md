# Edge Agent

## Overview
AI agents/agentic service running with local llm inference (on the edge) to provide AI Automation and Assistance for users and through multi-step reasoning. With exposed API for services integrate and web UI for users interaction.

## Tech stack
As the project target is to run on very limited hardware, the tech stack is focused on lightweight.
- LLM: Gemma 4 E2B model, GGUF format, 4 bit quantization.
- Inference: llama.cpp, with integrate option of rk-llama.cpp for RK3588 devices. JNI wrapper for Java integration.
- API: Javalin, REST and WebSocket.
- Agent framework: Not decided yet, maybe self-implemented or based on existing open-source agent framework.
- Web UI: Not decided yet, static web page for now.
- OS: Linux, with focus on RK3588 devices, but also support x86_64 devices.