<div align="center">

# SuperPI Kotlin SDK

**Kotlin Multiplatform SDK for AI Agent Apps on Android & JVM**

<sub>Clean-room Kotlin Multiplatform port of [pi](https://pi.dev) (`pi-ai`, `pi-agent`, `pi-mcp`, `pi-coding-agent`) powering [SuperPI](https://github.com/Sadami3066/SuperPI).</sub>

English · [简体中文](README.cn.md)

[![CI](https://github.com/Sadami3066/SuperPI-KotlinSDK/actions/workflows/ci.yml/badge.svg)](https://github.com/Sadami3066/SuperPI-KotlinSDK/actions/workflows/ci.yml)
<img src="https://img.shields.io/badge/Version-0.7.0-111111?style=flat-square" alt="Version 0.7.0" />
<img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.4.21" />
<img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT" />

</div>

---

## Modules

Dependency direction: `pi-coding-agent` → `pi-agent` → `pi-ai`, and `pi-coding-agent` → `pi-mcp` → `pi-ai`.

| Module | Scope | Highlights |
|---|---|---|
| `pi-ai` | LLM Transport & Catalog | 10 chat APIs, image generation/classifier, 42 built-in providers, 9 OAuth flows, streaming, retries, usage/cost |
| `pi-agent` | Agent Runtime | Stateful loop, serial/parallel tool execution, steering & follow-up queues, hooks, proxy transport |
| `pi-mcp` | MCP Client | Streamable HTTP + JVM stdio, deferred tools via `tool_search`, resources, prompts, OAuth 2.1 |
| `pi-coding-agent` | Coding & Session Facade | pi-compatible JSONL sessions, settings, compaction, image budgeting, `AgentSession` / `createAgentSession` |

## Requirements

- JDK 17+ (`jvmToolchain(17)`)
- Android `minSdk 26`, `compileSdk 36`, `android.useAndroidX=true`

## Installation

Artifacts are published automatically on `vX.Y.Z` tags to [`SuperPI-Maven`](https://github.com/Sadami3066/SuperPI-Maven):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://sadami3066.github.io/SuperPI-Maven") {
            content { includeGroup("io.github.whywood.superpi.sdk") }
        }
        google()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts (Android)
implementation("io.github.whywood.superpi.sdk:pi-coding-agent-android:0.7.0")

// build.gradle.kts (JVM)
implementation("io.github.whywood.superpi.sdk:pi-coding-agent-jvm:0.7.0")
```

To link against a local sibling checkout from [`SuperPI`](https://github.com/Sadami3066/SuperPI), pass `-Psuperpi.localSdk=true` (Gradle composite build) or run `./gradlew publishToMavenLocal`.

## 5-Minute Quick Start

```kotlin
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.ai.http.okHttpTransport
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.coding.AgentSessionOptions
import io.github.whywood.superpi.sdk.coding.createAgentSession
import io.github.whywood.superpi.sdk.coding.session.SessionEvent

val session = createAgentSession(
    AgentSessionOptions(
        cwd = workspaceDir,
        agentDir = agentDir,
        transport = okHttpTransport(),
        model = "deepseek/deepseek-v4-flash",
    ),
)

val unsubscribe = session.subscribe { event ->
    if (event is SessionEvent.AgentEventReceived) {
        val update = (event.event as? AgentEvent.MessageUpdate)?.assistantMessageEvent
        if (update is TextDeltaEvent) print(update.delta)
    }
}

session.prompt("Summarize the files in this directory")
unsubscribe()
session.close()
```

## Build, Verify & Docs

```bash
./gradlew build --no-daemon                    # compile JVM + Android and run all tests
./gradlew publishAllPublicationsToPagesRepository
./scripts/release-local.sh 0.7.0               # full verification + demos + checksum manifest
```

- [Architecture](docs/ARCHITECTURE.md) · [SDK Reference](docs/SDK.md) · [MCP Guide](docs/MCP.md) · [Contracts](docs/CONTRACTS.md) · [SDK Contracts](docs/CONTRACTS-SDK.md) · [Release Guide](docs/RELEASE.md)

## Known Scope Boundaries

- Headless SDK (no CLI/TUI); rendering belongs to the host app ([`SuperPI`](https://github.com/Sadami3066/SuperPI)).
- Android supports HTTP MCP servers (no stdio subprocesses); codemode scripts map to deferred tools + `tool_search`.

## License

[MIT](LICENSE) (Kotlin rewrite of [pi](https://github.com/earendil-works/pi)).
