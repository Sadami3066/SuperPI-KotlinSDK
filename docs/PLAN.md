# Plan

## Positioning (confirmed with the user on 2026-10-22)

The Kotlin edition of pi is a **modern, lightweight SDK** for developers building **mobile agent apps**.

This defines three boundaries:

1. **Library only, no CLI**: do not port `pi-coding-agent`'s CLI/argument parsing, interactive TUI, or print/json/rpc run modes; port only its core/SDK layer (`AgentSession`/`SessionManager`/tools/settings/auth/model resolution/compaction/extension hooks).
2. **All 42 providers**: port pi-ai's provider matrix in full, with no trimming.
3. **Mobile first**: Kotlin Multiplatform (`android` + `jvm` targets; an iOS target can be added later on macOS), with core logic in `commonMain` and no dependence on JVM-only APIs; platform capabilities such as HTTP, files, and credential storage are injected through replaceable implementations.

## Goals

| Module | Upstream | Contents |
|---|---|---|
| `pi-ai` | `packages/ai` (27,709 lines of TS) | Type/event protocol, 10 chat API implementations, 42 providers, auth/OAuth, model catalog, image/classifier, usage/cost |
| `pi-agent` | `packages/agent` (2,539 lines of TS) | Agent class, agent loop, tool execution, events, transport/proxy |
| `pi-coding-agent` | `packages/coding-agent` (76,051 lines of TS) | core/SDK only: session orchestration, session persistence, settings, auth, model resolution, tools, system prompt/resources, compaction, extension hooks |

Source analysis lives in `docs/analysis/` (4,400+ lines, including field-by-field type specs, the event protocol, a 50-item behavior fidelity checklist, and file/protocol formats).

## SDK design principles

- **Lightweight**: no Node/Bun, no CLI, no TUI, no in-process TS extensions; the core depends only on coroutines/serialization/okio, and the HTTP implementation is replaceable.
- **Modern API**: `suspend` functions + `Flow` event streams + `close()`/structured concurrency; immutable configuration + explicit lifecycle; no global singletons.
- **Embeddable**: storage root, HTTP transport, credential storage, tool set, system prompt, and extension hooks are all injectable; on Android the app-private directory is the default.
- **Platform-agnostic core**: `commonMain` uses only multiplatform APIs; `android`/`jvm` each provide the OkHttp transport and file implementation.
- **Behavior aligned with upstream**: event sequences, JSON fields, error text, and session format stay consistent with pi (comparable and interoperable).

## Milestones

### M0 Infrastructure and research (done)

- Multi-module Gradle + Kotlin/JVM project with `./gradlew build` fully green
- pi-ai base types (Message/Content/Usage/Model/Tool/events) + serialization + event streams
- 1.3.0 model catalog data (42 providers / 1,714 entries) + loader + tests
- Source analysis docs for the three packages (pi-ai / pi-agent / pi-coding-agent + format specs)

### M1 Project converted to a KMP SDK (done)

- Kotlin Multiplatform: `commonMain` + `jvm` + `android` (AGP 9.4.1, Kotlin 2.4.21, minSdk 26), with an `okhttpMain` shared source set
- Model catalog embedded as a single gzip resource (about 68KB of source, 50KB binary), reproducible via `scripts/vendor-model-data.sh` + `generate-catalog.py`
- Acceptance: `./gradlew build` fully green; both jvmTest and testAndroidHostTest pass

### M2 pi-ai core protocol (done)

1. SSE parsing, incremental partial JSON parsing
2. Transcript normalization: normalizeContext, system/tool declaration replay, orphan tool calls, image downgrade
3. JSON Schema tool argument validation (error text matches upstream)
4. retry/abort/error normalization/context overflow detection
5. `Models`/`Provider` registry + api key auth + `calculateCost`/thinking level + 42 built-in providers
6. `openai-completions` (28 providers usable; all 28 compat fields supported)
7. faux provider + MockWebServer fixture tests

Acceptance: pi-ai 250 JVM tests + 193 Android host tests, pi-agent 113 JVM tests + 113 Android host tests all green; `./gradlew clean build` green for three modules across both targets.

### M3 Remaining pi-ai providers

`openai-completions` now covers 28 providers; the other 13 API implementations are still placeholders.

`anthropic-messages`, `openai-responses`, `azure`, `google-generative-ai`, `google-vertex`, `mistral-conversations`, `bedrock-converse-stream`, `openai-codex-responses`, `pi-messages`, image/classifier APIs; 9 OAuth flows (implemented in mobile-friendly ways: external browser + callback/device code).

### M4 pi-agent (done)

AgentMessage/AgentEvent, two-layer agent loop, parallel/serial tool execution, hooks, queues/interrupt, transport/proxy.

Acceptance: 113 tests all green (B01–B13 and B15–B50 each have direct assertions, B14 is implemented); plus a cross-module e2e test: Agent → pi-ai Models → faux provider full path.

### M5 pi-coding-agent SDK layer (main work done, wrapping up)

Done:
- `SessionManager`: session JSONL v1-v3 read/write, deferred file creation, tree/branch/fork, context_edit projection, compaction-aware rebuild, stats, list/resume (55 tests)
- `SettingsManager` + `TrustManager` + `Migrations`: two-level merge, dirty-field write-back, defaultTools modifiers, trust.json, commands→prompts migration (42 tests)
- `ModelRuntime` / `ProviderComposer` / `ModelResolver`: models.json custom providers, apiKey resolution, model resolution and thinking suffix (47 tests)
- Tools: read/write/edit/ls + grep/find (pure Kotlin, no rg/fd dependency) + bash (injected ShellExecutor) + truncate/path/mutation queue (95 tests)
- Prompts: SystemPrompt sections/defaults/diff, ResourceLoader (AGENTS.md/SYSTEM.md/skills/prompts), skills XML, prompt templates (62 tests)
- `AgentSession` + `createAgentSession` + `AgentSessionRuntime` + `CodingHooks` (lead implementation, public SDK API)
- End-to-end validation: Agent → StreamFn → ModelRuntime → faux provider; session persist/reopen resume (SdkIntegrationTest)

Acceptance: `./gradlew clean build` green for all three modules; pi-coding-agent 304 JVM tests + 304 Android host tests.

Wrap-up items completed:
- compaction + branch summary port (`coding/compaction`, 56 tests); wired into AgentSession: automatic compaction before prompt, automatic compaction recovery on context overflow, public `session.compact()` API
- Session-level automatic retry (exponential backoff, context_edit omits the failed turn, retry events)
- `UnixShellExecutor` (shared JVM/Android source set; ProcessBuilder, timeout/abort kills the process tree, tail truncation, 13 tests)
- SDK developer docs `docs/SDK.md`

Done:
- `AgentSessionRuntime.fork(entryId, summarizeAbandonedBranch = true)` wired to branch summary
- Maven publishing configuration (`maven-publish`; `publishToMavenLocal` produces jvm jar/android aar/sources/pom with correct dependencies)

Done:
- Example projects: `examples/jvm-demo` (Gradle application, `./gradlew :examples:jvm-demo:run` prints a streaming reply end-to-end and writes a session file), `examples/android-demo` (standalone Gradle project consuming the android variant published to mavenLocal, producing a debug APK)
- The root build no longer declares plugin versions; each module/example declares its own (resolving the classpath conflict between AGP 9 application and KMP); the Android demo uses the classic DSL + kotlin-android 2.4.21

Remaining:
- Extension hook refinements (custom message persistence, input flow)

### M3 provider matrix (done)

**All 10 chat APIs implemented**: openai-completions, openai-responses, openai-codex-responses, azure-openai-responses, anthropic-messages, google-generative-ai, google-vertex, mistral-conversations, bedrock-converse-stream (AWS SigV4 + binary event stream), pi-messages (radius gateway).

**Images/classifiers**: openrouter-images; typesafe-system-one, openai-decisions, cloudflare-workers-ai-system-one; `generateImages`/`classify` dispatch in the Models layer.

**All 9 OAuth flows implemented**: anthropic, openai-chatgpt, github-copilot, openai-codex, xai, openrouter, kimi-coding, meta, radius. Infrastructure (pure Kotlin SHA-256/PKCE, device code polling, local callback server, credential refresh write-back) is complete and wired into the builtinProviders auth chain (OAuth → auth.json api key → env).

All 42 catalog providers can be routed (typesafe is classifier-only).

Known deviations: codex WebSocket transport/zstd compression not implemented (falls back to SSE); bedrock credential chain is env/bearer only (no profile/IMDS/STS); radius dynamic catalog is interface-only.

### M6 Polish and release (main work done)

- Maven publishing configuration (jvm jar / android aar / sources / pom), `publishToMavenLocal` works
- Example projects: `examples/jvm-demo` (`./gradlew :examples:jvm-demo:run` works end-to-end), `examples/android-demo` (standalone Gradle project consuming published artifacts; `scripts/build-android-demo.sh` produces a debug APK)
- SDK usage docs `docs/SDK.md`
- Optional remaining: full KDoc, R8/size and startup-time measurements, private repository publishing

## Final acceptance (review fix round, 0.1.0)

This round fixes and verifies the 14 P1/P2 items and completion-boundary issues raised by an external review:

| Review item | Fix | Verification |
|---|---|---|
| P1.1 OAuth refresh bypassed | Unified auth chain in pi-ai (`ProviderAuthResolver`/`resolveAuthForProvider`): stored OAuth → refresh when expired → `toAuth()`; coding `ModelRuntime` no longer pre-fills credentials | pi-ai `ProviderAuthChainTest` 9 tests (refresh request/write-back/headers); coding `AuthScenarioTest` 2 tests (end-to-end through the SDK) |
| P1.2 abort/waitForIdle/close did not cover the whole operation | Session-level `RunSignal` covers retry waits/compaction/summarization; `waitForIdle` waits for the entire prompt operation; `close()` is suspend and waits for the operation to finish, running the shutdown hook before unsubscribing/closing the session; Runtime provides a suspend `close()` | `SessionLifecycleScenarioTest` 3 tests (aborting during a retry wait sends no second request; waitForIdle covers retries and the whole operation) |
| P1.3 Overflow recovery lacked omitFailedTurn/attempt bounds | The overflow branch first calls `omitFailedTurn` (context_edit) + rebuilds context, then compacts automatically once (split-turn allows both history and turn-prefix summaries), then continues the run | `OverflowRecoveryScenarioTest` 2 tests; "Cannot continue from message role: assistant" no longer occurs |
| P1.4 Android demo threading bug | Demo: all session/prompt work on IO, UI via Channel→Main; suspend close in onDestroy; added offline faux / local mock modes | Real device (Xiaomi rubypro/Android 13): offline + `adb reverse` mock + destroy/relaunch; logcat shows 0 crashes/ANR/NetworkOnMain, no StrictMode violations in app frames; device evidence kept locally (not published with the public repository) |
| P1.5 Custom providers only supported openai-completions | pi-ai `chatApiFactory` covers all 10 chat APIs; `ProviderComposer` wired in | `ProtocolFactoriesTest` 10 APIs; a coding custom provider makes real requests to anthropic/openai-responses endpoints |
| P1.6 Cloudflare not adapted | Added `CloudflareStream`/`CloudflareAuth`: URL placeholder substitution, workers-ai Bearer, ai-gateway `cf-aig-authorization` (gateway token takes precedence) | `CloudflareProvidersTest` 7 tests (MockWebServer URL/headers) |
| P1.7 defaultTools had no effect | `createAgentSession` filters the default tool set with `settings.getDefaultTools()` | `SessionConfigScenarioTest` (defaultTools/add-remove modifiers) |
| P1.8 Static extra sections were lost on the first prompt | `AgentSession` stores `staticSystemPromptSections` and includes them in every sections computation | `SessionConfigScenarioTest`, visible in the first request |
| P2.9 System messages were not persisted | Both the initial system snapshot and patches are written to JSONL; on resume, the sections baseline is replayed from historical system messages | `SessionContextScenarioTest` (JSONL system entries/consistency after reopening) |
| P2.10 Model/thinking semantics were lost | Explicit `options.model` takes precedence; the `:thinking` suffix and `modelThinking`/default thinking participate in resolution | `SessionConfigScenarioTest` |
| P2.11 Event subscription could drop events | `MutableSharedFlow(SUSPEND, buffer=256)` + suspend `emit`; `subscribe` collects immediately with `UNDISPATCHED` | `SessionContextScenarioTest`, full sequence verified only through `session.events` |
| P2.12 grep/find ignore semantics | Added `GitIgnoreMatcher` (nested/negation/directory/anchored/glob); streaming early stop on limit + RunSignal cancellation | 44 tests (tools + matcher + early stop/cancellation) |
| P2.13 Validation subset | Added numeric/string/array constraints, local `$ref`/`$defs`, 21 formats; error text compared verbatim against TypeBox 1.3.27 goldens | ValidationTest 35 tests (60 golden assertions) |
| P2.14 PKCE randomness source | `oauthRandomBytes` defaults to `platformSecureRandomBytes` (`java.security.SecureRandom`) | PkceTest + PlatformAbstractionTest |
| Platform leakage | Platform differences converge in `io.github.whywood.superpi.sdk.ai.platform` (time/random/env/atomic); commonMain has no `java.*` imports | grep check + `PlatformAbstractionTest` |
| Release inconsistency | Version unified at `0.1.0`; `jvmToolchain(17)` artifacts have class major=61; bilingual README, LICENSE, RELEASE docs | `scripts/verify-release.mjs` 32/32 PASS; `scripts/release-local.sh` full run passes |

Test gaps were filled at the same time: fixed JSONL/protocol fixtures (hand-written in TS format, runnable by default), real-data scanning moved behind the `PI2K_REAL_SESSIONS_DIR` opt-in, and 18 SDK combination scenarios.

## Second review round (0.1.1)

Fixes the eight issues an external review raised on `b520ce2` / `v0.1.0`, one by one (the Android demo device item is left as-is per the user's request):

| Item | Problem | Fix | Verification |
|---|---|---|---|
| P1 | Cloudflare system environment variables did not reach URL substitution | `ResolvedAuth.env` carries the account/gateway/token resolved during auth; Models does resolveAuth → merge env (caller wins) → transformModel → dispatch | Added 3 "system environment variables only" cases (workers-ai / gateway URL + headers); pi-ai 628 tests |
| P1 | Manual `compact()` was not registered as a session operation; the fork branch summary had a null signal | Added `runOperation`: prompt / manual compact both register activeOperation + RunSignal (fully covers busy/abort/waitForIdle/close); when busy, manual compaction first aborts + waits for idle; the fork branch summary gets its own RunSignal bound to coroutine cancellation, and `finally` closes the source session | `OperationLifecycleScenarioTest`: isBusy during compaction, waitForIdle waits, abort returns null with exactly one Started and one Failed, no Finished |
| P1 | The completion signal came after a cancellable emit, causing a permanent hang under backpressure | In `finally`, call `operation.complete()` first, then send Settled with `NonCancellable + withTimeoutOrNull(5s)` | With a slow subscriber, a full buffer, and a cancelled prompt, `waitForIdle()` returns within the timeout |
| P2 | Subdirectory searches did not inherit parent .gitignore | The ignore hierarchy root is now cwd (when searchPath is inside cwd); a searchPath outside cwd still uses itself as root | Added 3 inheritance cases + out-of-cwd root semantics; 51 tools tests |
| P2 | Default model resolution dropped the thinking suffix | `resolveDefault()` resolves only once, and its thinkingLevel participates in the precedence chain | With `enabledModels:["faux/faux-1:high"]` and no explicit model → `thinkingLevel=="high"` |
| P2 | Threshold compaction emitted duplicate Finished and Started too late | Started is emitted before the summary; Finished/Failed are emitted once in `compactInternal`; `applyCompaction` no longer emits events | Threshold compaction case asserts Started=1 / Finished=1 / Failed=0, CompactionEntry=1 |
| P2 | retryAttempt leaked into the next turn after aborting a retry | `runOperation` resets retryAttempt on both entry and exit | Abort during the first turn's retry wait; the second turn still retries once and succeeds |
| — | grep silently skipped files >16MiB and read whole files | Changed to streaming line-by-line reads + a ring buffer for context; skip counting, an explicit skipped notice when there are no matches; overlapping context merged | Large-file skip notice / matching case unaffected; cross-line overlap merge case |
| — | `verify-release.mjs` used a wrong sources path and only checked the first class | Fixed the sources jar path and added an Android sources check; sampled/full class major checks | 0.1.1 release verification 35/35 PASS |

## MCP 0.2.0 (implemented)

| Capability | Implementation |
|---|---|
| `pi-mcp` module | JSON-RPC 2.0 codec, McpClient (initialize/paginated tools/resources/prompts/call/ping/notifications/cancel/timeouts), SSE parsing, Streamable HTTP (session id, protocol version, GET notification stream, Last-Event-ID reconnect, DELETE), JVM stdio (process cleanup), OAuth 2.1 (discovery/DCR/PKCE/refresh/step-up/token persistence/loopback) |
| Configuration | `<agentDir>/mcp.json` + trusted project `.pi/mcp.json`; `mcpServers` compatible with pi; stdio/http, exposure/toolExposure, timeout, oauth; `${ENV}` interpolation; `!cmd` rejected; project entries can only override enabled/exposure/toolExposure |
| Tools | `mcp__<server>__<tool>` naming with conflict hashing; direct/deferred/codemode(→deferred)/hidden; `tool_search` (BM25 + Chinese 2-gram; results written to `mcp_loaded_tools` and effective on the next turn of the same run); result mapping for text/image/audio/resource, 20KB truncation, structuredContent, progress→onUpdate, isError |
| Resources | Three resource tools (single/multi-server aggregation), binaries written to disk |
| prompts | `listPrompts`/`promptMcp`/`/mcp__server__prompt` |
| Runtime | `session.mcp`: server status, add/remove/reconnect/reload/setEnabled/healthCheck/tools/prompts/readResource/signIn; SessionEvent gains MCP status/tool/log/sign-in events |
| pi-agent | `AgentOptions/AgentLoopConfig.getActiveTools`, refreshing tools each turn and writing toolsAdded/Removed patches, supporting deferred tool loading during a run |
| Tests | pi-mcp 141 (protocol/HTTP/stdio/OAuth); coding gains adapter/resources/tool_search/prompts/manager/end-to-end scenarios; Android host compilation + pure protocol tests |
| Examples/docs | `examples/mcp-demo` (local mock MCP server + deferred + tool_search), `docs/MCP.md`, README in both languages |
