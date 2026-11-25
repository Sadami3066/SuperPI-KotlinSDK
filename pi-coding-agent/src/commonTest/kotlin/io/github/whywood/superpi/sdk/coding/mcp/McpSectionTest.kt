package io.github.whywood.superpi.sdk.coding.mcp

import io.github.whywood.superpi.sdk.mcp.config.McpExposure
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpSectionTest {
    @Test
    fun wrapsReachableServersInStableContainer() {
        val section = buildMcpServersSection(
            listOf(
                McpServerStatus(
                    name = "search",
                    state = McpServerState.Failed,
                    lastError = "connection refused",
                ),
                McpServerStatus(
                    name = "files",
                    state = McpServerState.Connected,
                    toolCount = 3,
                    description = "File tools",
                ),
            ),
        )
        requireNotNull(section)
        assertTrue(section.startsWith("<mcp_servers>"))
        assertTrue(section.endsWith("</mcp_servers>"))
        assertTrue(section.contains("Use the `tool_search` tool to find and load the tools you need before calling them."))
        assertTrue(section.contains("- files: File tools"))
        assertTrue(section.contains("- search"))
        assertFalse(section.contains("connection refused"))
        assertFalse(section.contains("3 tools"))
    }

    @Test
    fun guidesToolExecuteWhenEnabled() {
        val section = buildMcpServersSection(
            listOf(McpServerStatus(name = "files", state = McpServerState.Connected, description = "File tools")),
            usesToolExecute = true,
        )
        requireNotNull(section)
        assertTrue(section.contains("Use `tool_search` to discover tools from these servers, then execute them with `tool_execute`."))
    }

    @Test
    fun returnsNullWithoutReachableServers() {
        assertNull(buildMcpServersSection(emptyList()))
        assertNull(
            buildMcpServersSection(
                listOf(
                    McpServerStatus(name = "direct", state = McpServerState.Connected, exposure = McpExposure.Direct),
                    McpServerStatus(name = "hidden", state = McpServerState.Connected, exposure = McpExposure.Hidden),
                ),
            ),
        )
    }
}
