# pi-coding-agent SDK contract (M5)

All new code goes in `pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/`, tests in `commonTest`. Platform-agnostic: only okio (`okio.FileSystem`/`Path`), kotlinx.serialization, coroutines; `java.*`, `File`, `ProcessBuilder`, and `System.getenv` are forbidden.

## General

```kotlin
// paths/AgentPaths.kt
class AgentPaths(val cwd: Path, val agentDir: Path) {
    val sessionDir: Path            // agentDir / "sessions"
    fun sessionsForCwd(): Path      // sessionDir / "--<encoded-cwd>--"
    val promptsDir: Path            // agentDir / "prompts"
    val skillsDir: Path             // agentDir / "skills"
    val extensionsDir: Path
    val themesDir: Path
}

// json/Jsonc.kt
object Jsonc {
    fun parse(text: String): JsonElement   // allows // and /* */ comments and trailing commas; throws IllegalArgumentException on failure
}
```

## 1. session (task-9)

`session/SessionEntry.kt`: `SessionHeader` + 11 entry kinds (format in `docs/analysis/pi-coding-agent-formats.md` §2.3-2.5; field names verbatim).

```kotlin
sealed interface SessionEntry {
    val type: String
    val id: String
    val parentId: String?
    val timestamp: String        // ISO-8601 string
}

class SessionMessageEntry(... val message: Message) : SessionEntry
class ThinkingLevelChangeEntry(... val thinkingLevel: String) : SessionEntry
class ModelChangeEntry(... val provider: String, val modelId: String) : SessionEntry
class UsageEntry(... val kind: String? = null, val provider: String, val model: String, val usage: Usage, val note: String? = null) : SessionEntry
class CompactionEntry(... val summary: String, val firstKeptEntryId: String, val tokensBefore: Long, val usage: Usage? = null, val details: JsonValue? = null, val fromHook: Boolean? = null, val systemMessage: SystemMessage? = null) : SessionEntry
class BranchSummaryEntry(... val fromId: String, val summary: String, val details: JsonValue? = null, val usage: Usage? = null, val fromHook: Boolean? = null) : SessionEntry
class CustomEntry(... val customType: String, val data: JsonValue? = null) : SessionEntry
class CustomMessageEntry(... val customType: String, val content: MessageContent, val display: Boolean, val details: JsonValue? = null) : SessionEntry
class ContextEditEntry(... val targetId: String, val replacement: ContentReplacement?) : SessionEntry
class LabelEntry(... val targetId: String, val label: String?) : SessionEntry
class SessionInfoEntry(... val name: String?) : SessionEntry
```

`session/SessionManager.kt`:

```kotlin
class SessionManager {
    companion object {
        suspend fun create(paths: AgentPaths, fs: FileSystem, sessionId: String? = null): SessionManager
        suspend fun open(file: Path, fs: FileSystem): SessionManager
        suspend fun continueRecent(paths: AgentPaths, fs: FileSystem): SessionManager?
        fun inMemory(cwd: Path): SessionManager
        suspend fun list(paths: AgentPaths, fs: FileSystem): List<SessionInfo>       // metadata: id/file/cwd/timestamp/name/messageCount
        suspend fun findById(sessionId: String, paths: AgentPaths, fs: FileSystem): SessionManager?
    }

    val sessionId: String
    val cwd: Path
    val sessionFile: Path?

    fun getHeader(): SessionHeader
    fun getEntries(): List<SessionEntry>
    fun getEntry(id: String): SessionEntry?
    fun getLeafId(): String?
    fun getBranch(fromId: String? = null): List<SessionEntry>            // walks back from leaf by default
    fun getSessionName(): String?
    fun getTree(): List<TreeNode>                                        // TreeNode(entry, children)
    fun buildSessionProjection(): List<SessionEntry>                     // applies context_edit
    fun buildContextEntries(): List<SessionEntry>                        // compaction-aware
    fun buildSessionContext(): SessionContext                            // messages + provider/model + thinkingLevel
    fun getSessionStats(): SessionStats                                  // accumulated tokens/cost (usage entries + assistant usage)

    suspend fun appendMessage(message: Message): SessionMessageEntry
    suspend fun appendThinkingLevelChange(level: String): ThinkingLevelChangeEntry
    suspend fun appendModelChange(provider: String, modelId: String): ModelChangeEntry
    suspend fun appendUsage(kind: String?, provider: String, model: String, usage: Usage, note: String? = null): UsageEntry
    suspend fun appendCompaction(summary: String, firstKeptEntryId: String, tokensBefore: Long, usage: Usage? = null, details: JsonValue? = null, systemMessage: SystemMessage? = null): CompactionEntry
    suspend fun appendBranchSummary(fromId: String, summary: String, details: JsonValue? = null, usage: Usage? = null): BranchSummaryEntry
    suspend fun appendCustomEntry(customType: String, data: JsonValue? = null): CustomEntry
    suspend fun appendCustomMessageEntry(customType: String, content: MessageContent, display: Boolean, details: JsonValue? = null): CustomMessageEntry
    suspend fun appendContextEdit(targetId: String, replacement: ContentReplacement?): ContextEditEntry
    suspend fun appendLabelChange(targetId: String, label: String?): LabelEntry
    suspend fun appendSessionInfo(name: String?): SessionInfoEntry

    fun branch(entryId: String)
    fun resetLeaf()
    suspend fun branchWithSummary(entryId: String, summary: String, details: JsonValue? = null, usage: Usage? = null): BranchSummaryEntry
    suspend fun createBranchedSession(leafId: String? = null): SessionManager
    suspend fun close()
}

data class SessionContext(val messages: List<Message>, val provider: String?, val modelId: String?, val thinkingLevel: String?)
data class SessionInfo(val sessionId: String, val sessionFile: Path, val cwd: String, val timestamp: String, val name: String?, val messageCount: Int)
data class SessionStats(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long, val cacheWriteTokens: Long, val totalCost: Double)
class TreeNode(val entry: SessionEntry, val children: List<TreeNode>)
```

Must be replicated: deferred file creation (the file is created only on the first user/assistant message; earlier entries stay in memory), append-only writes, v1-v3 migration on read, cwd encoding, uuidv7/8-hex ids, context_edit projection, compaction-aware rebuild.

## 2. settings (task-10)

`settings/PiSettings.kt`: the full settings model (all fields in the formats doc §3.2); defaults follow upstream `settings-defaults.ts`.

```kotlin
@Serializable data class PiSettings(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val defaultTools: List<String>? = null,
    val enabledModels: List<String>? = null,
    val steeringMode: String? = null,
    val followUpMode: String? = null,
    val retry: RetrySettings? = null,
    val compaction: CompactionSettings? = null,
    val images: ImageSettings? = null,
    val shell: ShellSettings? = null,
    val modelThinking: Map<String, String>? = null,
    val theme: String? = null,
    val packages: List<JsonElement>? = null,
    ...
)
class SettingsManager {
    suspend fun reload()
    fun getSettings(): PiSettings
    suspend fun setDefaultModelAndProvider(provider: String, modelId: String)
    suspend fun setDefaultThinkingLevel(level: String?)
    suspend fun setEnabledModels(models: List<String>?)
    suspend fun setDefaultTools(tools: List<String>?)
    suspend fun setSteeringMode(mode: String)
    suspend fun setFollowUpMode(mode: String)
    suspend fun setRetryEnabled(enabled: Boolean)
    suspend fun setCompactionEnabled(enabled: Boolean)
    fun getDefaultModel(): Pair<String, String>?     // provider to modelId
    fun getDefaultThinkingLevel(): String?
    fun getEnabledModels(): List<String>?
    fun getDefaultTools(): List<String>              // includes +/- modifier rules (formats §3.3)
    fun getSteeringMode(): String
    fun getFollowUpMode(): String
    fun getRetrySettings(): RetrySettings
    fun getCompactionSettings(): CompactionSettings
    fun getImageAutoResize(): Boolean
    fun getShellPath(): String?
    fun getModelThinkingLevel(provider: String, modelId: String): String?
}
```
Two-level global+project merge (array union/override rules per upstream), write-back touches only dirty fields and preserves unknown ones, atomic writes (tmp+move).

## 3. models (task-11)

```kotlin
// models/CustomModels.kt
data class CustomModelConfig(val id, val name?, val reasoning?, val input?, val contextWindow?, val maxTokens?, val cost?...)
data class CustomProviderConfig(val id: String?, val baseUrl, val api, val apiKey?, val headers?, val models: List<CustomModelConfig>, val authHeader: String? = null)
object CustomModelsLoader { fun parse(text: String): List<CustomProviderConfig> }   // the providers section of models.json

// models/ModelRuntime.kt
class ModelRuntime(
    val models: Models,                     // pi-ai, built from createDefaultModels() + custom providers
    val credentialStore: CredentialStore,
    val env: Map<String, String> = emptyMap(),
) {
    fun getModels(): List<Model>
    fun getModel(provider: String, id: String): Model?
    fun getProvider(id: String): Provider?
    suspend fun getAvailableSnapshot(): List<Model>
    suspend fun resolveModel(spec: String): Model?
    suspend fun hasConfiguredAuth(providerId: String): Boolean
    suspend fun streamSimple(model: Model, context: Context, options: SimpleStreamOptions? = null, signal: RunSignal? = null): AssistantMessageEventStream
    suspend fun completeSimple(model: Model, context: Context, options: SimpleStreamOptions? = null, signal: RunSignal? = null): AssistantMessage
    fun registerProvider(provider: Provider)
    suspend fun refresh()
}

// models/ModelResolver.kt
class ModelResolver(private val runtime: ModelRuntime, private val defaultProvider: String?, private val defaultModel: String?, private val enabledModels: List<String>?) {
    suspend fun resolve(spec: String): ResolvedModel          // "provider/id", "id", fuzzy matching, ":thinking" suffix
    suspend fun resolveDefault(): ResolvedModel?
}
data class ResolvedModel(val model: Model, val thinkingLevel: String?)
```
models.json custom providers: `apiKey` goes into provider auth; `authHeader` and so on follow upstream provider-composer.

## 4. tools (task-12 / task-13)

```kotlin
// tools/ToolSupport.kt
class CodingTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val label: String,
    val promptSnippet: String? = null,
    val promptGuidelines: List<String>? = null,
    val prepareArguments: ((JsonObject) -> JsonObject)? = null,
    val execute: suspend (toolCallId: String, args: JsonObject, signal: RunSignal?, onUpdate: AgentToolUpdateCallback?) -> AgentToolResult,
) {
    fun asAgentTool(): AgentTool
}
class ToolOutput(val content: String, val details: JsonValue? = null)
fun textResult(text: String, details: JsonValue? = null, isError: Boolean = false): AgentToolResult

// tools/PathUtils.kt
fun resolveToolPath(cwd: Path, input: String): Path     // relative to cwd, expands ~, normalizes
// tools/Truncate.kt
data class Truncated(val text: String, val truncated: Boolean, val truncatedBy: String? = null)
fun truncateHead(text: String, maxLines: Int = 2000, maxBytes: Int = 50_000): Truncated
fun truncateTail(...); fun truncateLine(line: String, maxChars: Int): String
// tools/FileMutationQueue.kt
class FileMutationQueue { suspend fun <T> withFile(path: Path, block: suspend () -> T): T }
```

Tools (behavior and prompt text per `docs/analysis/pi-coding-agent.md` §6 and upstream source):

- task-12: `read`, `write`, `edit`, `ls` (pure Kotlin/okio; edit returns a diff summary + first changed line; BOM/CRLF normalization; write creates parent directories; read truncates by both lines and bytes, supports offset/limit, and handles binaries and large files)
- task-13: `grep`, `find` (**no rg/fd dependency, pure Kotlin**: okio recursive traversal + configurable ignored directories, glob→Regex, regex/literal/ignore-case/context lines/limit; output format, truncation, and prompt text aligned with upstream text), `bash` (`ShellExecutor` injected via interface, no default implementation; without an executor the tool returns an explicit error)
- `tools/CodingTools.kt`: `fun defaultCodingTools(cwd, fs, mutationQueue, shellExecutor?): List<CodingTool>`

## 5. prompt (task-14)

```kotlin
// prompt/SystemPrompt.kt
class SystemPromptSections(val sections: Map<String, String>)          // order-sensitive: preamble,tools,rules,docs,addendum,project_context,skills,cwd
fun buildSystemPrompt(input: SystemPromptInput): SystemPromptSections
fun diffSystemPromptSections(previous: SystemPromptSections?, current: SystemPromptSections): JsonObject   // null value = delete
class SystemPromptInput(val preamble: String? = null, val toolSnippets: List<String>, val toolGuidelines: List<String>, val docsPaths: List<String>, val addendum: String?, val projectInstructions: List<ContextFile>, val skills: List<Skill>, val cwd: Path)

// prompt/ResourceLoader.kt
class ResourceLoader(val paths: AgentPaths, val fs: FileSystem, val settings: SettingsManager) {
    suspend fun reload(): LoadedResources
}
data class LoadedResources(
    val systemPromptOverride: String?,
    val appendSystemPrompt: String?,
    val contextFiles: List<ContextFile>,
    val skills: List<Skill>,
    val promptTemplates: List<PromptTemplate>,
)
data class ContextFile(val path: Path, val content: String)
data class Skill(val name: String, val description: String, val filePath: String, val content: String)
data class PromptTemplate(val name: String, val description: String?, val content: String)
fun renderAvailableSkills(skills: List<Skill>): String     // <available_skills> XML, verbatim with upstream
class PromptTemplateExpander { fun expand(text: String): String }   // /template expansion (argument substitution rules per upstream)
```

Default prompt text is ported verbatim from upstream `core/system-prompt.ts` and `core/skills.ts` templates; context file rules: `AGENTS.md`/`AGENTS.override.md`/`CLAUDE.md`, `SYSTEM.md`/`APPEND_SYSTEM.md` (formats §12).

## AgentSession (lead-owned; nobody else writes it)

`session/AgentSession.kt` and `AgentSdk.kt` (createAgentSession) are implemented by the lead and depend on the contracts above.

## General constraints

- Kotlin code has no comments; JSON field names are camelCase matching upstream; error text is verbatim with upstream (the model depends on it).
- Only write files in your own task's declared scope; do not change build.gradle.kts, pi-ai, or pi-agent.
- Verification: `./gradlew :pi-coding-agent:jvmTest --no-daemon` (about 1 minute on first compile). Do not run a full-repo build concurrently.
- When done, complete the task + send_message to the lead: file list, test count, deviations, and open items.
