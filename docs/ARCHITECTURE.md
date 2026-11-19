# Architecture

## Positioning

pi2kotlin is a Kotlin Multiplatform SDK for building **mobile agent apps**: it rewrites pi's `pi-ai`, `pi-agent`, and `pi-coding-agent` (core/SDK layer) as Kotlin libraries, with no CLI/TUI.

## Technology choices

| Item | Choice | Rationale |
|---|---|---|
| Form | Kotlin Multiplatform library | Android first, JVM for tests/server; iOS can be added as a target later |
| Targets | `androidTarget` (minSdk 26) + `jvm` (JDK 17) | Mobile agent apps + JVM tests/desktop reuse; iOS is not wired up yet (adding a target requires `io.github.whywood.deepdeep.sdk.ai.platform` actuals plus platform HTTP/file implementations) |
| Build | Gradle 9.8.2 + Kotlin 2.4.21 + AGP 9.x | The existing project is already set up |
| JSON | kotlinx.serialization | Native to KMP; sealed discriminator keys match upstream JSON |
| Concurrency/streaming | kotlinx.coroutines (Flow + Channel) | Native to KMP; maps to upstream async iterator/Promise |
| Files | okio | KMP and Android friendly |
| HTTP | Custom `HttpTransport` interface + OkHttp implementation | Core stays platform-agnostic; the implementation can be swapped for Ktor/Darwin |
| Tests | kotlin.test + kotlinx-coroutines-test + MockWebServer | commonTest runs the logic; platform implementations are tested separately |

No upstream npm dependencies are pulled in (openai/anthropic/google SDK, typebox, undici, jiti, etc.); wire protocols are hand-written from upstream source.

## Modules

```
pi-ai/            Unified LLM API: type model, event streams, model catalog, provider registration, API protocol implementations, auth
pi-agent/         Agent runtime: Agent class, agent loop, tool execution, events, transport
pi-coding-agent/  Coding agent SDK: AgentSession, session persistence, settings, model resolution, tools, system prompt, compaction, extension hooks
```

Dependency direction: `pi-coding-agent` → `pi-agent` → `pi-ai`.

## KMP source layout (per module)

```
src/commonMain/kotlin/      Pure Kotlin logic and types (all protocol parsing, agent logic, validation, compaction…)
src/commonTest/kotlin/      Shared tests
src/jvmMain/kotlin/         JVM platform implementations (OkHttp transport, files, credentials, io.github.whywood.deepdeep.sdk.ai.platform actuals)
src/androidMain/kotlin/     Android platform implementations (same as above)
```

The platform boundary converges on expect/actual in `io.github.whywood.deepdeep.sdk.ai.platform` (`platformCurrentTimeMillis`, `platformSecureRandomBytes`, `platformEnv`, `PlatformAtomicBoolean`; the actuals live in `pi-ai/src/jvmMain` and `pi-ai/src/androidMain`, and pi-agent reuses them via `api(project(":pi-ai"))`), plus interface injection (`HttpTransport`, `FileSystem`, `CredentialStore`, `ModelCatalogSource`). They are injected when constructing `PiClient`/`AgentSession`; default factories are provided in the platform source sets (Android defaults to OkHttp + app-private directory).

The current targets are android + jvm only. OS-specific differences are allowed only in `io.github.whywood.deepdeep.sdk.ai.platform` and the platform source sets; `commonMain` does not use `java.*`/`javax.*`/`System.*`. Elapsed-time measurement uses `kotlin.time.TimeSource.Monotonic` directly and does not enter the platform abstraction. Adding an iOS target requires actuals for all the expect declarations above (otherwise commonMain will not compile) and platform HTTP/file implementations; iOS is not claimed as supported today.

## Key design decisions

### JSON conventions

- Field names match the upstream in-memory/session format exactly (camelCase); `PiJson` sets `encodeDefaults=true`, `explicitNulls=false`, `ignoreUnknownKeys=true`: TS `undefined` is not written, `null` fields are not persisted, and future fields remain readable.
- Unions: `@Serializable` sealed interface + `@JsonClassDiscriminator` (`Message`→role, `Content`→type, `AssistantMessageEvent`→type).
- Arbitrary JSON (tool arguments, details, compat) uses `JsonObject`; wire DTOs are kept separate from in-memory types (snake_case conversion happens inside API implementations).

### Event streams

`AssistantMessageEventStream` = a `Channel(UNLIMITED)` event stream + a `CompletableDeferred<AssistantMessage>` result + `durationMs` timing, corresponding to upstream `EventStream`. `push` does not suspend; `done`/`error` terminate the stream and complete the result.

The public SDK layer does not expose the push side, only `Flow<AgentEvent>` and `suspend fun prompt()`.

### Message mutability

Upstream streaming partials are shared live objects (text blocks grow with deltas). `AssistantMessage` and content blocks use mutable fields, and `partial` in events references the same instance; coding-agent relies on stable message object identity (fields replaced in place).

### Cancellation

An explicit `RunSignal` token (`abort()` + callback + `isAborted`) replaces `AbortSignal`; coroutine cancellation is not used: upstream abort is cooperative, and cancelling the coroutine would throw `CancellationException` and break the "resolve normally after abort" semantics.

### Tool argument validation

pi-ai provides `validateToolArguments(tool, args)`: it returns normalized arguments on success and throws `ToolArgumentValidationException` on failure, with error text verbatim from upstream (pi-agent catches it and turns it into an error tool result).

## pi-ai internal layering

```
types          data model + serialization
event-stream   AssistantMessageEventStream
models         model catalog + Models registry + provider factories + cost
api/*          wire protocol implementations (openai-completions, anthropic-messages, openai-responses, …)
auth           API key resolution, OAuth flows, credential storage (injectable interfaces)
http           HttpTransport abstraction + SSE parsing + retry
utils          partial JSON, transcript normalization, JSON Schema validation, usage/overflow
```

## Model catalog

The 1.3.0 catalog (42 providers / 1,714 entries) is built in as a build-time generated resource to avoid runtime network access; the `ModelCatalogSource` interface lets hosts supply custom or remote catalogs (corresponding to upstream models.store and the pi.dev overlay, deferred). The generation script `scripts/vendor-model-data.sh` extracts the data from the npm tarball and is reproducible.

## Mobile constraints

- `commonMain` forbids `java.*`/`javax.*` imports and `System.*`: wall-clock time uses `platformCurrentTimeMillis()`, elapsed time uses `kotlin.time.TimeSource.Monotonic`, randomness uses `platformSecureRandomBytes()`, environment variables use `platformEnv()`, and atomic booleans use `PlatformAtomicBoolean`.
- okio `FileSystem.SYSTEM` is an okio multiplatform API (available on JVM/Android/Native); adding a JS/Wasm target would require replacing it.
- Credential storage: the default implementation is injectable; on Android the host decides whether to use Keystore encryption.
- Storage root: passed at construction (app-private directory by default on Android); `~/.pi` is not hard-coded.
- Dependency size: only coroutines/serialization/okio plus a platform HTTP implementation; no large SDKs.
- Tools: file tools work inside the app sandbox; the `bash` tool is injectable and can be disabled (disabled by default on mobile).

## Isomorphism strategy

Each Kotlin module maps one-to-one to upstream files and concepts (same concepts, same event sequences, same JSON fields), so upstream bug fixes can be ported one by one and upstream tests translated one by one.
