# MCP integration guide

pi2kotlin has shipped an embedded MCP (Model Context Protocol) client since 0.2.0; 0.2.1 added sampling / elicitation / roots, bringing third-party MCP server tools, resources, and prompts into agent sessions.

## Supported scope

| Capability | Status |
|---|---|
| Streamable HTTP transport (POST/JSON+SSE, `Mcp-Session-Id`, protocol version negotiation, GET notification stream, Last-Event-ID resume, reconnect after disconnects, DELETE shutdown) | Supported |
| stdio transport | JVM only (Android has no subprocesses; the config is marked `Unsupported`) |
| Tools: direct / deferred / codemode / hidden exposure, per-tool `toolExposure` overrides | Supported (codemode maps to deferred + tool_search; no JS scripts) |
| `tool_search` (BM25 + Chinese 2-gram) | Supported |
| resources: `list_mcp_resources` / `list_mcp_resource_templates` / `read_mcp_resource` | Supported |
| prompts: list/get, `session.promptMcp(...)`, `/mcp__<server>__<prompt>` slash commands | Supported |
| OAuth 2.1: protected resource discovery, RFC 8414/OIDC, DCR, PKCE, refresh, scope step-up, token persistence | Supported (CIMD not supported) |
| `tools/list_changed`, log/progress notifications | Supported |
| roots (workspace root declarations, `roots/list_changed`) | Supported (returns `cwd` by default; configurable/updatable) |
| sampling (server asks the host to generate with the current model) | Supported (enabled by default; disable with `samplingEnabled = false`) |
| elicitation (server asks the host to collect user input) | Supported (requires a host `elicitationHandler`; the capability is not declared without one) |
| resource subscriptions / completion / CIMD | Not implemented |
| legacy HTTP+SSE transport | Not supported (`type:"sse"` config errors) |

## Quick start

```kotlin
import io.github.whywood.deepdeep.sdk.coding.mcp.McpOptions
import io.github.whywood.deepdeep.sdk.mcp.config.McpExposure
import io.github.whywood.deepdeep.sdk.mcp.config.McpServerConfig

val session = createAgentSession(
    AgentSessionOptions(
        cwd = workspaceDir,
        agentDir = agentDir,
        transport = okHttpTransport(),
        model = "deepseek/deepseek-v4-flash",
        mcp = McpOptions(
            servers = mapOf(
                "filesystem" to McpServerConfig.Stdio(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-filesystem", workspaceDir.toString()),
                    exposure = McpExposure.Direct,
                ),
                "docs" to McpServerConfig.Http(
                    url = "https://mcp.example.com/mcp",
                    headers = mapOf("Authorization" to "Bearer ${System.getenv("DOCS_TOKEN")}"),
                ),
            ),
        ),
    ),
)
```

When `McpOptions.servers` is not passed, configuration is read from:

1. `<agentDir>/mcp.json` (global)
2. `<cwd>/.pi/mcp.json` (only when the project is trusted; controlled by `settings.json` or `AgentSessionOptions.projectTrusted`)

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "."],
      "env": { "FOO": "${FOO}" },
      "cwd": ".",
      "enabled": true,
      "timeout": 60,
      "exposure": "deferred"
    },
    "internal-docs": {
      "url": "https://mcp.example.com/mcp",
      "headers": { "Authorization": "Bearer ${DOCS_TOKEN}" },
      "oauth": { "scope": "mcp:read", "clientId": "..." }
    }
  }
}
```

Fields:

- `type`: `stdio` or `http` (inferred from `command`/`url` when omitted; `streamable-http` is an alias; `sse` is not supported)
- `command` / `args` / `env` / `cwd`: stdio
- `url` / `headers` / `oauth` / `auth.provider`: HTTP
- `enabled`: `true` by default
- `timeout`: per-request timeout in seconds, default `60`
- `description`: a one-line description shown to the model
- `exposure`: `direct` / `deferred` (default) / `codemode` (equivalent to deferred) / `hidden`
- `toolExposure`: overrides a single tool's exposure by tool name or `*` glob

`${VAR}` is interpolated from environment variables; a missing variable is an error and the server is skipped. A value of `!command` is rejected and not executed (security restriction).

A project `mcp.json` can only override `enabled` / `exposure` / `toolExposure` for global servers with the same name; it cannot define new servers, commands, or credentials.

## Tool exposure and tool_search

- `direct`: declared to the model immediately after connecting and called like a built-in tool.
- `deferred` (default): tools are not declared up front; the model searches with `tool_search` first, and matches are declared and callable in the **next model request**; matches are also written to the custom session entry `mcp_loaded_tools`, so they stay loaded after a session resume.
- `codemode`: upstream pi uses this to expose tools to JS scripts; this SDK has no JS sandbox and treats it as `deferred`.
- `hidden`: registered but not exposed.

`tool_search` ranks with BM25 and handles mixed Chinese/English queries (Chinese is tokenized into 2-grams). It is added to the session automatically when a deferred server exists.

## Resources

When a connection succeeds and the server declares the resources capability, these are registered automatically:

- `list_mcp_resources` (`server`, `cursor`)
- `list_mcp_resource_templates`
- `read_mcp_resource` (`uri` required, `server` optional)

Lists merge across servers; `read` requires a `server` (can be omitted when there is only one). Text and images are returned directly; binaries are written to the configured directory.

The host can also call:

```kotlin
session.mcp.readResource("internal-docs", "file:///handbook.md")
```

## Prompts

```kotlin
val prompts = session.mcp.listPrompts()          // all servers
session.promptMcp("demo", "greet", mapOf("who" to "world"))
```

You can also write slash commands directly in `prompt`: `/mcp__demo__greet world`.

## OAuth

When an HTTP server has no `Authorization` configured, or has `oauth` configured, a 401 triggers the OAuth flow:

```kotlin
val result = session.mcp.signIn(
    server = "internal-docs",
    redirectUri = "http://127.0.0.1:33418/callback", // on JVM, pi-mcp's LoopbackCallbackServer is available
    interaction = object : McpOAuthInteraction {
        override suspend fun authorize(url: String, redirectUri: String): String {
            // Android: open Custom Tabs / a browser, wait for the deep link callback, and return the full callback URL
            return awaitBrowserCallback(url, redirectUri)
        }
    },
)
```

- Tokens persist to `<agentDir>/mcp-auth.json` (atomic write, 0600).
- Automatic refresh (30 seconds early), one retry on 401, and step-up on 403 `insufficient_scope`.
- On JVM, `io.github.whywood.deepdeep.sdk.mcp.oauth.LoopbackCallbackServer` provides a local callback; on Android the host supplies the browser callback (see `examples/android-demo`).
- `auth.provider` (reusing an SDK provider token you are already signed in with) currently **only parses the config and is not wired up**; do not rely on it.

## roots / sampling / elicitation

These three are MCP client capabilities, controlled by `McpOptions`:

```kotlin
McpOptions(
    // roots: returns file://<cwd> by default; can be set explicitly and updated at runtime
    roots = listOf(McpRoot(uri = "file:///data/user/0/app/files/workspace", name = "workspace")),
    rootsEnabled = true,

    // sampling: the server calls the host model; enabled by default, can be disabled
    samplingEnabled = true,
    samplingMaxTokens = 4096,
    samplingTemperature = null,

    // elicitation: the server asks for user input, implemented by the host UI
    elicitationHandler = { request ->
        // request.message is the prompt text, request.requestedSchema is the JSON Schema form
        val accepted = showForm(request.message, request.requestedSchema)
        McpElicitationResult(
            action = if (accepted != null) "accept" else "decline",
            content = accepted,
        )
    },
)
```

Behavior and caveats:

- **roots**: `roots/list` returns the configured roots; with `rootsEnabled=false` the capability is not declared. Runtime update:
  ```kotlin
  session.mcp.setRoots(listOf(McpRoot("file:///new-workspace")))
  ```
  This automatically sends `notifications/roots/list_changed`.
- **sampling**: when the server sends `sampling/createMessage`, the SDK makes one non-streaming request with the **current session model** (`maxTokens` is min(server request value, `samplingMaxTokens`, model limit)), supports text/image input, and returns text/image. This is a real model call and **consumes tokens**; set `samplingEnabled = false` when you don't need it (the capability is then not declared and servers will not ask). Sampling requests happen during tool execution and do not advance the agent session's turns.
- **elicitation**: the capability is declared only when an `elicitationHandler` is provided. The handler receives `message` + `requestedSchema` and returns an `action` of `"accept"` (optionally with schema-conforming `content`), `"decline"`, or `"cancel"`. Without a handler the capability is not declared; a server that still insists receives `-32601`. Android/JVM UI is implemented by the host; the SDK never opens dialogs.
- None of the three affects normal MCP tools/resources/prompts.

## Runtime management

```kotlin
session.mcp.servers                              // List<McpServerStatus>
session.mcp.reconnect("docs")
session.mcp.reload()                             // re-read mcp.json and diff
session.mcp.addServer("extra", config)
session.mcp.removeServer("extra")
session.mcp.setEnabled("docs", enabled = false)
session.mcp.healthCheck()
session.mcp.listPrompts(); session.mcp.tools(); session.mcp.readResource(...)
```

Events:

```kotlin
session.events.collect { event ->
    when (event) {
        is SessionEvent.McpServerStatusChanged -> { /* Connecting/Connected/AuthRequired/Failed/Unsupported/Closed */ }
        is SessionEvent.McpToolsChanged -> { }
        is SessionEvent.McpLog -> { }
        is SessionEvent.McpSignInRequired -> { }
        else -> Unit
    }
}
```

`session.mcp.servers` exposes `state`, `toolCount`, `serverInfo`, `instructions`, and `lastError`.

## Platform differences

- Android: HTTP servers only; a stdio config yields `Unsupported` status and an explicit error. Reaching a local HTTP server requires the host to allow cleartext (debug only) or use https.
- JVM: HTTP + stdio both work. When a stdio server shuts down, its subprocess is cleaned up (SIGTERM → SIGKILL).
- The first `createAgentSession` connects all enabled servers in parallel and waits at most `McpOptions.connectTimeoutMs` (default 10 seconds); timed-out/failed servers do not block the session, and their status and errors surface through events.

## Security and limitations

- Project config is read only when the project is trusted, and it can only override `enabled`/`exposure`/`toolExposure`.
- `!command` interpolation is not executed; HTTP requires https (loopback excepted).
- Output is truncated in the middle at 20KB by default (the full text can be written to disk), a single SSE event is capped at 16MB, and each request has a 60-second default timeout.
- Not supported: codemode JS sandbox, legacy SSE, CIMD, resource subscriptions, completion, and UI resources other than MCP prompts (`ui://`).
- Android OAuth still needs a host-provided browser callback and redirect URI (JVM has a built-in loopback).
- A project `.pi/mcp.json` can only override enabled/exposure/toolExposure of global servers; it cannot add servers.
- `auth.provider` is parsed but not wired up.

## Examples

```bash
./gradlew :examples:mcp-demo:run
```

The example starts a local mock MCP HTTP server (an echo tool + a resource) and demonstrates the full flow of a deferred tool being loaded through `tool_search` and called.
