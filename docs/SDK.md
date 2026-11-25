# SuperPI-KotlinSDK guide

A coding agent SDK for Android / JVM apps: pi's (TypeScript) `pi-ai`, `pi-agent`, and `pi-coding-agent` rewritten as a Kotlin Multiplatform library.

## Dependency

```kotlin
dependencies {
    implementation("io.github.whywood.superpi.sdk:pi-coding-agent:0.7.1")
}
```

On Android use `io.github.whywood.superpi.sdk:pi-coding-agent-android:0.7.1`. Artifacts are not published to Maven Central yet; there are two ways to get them:

**JitPack** (built per tag; the first request can be slow):

```kotlin
// settings.gradle.kts
repositories {
    maven("https://jitpack.io")
}

// modules
implementation("com.github.Sadami3066.SuperPI-KotlinSDK:pi-coding-agent:v0.7.1")        // JVM
implementation("com.github.Sadami3066.SuperPI-KotlinSDK:pi-coding-agent-android:v0.7.1") // Android
```

**Local publish**: run `./gradlew publishToMavenLocal`, then depend on `io.github.whywood.superpi.sdk:*:0.7.1`.

Modules: `pi-ai` (unified LLM layer) → `pi-agent` (agent loop) → `pi-coding-agent` (sessions/tools/prompts/SDK facade). Targets: Android (minSdk 26) + JVM (Java 17 bytecode).

## Quick start

```kotlin
val session = createAgentSession(
    AgentSessionOptions(
        cwd = workspaceDir,
        agentDir = appPrivateDir.resolve("agent"),
        transport = okHttpTransport(),
        model = "deepseek/deepseek-v4-flash",
    ),
)

val subscription = session.subscribe { event ->
    when (event) {
        is SessionEvent.AgentEventReceived -> when (val agentEvent = event.event) {
            is AgentEvent.MessageUpdate -> {
                val delta = agentEvent.assistantMessageEvent
                if (delta is TextDeltaEvent) ui.append(delta.delta)
            }
            else -> Unit
        }
        else -> Unit
    }
}

session.prompt("Summarize what's in this directory")
println(session.getLastAssistantText())
subscription()
session.close()
```

## Sessions

```kotlin
val session = createAgentSession(options)
session.prompt("...")                                   // one complete run (including retries/tool calls)
session.steer("Also check the README")                  // injected while streaming; effective after the current turn's tool calls
session.followUp("Look at the tests once this finishes") // continues after the current run ends
session.abort()                                         // cooperative abort
session.waitForIdle()
session.setModel(model, thinkingLevel = "high")
session.setThinkingLevel("medium")
session.messages                                        // current transcript (AgentMessage)
session.systemPrompt                                    // the system prompt currently in effect
session.sessionFile                                     // session JSONL path (may not exist on disk yet)
session.close()
```

Sessions persist as pi-compatible JSONL (the `~/.pi` layout) and can be read directly by pi; the API supports resume:

```kotlin
// new
val session = createAgentSession(options.copy(sessionFile = null))
// resume
val resumed = createAgentSession(options.copy(sessionFile = file))
// session runtime (automatic rebuild/switch/fork)
val runtime = AgentSessionRuntime.create(options)
runtime.newSession()
runtime.switchSession(file)
runtime.fork(entryId = null)
```

The storage root is entirely up to the host (app-private directory on Android, any directory on the JVM).

## Models and providers

- 42 built-in providers (28 OpenAI-compatible ones are usable; anthropic/responses/google and others are being filled in), with the model catalog bundled.
- Custom providers: `<agentDir>/models.json` (compatible with pi's format), or injected at runtime:

```kotlin
AgentSessionOptions(
    ...,
    providers = listOf(myProvider),
    model = "my-provider/my-model",
)
```

- Auth: API keys are read from `<agentDir>/auth.json`; a `CredentialStore` can also be injected. OAuth login flows come in later releases.

## Tools

Default tool set: `read`, `write`, `edit`, `ls`, `grep`, `find`, `bash` (bash requires an injected `ShellExecutor`; mobile builds do not provide one by default).

Custom tools:

```kotlin
val myTool = CodingTool(
    name = "weather",
    description = "Check the weather",
    parameters = buildJsonObject { put("type", "object") },
    label = "Weather",
    execute = { _, args, _, _ -> textResult("Sunny") },
)

AgentSessionOptions(..., tools = defaultCodingTools(...) + myTool)
```

## Hooks

```kotlin
CodingHooks(
    transformPrompt = { raw -> raw.trim() },
    extraSystemPromptSections = { mapOf("app_rules" to "<app_rules>...</app_rules>") },
    onSessionStart = { session -> /* ... */ },
    onSessionShutdown = { session -> /* ... */ },
)
```

`AgentSessionOptions.extraSystemPromptSections` are static sections included in every request; `initialSystemPromptSections` are written only into the session's first system snapshot, providing initial values for sections that hooks generate dynamically (environment, preferences, plugin state, and so on). The two are normally used together: the first snapshot on a new session already contains the dynamic sections, and afterwards a system patch is appended only when values change.

## Platform integration

- HTTP: implement `HttpTransport` (defaults to `okHttpTransport()`).
- Files: okio `FileSystem`, injectable (defaults to `FileSystem.SYSTEM`).
- Command execution: implement `ShellExecutor` and pass it to `AgentSessionOptions.shellExecutor`.
- Credentials: implement your own `CredentialStore` or use the default file implementation.

## MCP

See the [MCP guide](MCP.md).

## Limitations

- No CLI or interactive TUI; the host app renders the event stream.
- pi's TypeScript extensions cannot be loaded; extension points are Kotlin `CodingHooks` plus custom `CodingTool`s.
- Images are normalized to the model's `inputLimits` (dimensions/encoding/size); request bodies keep only the most recent inline images within budget (default 20 images / 20MiB of base64, adjustable via `images.requestMaxImages` / `requestMaxBytes` in `settings.json`), older images become text placeholders, and session history and JSONL keep the originals.
- `grep`/`find` are pure Kotlin implementations with no rg/fd dependency.
