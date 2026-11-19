# Interface contracts for parallel implementation (completed rounds)

> Status: task-4 through task-8 are all complete and merged; this file is retained as interface reference.

# Interface contracts for parallel implementation

This file freezes the public signatures that parallel development depends on. Implementers must not change signatures owned by other tasks; notify the lead before requesting a change.

## Existing public types (pi-ai, `io.github.whywood.superpi.sdk.ai`)

```kotlin
val PiJson: Json

sealed interface Message            // SystemMessage/UserMessage/AssistantMessage/ToolResultMessage
sealed interface Content            // TextContent/ThinkingContent/ImageContent/ToolCall
sealed interface AssistantMessageEvent
sealed interface MessageContent     // PlainTextContent/BlockContent

data class Model(...)               // a chat model from the catalog
data class Tool(name, description, parameters: JsonObject, constrainedSampling: JsonValue?)
class TranscriptContext(val messages: List<Message>)
class AssistantMessageEventStream   // push/end/events: Flow/result()
class RunSignal                     // abort()/isAborted/onAbort()

data class StreamOptions(...)       // apiKey/env/headers/onPayload/onResponse/temperature/maxTokens/transport/cacheRetention/sessionId...
data class SimpleStreamOptions(val stream: StreamOptions, val toolChoice, val reasoning, val deferred, val thinkingBudgets)
```

## task-4 (HTTP/streaming foundation) exposure

```kotlin
// io.github.whywood.superpi.sdk.ai.http
class SseEvent(val event: String?, val data: String, val id: String?, val retry: Long?)
class SseParser(source: BufferedSource) { suspend fun next(): SseEvent? ; fun close() }

// io.github.whywood.superpi.sdk.ai.http (okhttpMain source set)
fun okHttpTransport(client: OkHttpClient = defaultClient()): HttpTransport

// io.github.whywood.superpi.sdk.ai.utils
object PartialJson {
    fun parse(text: String): JsonValue?              // incomplete input returns the parsed part/null
    fun repair(text: String): String
    fun parseToolCallArguments(json: String?): ToolArguments   // arguments: JsonObject + argumentsError: String?
}
data class ToolArguments(val arguments: JsonObject, val argumentsError: String? = null)

object Retry {
    fun isRetryableAssistantError(message: AssistantMessage): Boolean
    fun retryDelayMs(baseDelayMs: Long, maxAgentDelayMs: Long, attempt: Int): Long
    suspend fun sleep(ms: Long, signal: RunSignal?)
}

object ProviderErrors {
    fun normalize(status: Int, headers: Map<String, String>, body: String): NormalizedProviderError
    fun format(error: NormalizedProviderError, prefix: String? = null): String
    fun truncate(text: String, maxChars: Int): String
}
```

`OkHttpTransport` semantics: SSE is not buffered as a whole packet; non-2xx HTTP still returns an `HttpResponse` (the upper layer reads the body to produce an error); cancellation can simply throw via coroutine cancel (the upper layer catches it and converts to aborted); proxies are read from `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` environment variables (JVM), while Android uses the system default ProxySelector.

## task-5 (transcript/validation/estimation) exposure

```kotlin
// io.github.whywood.superpi.sdk.ai.utils.Transcript (top-level functions avoid clashing with the class name)
fun createInitialSystemMessage(systemPrompt: String?, tools: List<Tool>?): SystemMessage?
fun normalizeContext(context: Context): TranscriptContext
fun getInitialSystemMessage(messages: List<Message>): SystemMessage?
fun withoutInitialSystemMessage(messages: List<Message>): List<Message>
fun getCurrentTools(messages: List<Message>): List<Tool>
fun getCurrentSystemMessage(messages: List<Message>): SystemMessage?
fun getCurrentSystemPrompt(messages: List<Message>): String
fun collapseSystemMessages(context: TranscriptContext): TranscriptContext
fun toToolDeclaration(tool: Tool): Tool
fun declarationsEqual(a: Tool, b: Tool): Boolean
data class ToolStateChanges(val added: List<Tool>, val removed: List<ToolReference>)
fun getToolStateChanges(previous: List<Tool>, current: List<Tool>): ToolStateChanges

// io.github.whywood.superpi.sdk.ai.utils.Text
fun contentText(content: MessageContent, separator: String = "\n"): String
fun getSystemMessageText(message: SystemMessage): String
fun renderSystemMessageUpdate(message: SystemMessage): String

// io.github.whywood.superpi.sdk.ai.utils.Validation
class ToolArgumentValidationException(message: String) : Exception(message)
fun validateToolArguments(tool: Tool, toolCall: ToolCall): JsonObject
fun validateToolCall(tools: List<Tool>, toolCall: ToolCall): JsonObject

// io.github.whywood.superpi.sdk.ai.utils.Estimate
fun calculateContextTokens(usage: Usage): Long
fun estimateTextTokens(text: String): Long
fun estimateMessageTokens(message: Message): Long
data class ContextUsageEstimate(val tokens: Long, val usageTokens: Long, val trailingTokens: Long, val lastUsageIndex: Int)
fun estimateContextTokens(messages: List<Message>): ContextUsageEstimate

// io.github.whywood.superpi.sdk.ai.utils.Overflow
fun isContextOverflow(message: AssistantMessage, contextWindow: Long? = null): Boolean
fun isRecoverableLength(message: AssistantMessage, desiredMaxOutput: Long): Boolean
```

Validator requirements: port upstream `utils/validation.ts`'s JSON Schema subset in full (type/properties/required/additionalProperties/items/enum/const/anyOf/oneOf/allOf/nullable/integer-and-number coercion), with error messages aligned verbatim: `Validation failed for tool "x":\n  - /path: message\n\nReceived arguments:\n{...}`; `argumentsError` takes precedence: `Invalid arguments for tool "x": <argumentsError>`.

## task-6 (openai-completions) exposure

```kotlin
// io.github.whywood.superpi.sdk.ai.api
fun openAICompletionsApi(): ApiProvider
// ApiProvider: val api: String; suspend fun stream(model, context: TranscriptContext, options: StreamOptions): AssistantMessageEventStream
//              suspend fun streamSimple(model, context, options: SimpleStreamOptions): AssistantMessageEventStream
```

Port the parts of `src/api/openai-completions.ts` + `src/api/transform-messages.ts` + `src/api/simple-options.ts` + `src/api/openai-prompt-cache.ts` that this API uses in full; support all compat fields from `src/providers/compat-schema.ts`'s `OpenAICompletionsCompat`. HTTP goes through `HttpTransport` (injected as a constructor parameter), SSE uses `SseParser`, argument increments use `PartialJson`, and errors use `ProviderErrors`.

## task-7 (Models/Provider/auth/faux) exposure

```kotlin
// io.github.whywood.superpi.sdk.ai
interface ApiFactory { fun create(): ApiProvider }

class Provider(
    val id: String,
    val name: String,
    val baseUrl: String? = null,
    val headers: Map<String, String?>? = null,
    val apiProviders: Map<String, () -> ApiProvider>,
    val models: List<Model>,
    val allModels: List<AnyModel> = models,
    val auth: AuthResolver,
)

class Models(private val providers: MutableMap<String, Provider> = linkedMapOf()) {
    fun setProvider(provider: Provider)
    fun getProviders(): List<Provider>
    fun getProvider(id: String): Provider?
    fun getModels(provider: String? = null): List<Model>
    fun getModel(provider: String, id: String): Model?
    fun getModelsOfType(type: ModelType, provider: String? = null): List<AnyModel>
    suspend fun stream(model: Model, context: Context, options: StreamOptions? = null, signal: RunSignal? = null): AssistantMessageEventStream
    suspend fun complete(model: Model, context: Context, options: StreamOptions? = null, signal: RunSignal? = null): AssistantMessage
    suspend fun streamSimple(model: Model, context: Context, options: SimpleStreamOptions? = null, signal: RunSignal? = null): AssistantMessageEventStream
    suspend fun completeSimple(model: Model, context: Context, options: SimpleStreamOptions? = null, signal: RunSignal? = null): AssistantMessage
}

fun createModels(): Models
fun builtinProviders(): List<Provider>
fun calculateCost(model: AnyModel, usage: Usage): UsageCost
fun getSupportedThinkingLevels(model: Model): List<ModelThinkingLevel>
fun clampThinkingLevel(model: Model, level: ModelThinkingLevel): ModelThinkingLevel

// io.github.whywood.superpi.sdk.ai.auth
sealed interface AuthResolver { suspend fun resolve(overrides: AuthOverrides? = null): ResolvedAuth? }
data class ResolvedAuth(val apiKey: String?, val headers: Map<String, String> = emptyMap(), val source: String)
class EnvApiKeyAuth(val label: String, val envVars: List<String>, val inlineKey: String? = null) : AuthResolver
class StaticApiKeyAuth(val apiKey: String) : AuthResolver
class CredentialStore            // JSON persistence with injectable path; compatible with pi's auth.json structure
```

`builtinProviders()` builds all 42 providers from the bundled catalog data: each provider's `models` are its chat models, and `apiProviders` groups them by the model `api` field; not-yet-implemented APIs (other than openai-completions) use placeholder factories (an ApiProvider that throws `NotImplementedError`) so everything still compiles; `auth` uses `EnvApiKeyAuth` with env variable names matching upstream `src/providers/*.ts`.

faux provider (`providers/Faux.kt`): ports the scripted responses/streaming events/tool calls of `src/providers/faux.ts` for tests.

## task-8 (pi-agent) exposure

Implement `io.github.whywood.superpi.sdk.agent` per `docs/analysis/pi-agent.md`: `AgentMessage`/`AgentTool`/`AgentEvent`/`AgentContext`/`AgentState`/`AgentOptions`/`AgentLoopConfig`/`StreamFn`/`Agent`/`runAgentLoop`/`runAgentLoopContinue`/proxy. `StreamFn` is defined as:

```kotlin
typealias StreamFn = suspend (model: Model, context: TranscriptContext, options: SimpleStreamOptions?) -> AssistantMessageEventStream
```

pi-agent depends only on pi-ai's public types and the frozen functions in `utils/`; do not modify the pi-ai module.

## General constraints

- Kotlin code carries no comments; express intent through naming and structure.
- Read the corresponding `docs/analysis/` sections first (they carry line-by-line evidence), then the upstream TS implementation.
- File write scope follows the task declaration strictly; do not modify `build.gradle.kts`, `gradle/libs.versions.toml`, or `docs/` (unless the task says so).
- Tests: `./gradlew :pi-ai:jvmTest` (fast JVM-only tests); make sure `:pi-ai:allTests` is green before committing.
- Naming: packages `io.github.whywood.superpi.sdk.ai` / `io.github.whywood.superpi.sdk.agent` / `io.github.whywood.superpi.sdk.coding`; no `I` prefix on interfaces.
- When done, complete the task and message the lead: file list, test results, deviations from the contract, and open items.
