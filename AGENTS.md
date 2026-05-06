# Repository Guidelines

## Project Structure & Module Organization

This repository contains a Java 17+ edge AI gateway and an Angular client. Backend source lives in `src/main/java/thingai/edge/aigateway`, with packages for `agent`, `api`, `llm`, `session`, and `utils`. Tests mirror this layout under `src/test/java`. Static backend assets are in `src/main/resources/public`. The Angular app lives in `client/src`; follow `client/AGENTS.md` for Angular rules. Specs are under `docs/`, local JARs in `libs/`, and MCP configuration in `mcp-servers.json`.

## Build, Test, and Development Commands

- `./gradlew build`: compiles the Java project and runs tests.
- `./gradlew test`: runs JUnit 5 backend tests.
- `./gradlew clean`: removes Gradle build outputs.
- `cd client && npm install`: installs Angular client dependencies.
- `cd client && npm start`: starts the Angular dev server.
- `cd client && npm run build`: builds the Angular client.
- `cd client && npm test`: runs client tests.

No Gradle `application` task is defined; run `thingai.edge.aigateway.Main` from the IDE. Javalin defaults to port `8080`.

## Coding Style & Naming Conventions

Use 4-space indentation for Java. Keep packages lowercase under `thingai.edge.aigateway`, classes in `PascalCase`, methods and fields in `camelCase`, and constants in `UPPER_SNAKE_CASE`. Prefer focused classes matching package boundaries. Use Gson where the backend already does. For Angular, use strict TypeScript, standalone components, signals, and `client/AGENTS.md`.

## Architecture Boundaries

`Agent` is execution-only: no DAO or persistence logic. `AgentOrchestrator` owns session history, persistence, and multi-agent chaining. Route classes stay thin. `LlamaCppProvider` wraps the OpenAI-compatible `llama-server` HTTP API; do not add JNI llama bindings.

## Testing Guidelines

Backend tests use JUnit Jupiter. Name test classes `*Test` and methods with behavior-focused names such as `runAsyncUsesBlockingChainAndReportsEachAgentResult`. Keep tests deterministic with in-memory fakes for LLM providers, DAOs, and callbacks. Run `./gradlew test` before backend changes and `cd client && npm test` before client changes.

## Commit & Pull Request Guidelines

Git history uses short Conventional Commit-style subjects, especially `feat:` and `chore:`. Use concise imperative subjects, for example `feat: add MCP server management`. PRs should include a summary, commands run, linked issues or task files, and screenshots for UI changes.

## Security & Configuration Tips

Copy `.env.example` to `.env` and set `LLAMA_SERVER_URL`. Do not commit tokens, secrets, or machine-specific paths. Replace placeholder MCP authorization values locally only.

## Agent-Specific Instructions

Before significant changes, consult `docs/specs/project-specs.md` and relevant files under `agents/task/`. Keep searches scoped, ask for clarification on ambiguous requirements, and present a concise plan before broad implementation.
