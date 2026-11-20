package io.github.whywood.superpi.sdk.agent

import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.platform.PlatformAtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentConcurrencyTest {

    @Test
    fun `parallel tool execution serializes events and preserves pendingToolCalls across threads`() = runTest {
        withContext(Dispatchers.Default) {
            repeat(5) { round ->
                val toolCount = 24
                val updatesPerTool = 5
                val toolCalls = (1..toolCount).map { index ->
                    toolCallBlock(
                        id = "call-$round-$index",
                        name = "worker",
                        arguments = jsonObjectOf("value" to JsonPrimitive("item-$index")),
                    )
                }
                val tool = agentTool(name = "worker", parameters = valueSchema()) { callId, args, _, onUpdate ->
                    val label = (args["value"] as JsonPrimitive).content
                    for (step in 1..updatesPerTool) {
                        yield()
                        onUpdate?.invoke(AgentToolResult(content = listOf(TextContent("$callId:$step"))))
                    }
                    AgentToolResult(content = listOf(TextContent("done:$label")))
                }

                var requestCount = 0
                val agent = Agent(
                    AgentOptions(
                        initialState = AgentInitialState(tools = listOf(tool)),
                        toolExecution = ToolExecutionMode.Parallel,
                        streamFn = { _, _, _ ->
                            requestCount++
                            if (requestCount == 1) {
                                doneStream(assistantMessage(toolCalls, StopReason.ToolUse))
                            } else {
                                doneStream(assistantMessage(listOf(textBlock("all done"))))
                            }
                        },
                    ),
                )

                val inListener = PlatformAtomicBoolean(false)
                var concurrentOverlapDetected = false
                val expectedPending = LinkedHashSet<String>()
                var pendingMismatch: String? = null
                val startedIds = mutableListOf<String>()
                var updateCount = 0
                val endedIds = mutableListOf<String>()

                agent.subscribe { event, _ ->
                    if (!inListener.compareAndSet(false, true)) {
                        concurrentOverlapDetected = true
                    }
                    try {
                        when (event) {
                            is AgentEvent.ToolExecutionStart -> {
                                startedIds.add(event.toolCallId)
                                expectedPending.add(event.toolCallId)
                                if (agent.state.pendingToolCalls != expectedPending) {
                                    pendingMismatch =
                                        "start ${event.toolCallId}: expected $expectedPending, got ${agent.state.pendingToolCalls}"
                                }
                            }

                            is AgentEvent.ToolExecutionUpdate -> {
                                updateCount++
                                yield()
                            }

                            is AgentEvent.ToolExecutionEnd -> {
                                endedIds.add(event.toolCallId)
                                expectedPending.remove(event.toolCallId)
                                if (agent.state.pendingToolCalls != expectedPending) {
                                    pendingMismatch =
                                        "end ${event.toolCallId}: expected $expectedPending, got ${agent.state.pendingToolCalls}"
                                }
                            }

                            else -> Unit
                        }
                    } finally {
                        inListener.set(false)
                    }
                }

                agent.prompt("run parallel batch $round")

                assertFalse(concurrentOverlapDetected, "AgentListener callbacks must not overlap concurrently")
                assertEquals(null, pendingMismatch)
                assertEquals(toolCount, startedIds.size)
                assertEquals(toolCount * updatesPerTool, updateCount)
                assertEquals(toolCount, endedIds.size)
                assertEquals(startedIds.toSet(), endedIds.toSet())
                assertEquals(emptySet(), agent.state.pendingToolCalls)
                assertFalse(agent.state.isStreaming)
                val toolResults = agent.state.messages.mapNotNull { it.asToolResultMessage() }
                assertEquals((1..toolCount).map { "call-$round-$it" }, toolResults.map { it.toolCallId })
            }
        }
    }

    @Test
    fun `concurrent subscribe and unsubscribe during event emission does not race or deadlock`() = runTest {
        withContext(Dispatchers.Default) {
            val toolCount = 16
            val updatesPerTool = 8
            val toolCalls = (1..toolCount).map { index ->
                toolCallBlock(
                    id = "sub-call-$index",
                    name = "emit",
                    arguments = jsonObjectOf("value" to JsonPrimitive("v-$index")),
                )
            }
            val tool = agentTool(name = "emit", parameters = valueSchema()) { callId, _, _, onUpdate ->
                for (step in 1..updatesPerTool) {
                    yield()
                    onUpdate?.invoke(AgentToolResult(content = listOf(TextContent("$callId:$step"))))
                }
                AgentToolResult(content = listOf(TextContent("ok")))
            }

            var requestCount = 0
            val agent = Agent(
                AgentOptions(
                    initialState = AgentInitialState(tools = listOf(tool)),
                    toolExecution = ToolExecutionMode.Parallel,
                    streamFn = { _, _, _ ->
                        requestCount++
                        if (requestCount == 1) {
                            doneStream(assistantMessage(toolCalls, StopReason.ToolUse))
                        } else {
                            doneStream(assistantMessage(listOf(textBlock("finished"))))
                        }
                    },
                ),
            )

            val runDone = PlatformAtomicBoolean(false)
            val reentrantEvents = mutableListOf<String>()
            var selfUnsubscribe: (() -> Unit)? = null
            var dynamicallyAddedEvents = 0
            selfUnsubscribe = agent.subscribe { event, _ ->
                reentrantEvents.add(event.type)
                if (event is AgentEvent.ToolExecutionStart) {
                    selfUnsubscribe?.invoke()
                    agent.subscribe { nestedEvent, _ ->
                        if (nestedEvent is AgentEvent.ToolExecutionEnd) {
                            dynamicallyAddedEvents++
                        }
                    }
                }
            }

            val subscriberErrors = mutableListOf<Throwable>()
            val errorLock = Any()

            coroutineScope {
                val churners = List(4) {
                    launch {
                        try {
                            var iterations = 0
                            while (!runDone.get() && iterations < 500) {
                                iterations++
                                val unsub = agent.subscribe { _, _ -> }
                                yield()
                                unsub()
                            }
                        } catch (error: Throwable) {
                            synchronized(errorLock) { subscriberErrors.add(error) }
                        }
                    }
                }

                try {
                    agent.prompt("trigger concurrent subscriptions")
                } finally {
                    runDone.set(true)
                }

                churners.forEach { it.join() }
            }

            assertEquals(emptyList(), subscriberErrors)
            assertTrue(reentrantEvents.contains("tool_execution_start"))
            assertEquals(toolCount, dynamicallyAddedEvents)
            assertEquals(emptySet(), agent.state.pendingToolCalls)
            assertFalse(agent.state.isStreaming)
        }
    }

    @Test
    fun `concurrent prompt and reset attempts guard activeRun atomically`() = runTest {
        withContext(Dispatchers.Default) {
            repeat(5) {
                val releaseStream = CompletableDeferred<Unit>()
                val streamStarted = CompletableDeferred<Unit>()
                var streamCalls = 0
                val streamCallsLock = Any()

                val agent = Agent(
                    AgentOptions(
                        streamFn = { _, _, _ ->
                            val call = synchronized(streamCallsLock) { ++streamCalls }
                            if (call == 1) {
                                streamStarted.complete(Unit)
                                releaseStream.await()
                            }
                            doneStream(assistantMessage(listOf(textBlock("ok-$call"))))
                        },
                    ),
                )

                val startGate = CompletableDeferred<Unit>()
                val results = coroutineScope {
                    val racers = List(8) { index ->
                        async {
                            startGate.await()
                            runCatching { agent.prompt("race-$index") }
                        }
                    }
                    startGate.complete(Unit)
                    streamStarted.await()

                    assertFailsWith<IllegalStateException> { agent.reset() }
                    assertFailsWith<IllegalStateException> { agent.continueRun() }

                    val idleWaiter = async {
                        agent.waitForIdle()
                        assertFalse(agent.state.isStreaming)
                    }

                    releaseStream.complete(Unit)
                    idleWaiter.await()
                    racers.awaitAll()
                }

                assertEquals(1, results.count { it.isSuccess })
                assertEquals(7, results.count { it.exceptionOrNull() is IllegalStateException })
                assertEquals(1, streamCalls)

                agent.prompt("after-idle")
                assertEquals(2, streamCalls)
                agent.reset()
                assertEquals(emptyList(), agent.state.messages)
            }
        }
    }

    @Test
    fun `runAgentLoop parallel tool execution serializes direct sink emissions across threads`() = runTest {
        withContext(Dispatchers.Default) {
            val toolCount = 20
            val updatesPerTool = 4
            val toolCalls = (1..toolCount).map { index ->
                toolCallBlock(
                    id = "loop-call-$index",
                    name = "emit",
                    arguments = jsonObjectOf("value" to JsonPrimitive("v-$index")),
                )
            }
            val tool = agentTool(name = "emit", parameters = valueSchema()) { callId, _, _, onUpdate ->
                for (step in 1..updatesPerTool) {
                    yield()
                    onUpdate?.invoke(AgentToolResult(content = listOf(TextContent("$callId:$step"))))
                }
                AgentToolResult(content = listOf(TextContent("ok")))
            }

            val inSink = PlatformAtomicBoolean(false)
            var overlapDetected = false
            val events = mutableListOf<AgentEvent>()
            var requestCount = 0

            runAgentLoop(
                prompts = listOf(userMessage("go")),
                context = AgentContext(tools = listOf(tool)),
                config = AgentLoopConfig(
                    model = createModel(),
                    convertToLlm = identityConvertToLlm,
                    toolExecution = ToolExecutionMode.Parallel,
                ),
                emit = { event ->
                    if (!inSink.compareAndSet(false, true)) {
                        overlapDetected = true
                    }
                    try {
                        events.add(event)
                        yield()
                    } finally {
                        inSink.set(false)
                    }
                },
                streamFn = { _, _, _ ->
                    requestCount++
                    if (requestCount == 1) {
                        doneStream(assistantMessage(toolCalls, StopReason.ToolUse))
                    } else {
                        doneStream(assistantMessage(listOf(textBlock("done"))))
                    }
                },
            )

            assertFalse(overlapDetected, "AgentEventSink must not be invoked concurrently")
            assertEquals(toolCount, events.count { it is AgentEvent.ToolExecutionStart })
            assertEquals(toolCount * updatesPerTool, events.count { it is AgentEvent.ToolExecutionUpdate })
            assertEquals(toolCount, events.count { it is AgentEvent.ToolExecutionEnd })
        }
    }
}
