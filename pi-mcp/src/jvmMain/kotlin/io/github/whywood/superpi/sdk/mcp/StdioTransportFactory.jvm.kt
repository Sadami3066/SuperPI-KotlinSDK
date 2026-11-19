package io.github.whywood.superpi.sdk.mcp

import io.github.whywood.superpi.sdk.mcp.transport.StdioTransport

private object JvmStdioTransportFactory : StdioTransportFactory {
    override fun create(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        cwd: String?,
    ): McpTransport = StdioTransport(command = command, args = args, env = env, cwd = cwd)
}

actual fun platformStdioTransportFactory(): StdioTransportFactory = JvmStdioTransportFactory
