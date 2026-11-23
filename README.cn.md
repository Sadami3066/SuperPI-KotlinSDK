<div align="center">

# SuperPI Kotlin SDK

**面向 Android 与 JVM 的 Kotlin Multiplatform AI Agent SDK**

<sub>基于 [pi](https://pi.dev)（`pi-ai`、`pi-agent`、`pi-mcp`、`pi-coding-agent`）的 Kotlin Multiplatform 实现，为 [SuperPI](https://github.com/Sadami3066/SuperPI) 提供核心运行时。</sub>

[English](README.md) · 简体中文

[![CI](https://github.com/Sadami3066/SuperPI-KotlinSDK/actions/workflows/ci.yml/badge.svg)](https://github.com/Sadami3066/SuperPI-KotlinSDK/actions/workflows/ci.yml)
<img src="https://img.shields.io/badge/Version-0.7.0-111111?style=flat-square" alt="Version 0.7.0" />
<img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.4.21" />
<img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT" />

</div>

---

## 模块概览

依赖方向：`pi-coding-agent` → `pi-agent` → `pi-ai`，以及 `pi-coding-agent` → `pi-mcp` → `pi-ai`。

| 模块 | 定位 | 核心能力 |
|---|---|---|
| `pi-ai` | 统一 LLM 接口与目录 | 10 种 Chat API、图像生成与分类、42 家内置服务商、9 种 OAuth、流式事件与重试计费 |
| `pi-agent` | Agent 循环引擎 | 事件生命周期、串/并行工具执行、steering / follow-up 队列、hooks 与代理传输 |
| `pi-mcp` | MCP 客户端 | Streamable HTTP + JVM stdio、基于 `tool_search` 的按需加载、Resources/Prompts、OAuth 2.1 |
| `pi-coding-agent` | 会话与编码门面 | pi 兼容 JSONL 会话、压缩、请求级图片预算管理、`AgentSession` / `createAgentSession` |

## 环境要求

- JDK 17+（`jvmToolchain(17)`）
- Android：`minSdk 26`、`compileSdk 36`、`android.useAndroidX=true`

## 安装引用

打 `vX.Y.Z` tag 时由 CI 自动构建并发布到 [`SuperPI-Maven`](https://github.com/Sadami3066/SuperPI-Maven)：

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
// Android 项目
implementation("io.github.whywood.superpi.sdk:pi-coding-agent-android:0.7.0")

// JVM 项目
implementation("io.github.whywood.superpi.sdk:pi-coding-agent-jvm:0.7.0")
```

若与 [`SuperPI`](https://github.com/Sadami3066/SuperPI) 主仓同级存放，可在主仓构建时传 `-Psuperpi.localSdk=true` 直接通过 Gradle Composite Build 联调未发布源码，或执行 `./gradlew publishToMavenLocal`。

## 五分钟上手

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

session.prompt("总结当前目录文件")
unsubscribe()
session.close()
```

## 构建、校验与文档

```bash
./gradlew build --no-daemon                    # 编译 JVM + Android 并运行全套测试
./gradlew publishAllPublicationsToPagesRepository
./scripts/release-local.sh 0.7.0               # 干净构建 + 发布校验 + Demo + 校验和
```

- [架构设计](docs/ARCHITECTURE.md) · [SDK 参考](docs/SDK.md) · [MCP 指南](docs/MCP.md) · [内部契约](docs/CONTRACTS.md) · [对外契约](docs/CONTRACTS-SDK.md) · [发版流程](docs/RELEASE.md)

## 已知边界

- 纯运行时 SDK，不含 CLI/TUI（UI 由宿主 [SuperPI](https://github.com/Sadami3066/SuperPI) 渲染）。
- Android 端仅支持 HTTP MCP（不支持 stdio 子进程）；`codemode` 映射为 `deferred` + `tool_search`。

## 开源许可

[MIT](LICENSE)（基于 [pi](https://github.com/earendil-works/pi) 的 Kotlin 重写实现）。
