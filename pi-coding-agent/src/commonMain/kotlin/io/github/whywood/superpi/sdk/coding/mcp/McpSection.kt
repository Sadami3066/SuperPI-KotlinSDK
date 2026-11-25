package io.github.whywood.superpi.sdk.coding.mcp

import io.github.whywood.superpi.sdk.mcp.config.McpExposure

internal fun buildMcpServersSection(
    statuses: List<McpServerStatus>,
    usesToolExecute: Boolean = false,
): String? {
    val reachable = statuses.filter { status ->
        status.exposure == McpExposure.Deferred || status.exposure == McpExposure.Codemode
    }
    if (reachable.isEmpty()) return null
    val usageLine = if (usesToolExecute) {
        "Use `tool_search` to discover tools from these servers, then execute them with `tool_execute`."
    } else {
        "Use the `tool_search` tool to find and load the tools you need before calling them."
    }
    val lines = mutableListOf(
        "Some MCP (Model Context Protocol) servers are connected but their tools are not declared upfront.",
        usageLine,
        "",
    )
    for (status in reachable.sortedBy { it.name }) {
        val summary = firstLine(status.description ?: status.instructions)
        if (summary != null) {
            lines.add("- ${status.name}: $summary")
        } else {
            lines.add("- ${status.name}")
        }
    }
    return "<mcp_servers>\n" + lines.joinToString("\n") + "\n</mcp_servers>"
}

private fun firstLine(text: String?): String? {
    val value = text?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return null
    return value.take(200)
}
