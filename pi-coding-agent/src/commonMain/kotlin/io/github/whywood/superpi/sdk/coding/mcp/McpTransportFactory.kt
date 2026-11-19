package io.github.whywood.superpi.sdk.coding.mcp

import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.mcp.McpTransport
import io.github.whywood.superpi.sdk.mcp.McpTransportUnsupportedException
import io.github.whywood.superpi.sdk.mcp.transport.StreamableHttpTransportOptions
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import io.github.whywood.superpi.sdk.mcp.platformStdioTransportFactory
import io.github.whywood.superpi.sdk.mcp.transport.StreamableHttpTransport

fun interface McpTransportFactory {
    suspend fun create(name: String, config: McpServerConfig, authProvider: io.github.whywood.superpi.sdk.mcp.oauth.McpAuthProvider?): McpTransport
}

class DefaultMcpTransportFactory(
    private val http: HttpTransport,
    private val env: Map<String, String> = emptyMap(),
) : McpTransportFactory {
    override suspend fun create(
        name: String,
        config: McpServerConfig,
        authProvider: io.github.whywood.superpi.sdk.mcp.oauth.McpAuthProvider?,
    ): McpTransport = when (config) {
        is McpServerConfig.Http -> StreamableHttpTransport(
            http = http,
            options = StreamableHttpTransportOptions(
                url = config.url,
                headers = config.headers,
                authProvider = authProvider,
            ),
        )

        is McpServerConfig.Stdio -> {
            val mergedEnv = LinkedHashMap(env).apply { putAll(config.env) }
            platformStdioTransportFactory().create(
                command = config.command,
                args = config.args,
                env = mergedEnv,
                cwd = config.cwd,
            ) ?: throw McpTransportUnsupportedException(
                "stdio MCP servers are not supported on this platform; use an HTTP MCP server instead",
            )
        }
    }
}
