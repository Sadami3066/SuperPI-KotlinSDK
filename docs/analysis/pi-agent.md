# pi-agent-core (packages/agent) source analysis and Kotlin porting spec

- Source: `upstream pi checkout: packages/agent` (read-only snapshot), package name/version `@earendil-works/pi-agent-core` 1.3.0 (`package.json`).
- Size: `src` has 6 files totaling 2539 lines (types 539, agent-loop 959, agent 623, proxy 393, stream-fn 20, index 5); `test` has 4 files totaling 3997 lines + 89 lines of utils.
- Dependencies: `@earendil-works/pi-ai` 1.3.0 (types + EventStream/transcript helpers/argument validation/request identity), `typebox` 1.3.27 (types only; `src` does not import any runtime value).
- Evidence convention: `src/x.ts:line` and `test/x.test.ts:line` are relative to `packages/agent`; `ai/...` is relative to `packages/ai/src`; `ca/...` is relative to `packages/coding-agent/src`. Line numbers are based on the current snapshot.
- Reading goal: write behaviorally identical Kotlin verbatim without reading the TS. Sections 1-7 are factual specs; sections 8-10 cover porting decisions, tests, and file breakdown.

## 0. Summary of conclusions

1. The package has only two execution paths: the low-level stateless loop `runAgentLoop`/`runAgentLoopContinue` (events delivered through a sink callback, `agent-loop.ts:111-160`), and the stateful wrapper `Agent` (event subscriptions, message queues, interrupt, run lifecycle, `agent.ts:194-623`). The remaining files are types, event definitions, and the proxy streamFn.
2. All externally observable behavior is carried by the `AgentEvent` sequence; the event sink is async and every event is awaited (`agent-loop.ts:35`, `126`; `agent.ts:575-622`), and subscribers are awaited serially in registration order.
3. Tool execution has two strategies: `parallel` by default (serial preflight, then concurrent execution; `tool_execution_end` in completion order, toolResult messages in source order), and any tool declaring `executionMode: "sequential"` downgrades the whole batch to serial (`agent-loop.ts:518-533`, `596-670`).
4. abort is cooperative: the AbortSignal is passed through to streamFn, tools, and hooks, and the loop itself never throws. An assistant message with `stopReason` `error`/`aborted` is a hard exit; an abort inside a tool batch only produces an `Operation aborted` tool result and lets the loop continue to the next request with the already-aborted signal (`agent-loop.ts:254-265`, `585-587`, `623-653`, `748-773`).
5. This version has no `maxTurns`/retries/persistence/separate attachment API; stopping conditions are only the six listed in §4.5. Retries are driven by the caller via `continue()` (`agent-loop.ts:72-79`), and `finishTurn` can force one more request (`agent-loop.ts:295-323`).

## 1. Package responsibilities and boundaries

### 1.1 File responsibilities

| File | Lines | Responsibility | Key exports |
|---|---|---|---|
| `src/index.ts` | 5 | Aggregated exports | All public symbols |
| `src/types.ts` | 539 | Pure types: StreamFn, tools, hooks, loop config, state, events, context | All types |
| `src/agent-loop.ts` | 959 | Stateless loop: transcript tool declarations, streaming assistant, tool execution/validation/hooks, event emission | `agentLoop`, `agentLoopContinue`, `runAgentLoop`, `runAgentLoopContinue`, `runToolCall`, `DEFAULT_AGENT_IDENTITY`, `AgentEventSink`, `ToolCallHooks`, `RunToolCallOptions` |
| `src/agent.ts` | 623 | Stateful `Agent`: state object, subscriptions, steering/follow-up queues, AbortController, lifecycle | `Agent`, `AgentOptions`, `AgentInitialState`, `QueueMode` (re-export) |
| `src/proxy.ts` | 393 | StreamFn through a server-side proxy: HTTP POST + SSE parsing + partial rebuild | `streamProxy`, `ProxyStreamOptions`, `ProxyAssistantMessageEvent` |
| `src/stream-fn.ts` | 20 | Global default StreamFn registry | `setDefaultStreamFn` |

The package contains no provider implementations, auth, HTTP (other than the proxy's `fetch`), session storage, compaction, or retry policy. `CHANGELOG.md` 1.0.0 explicitly moved harness/session/durable capabilities out of this package (`CHANGELOG.md` 1.0.0 Breaking Changes).

### 1.2 `index.ts` export surface (`src/index.ts:1-5`)

- From `agent.ts`: `Agent`, `AgentOptions`, `AgentInitialState`, and the re-exported `QueueMode` (`agent.ts:37`).
- From `agent-loop.ts`: `AgentEventSink` (35), `DEFAULT_AGENT_IDENTITY` (41), `agentLoop` (47), `agentLoopContinue` (80), `runAgentLoop` (111), `runAgentLoopContinue` (137), `ToolCallHooks` (694), `RunToolCallOptions` (801), `runToolCall` (821).
- From `proxy.ts`: `streamProxy`, `ProxyStreamOptions`, `ProxyAssistantMessageEvent`.
- From `stream-fn.ts`: only `setDefaultStreamFn`; `getDefaultStreamFn` is not exported and is used internally only by `agent-loop.ts:21` and `agent.ts:16`.
- From `types.ts`: all types (via `export *`).

Note: `agentLoop`/`agentLoopContinue` return `EventStream<AgentEvent, AgentMessage[]>` (`ai/utils/event-stream.ts:26-135`), whose `result()` is `agent_end.messages` (`agent-loop.ts:162-167`); `runAgentLoop*` return `Promise<AgentMessage[]>` directly.

### 1.3 Dependency surface (pi-ai)

| Symbol | Used at | Purpose |
|---|---|---|
| `StreamFn`-related types, `Model`, `TranscriptContext`, `SimpleStreamOptions`, `AssistantMessageEventStream`, `AssistantMessageEvent`, `Message`/`AssistantMessage`/`ToolResultMessage`/`SystemMessage`/`TextContent`/`ImageContent`/`ToolCall`/`Usage`/`StopReason`, `Tool`, `ThinkingBudgets`, `ClientIdentity` | types.ts, agent.ts | Type foundation |
| `EventStream` | `agent-loop.ts:9,54-69,162-167` | Low-level event stream implementation |
| `createAssistantMessageEventStream`, `parseStreamingJson` | `proxy.ts:11,112,343` | Proxy output stream / incremental JSON |
| `normalizeContext` | `agent-loop.ts:406` | `Message[]` → `TranscriptContext` (messages only) |
| `createInitialSystemMessage`, `getCurrentSystemMessage`, `getCurrentSystemPrompt`, `toToolDeclaration` | `agent.ts:3-4,86,369-370` | Initial system message, reset baseline, `state.systemPrompt`, tool declaration stripping |
| `getCurrentTools`, `getToolStateChanges` | `agent-loop.ts:10-11,357-359` | Tool state replay and diff from the transcript |
| `validateToolArguments` | `agent-loop.ts:19,737` | Tool argument schema validation (typebox) |
| `mergeClientIdentity`, `PI_ATTRIBUTION` | `agent-loop.ts:12,14,41,415` | Request identity attribution |
| Tool state/transcript helpers | `ai/utils/transcript.ts` | Exact semantics below |

Prerequisites for the Kotlin port (pi-ai must provide these first, with verbatim-identical behavior):

1. Message/content/`Model`/`Usage`/`StopReason`/`AssistantMessageEvent`/`TranscriptContext` types and JSON serialization.
2. `EventStream<T,R>` and `AssistantMessageEventStream` (`ai/utils/event-stream.ts:26-135`): FIFO queue + waiter queue; `push` is ignored after `done`; terminal events parse the final result first; `end(result?)` resolves the result and wakes consumers; `result()` can be awaited before consumption; `AssistantMessageEventStream` additionally fills `durationMs` on the final message (monotonic clock, without overwriting an existing value or a timestamp earlier than stream creation).
3. `normalizeContext` (`ai/utils/transcript.ts:30-34`): the only producer of `TranscriptContext`; the agent calls it with `{messages}` only, passing no systemPrompt/tools (`agent-loop.ts:406`).
4. `createInitialSystemMessage` (`ai/utils/transcript.ts:10-23`): when systemPrompt is non-empty or tools is non-empty, produces `{role:"system", content: systemPrompt ?? "", toolsAdded?, timestamp: 0}`.
5. `getCurrentSystemMessage`/`getCurrentSystemPrompt` (`ai/utils/transcript.ts:68-102` + `ai/utils/text.ts:15-21`): replays all system messages in order; non-empty content is appended, sections are replaced/deleted by name; tools are replayed via `getCurrentTools`; the final text = content + each section value, joined with `\n\n`.
6. `getCurrentTools` (`ai/utils/transcript.ts:58-66`): apply `toolsRemoved` deletions and `toolsAdded` assignments in order, preserving Map order.
7. `getToolStateChanges`/`declarationsEqual`/`toToolDeclaration` (`ai/utils/transcript.ts:123-167`): a declaration keeps only name/description/parameters (JSON round-trip)/constrainedSampling; unequal declaration JSON counts as a remove + add.
8. `validateToolArguments` (`ai/utils/validation.ts:317-352`): `argumentsError` is reported first; after `structuredClone` it normalizes optional nulls, applies `Value.Convert` coercion, then typebox validation; on failure throws `Validation failed for tool "X":\n  - path: message\n\nReceived arguments:\n{json}`; tolerates JSON Schema coercion for non-typebox schemas.
9. `mergeClientIdentity`/`PI_ATTRIBUTION` (`ai/utils/identity.ts:24,49-59`): field-level merge, the latter wins, undefined is skipped.
10. `parseStreamingJson` (around `ai/utils/json-parse.ts:129-150`): `JSON.parse` first; on failure `repairJson` then parse; then fall back to `partial-json`; then partial-parse the repaired text; finally return `{}`. Always returns an object.
11. `contentText`/`getSystemMessageText` (`ai/utils/text.ts:6-21`).

### 1.4 Common misconceptions

- No `maxTurns`: the symbol does not exist anywhere in the package. See 4.5 for stopping conditions.
- No retries: the comment on `agentLoopContinue` says it is meant for retry (`agent-loop.ts:72-79`); actual retry/backoff lives in the caller (coding-agent's session layer).
- No separate attachment type: images are just `ImageContent` placed in `UserMessage.content`, and `prompt(string, images)` assembles `[{type:"text"},{type:"image"}]` (`agent.ts:422-439`).
- `transport` is only a string enum `"sse" | "websocket" | "websocket-cached" | "auto"` (`ai/types.ts:145`); Agent defaults to `"auto"` and passes it through to stream options (`agent.ts:230,259,485`); the actual transport is implemented by pi-ai providers.
- "subscription order" means Set insertion order (`agent.ts:196,619-621`), not an event bus.

## 2. Type model (field-exact)

### 2.1 Scalars/enums

| Type | Definition | Default/constraint | Evidence |
|---|---|---|---|
| `ThinkingLevel` | `"off" \| "minimal" \| "low" \| "medium" \| "high" \| "xhigh" \| "max"` | Agent state defaults to `"off"`; inside the loop `"off"` maps to `reasoning: undefined` | `types.ts:344-349`; `agent.ts:94,480` |
| `ToolExecutionMode` | `"sequential" \| "parallel"` | Agent defaults to `"parallel"`; the loop also defaults to parallel | `types.ts:40-47`; `agent.ts:261`; `agent-loop.ts:529` |
| `QueueMode` | `"all" \| "one-at-a-time"` | Both queues default to `"one-at-a-time"` | `types.ts:49-55`; `agent.ts:255-256` |
| `Transport` | `"sse" \| "websocket" \| "websocket-cached" \| "auto"` (pi-ai) | Agent defaults to `"auto"` | `ai/types.ts:145`; `agent.ts:259` |
| `StopReason` | `"pending" \| "stop" \| "length" \| "toolUse" \| "error" \| "aborted" \| "deferred"` | Only `error`/`aborted` trigger a hard exit; `length` makes all tools fail | `ai/types.ts:480`; `agent-loop.ts:254,277-279` |
| `Usage` | input/output/cacheRead/cacheWrite (+ optional cacheWrite1h, reasoning)/totalTokens/cost{input,output,cacheRead,cacheWrite,total} | All numbers, required (except optional fields) | `ai/types.ts:457-478` |
| `JsonValue`/`JsonObject` | `null \| boolean \| number \| string \| readonly JsonValue[] \| JsonObject`; `{[key:string]: JsonValue}` | Kotlin uses `JsonElement`/`JsonObject` | `ai/types.ts:482-483` |
| `Model<TApi>` | id,name,api,provider,baseUrl,input,cost,reasoning,contextWindow,maxTokens (+ optional type/inputLimits/headers/thinkingLevelMap/promptCache/samplingParams…) | See 2.9 for the Agent default model | `ai/types.ts:830-878` |
| `Tool` (pi-ai) | name,description,parameters(TSchema),constrainedSampling? | `AgentTool` extends it | `ai/types.ts:760-765` |
| `ToolCall` | type:"toolCall", id,name,arguments:JsonObject,thoughtSignature?,namespace?,argumentsError? | `partialJson` is an internal temporary field in the proxy (TS attaches it via cast, `proxy.ts:330-336,359`) | `ai/types.ts:442-455` |

### 2.2 LLM messages (pi-ai, `ai/types.ts:542-650`)

| Type | Required fields | Optional fields | Key semantics |
|---|---|---|---|
| `SystemMessage` | role:"system", content: string \| TextContent[], timestamp: number | sections?: Record<string,string\|null>, toolsAdded?: Tool[], toolsRemoved?: ToolReference[] | System messages in the transcript declare the prompt and tools; see 1.3/4.6 for replay semantics |
| `UserMessage` | role:"user", content: string \| (TextContent\|ImageContent)[], timestamp | none | Images go in the content array |
| `AssistantMessage` | role:"assistant", content:(TextContent\|ThinkingContent\|ToolCall)[], api, provider, model, usage, stopReason, timestamp | responseModel, responseId, providerThinkingLevel, thinkingLevel, diagnostics, deferred, errorMessage, rawStopReason, endTurn, durationMs | The loop writes `thinkingLevel` on the final message (`agent-loop.ts:419`) |
| `ToolResultMessage<TDetails>` | role:"toolResult", toolCallId, toolName, content:(TextContent\|ImageContent)[], isError, timestamp | details, usage, nestedCalls, durationMs | The loop uses `content ?? []` when generating (`agent-loop.ts:947`) |
| `Message` | Union of the four above | — | Output type of `convertToLlm` |
| `TranscriptContext` | `{messages: Message[]}` + private brand | — | Can only be produced by `normalizeContext` (`ai/types.ts:777-794`) |

### 2.3 `StreamFn` (`types.ts:19-37`)

```ts
type StreamFn = (model: Model<Api>, context: TranscriptContext,
                 options?: SimpleStreamOptions) => AssistantMessageEventStream | Promise<AssistantMessageEventStream>
```

Contract (caller assumptions that must be preserved verbatim):

- Must not throw or return a rejected promise on request/model/runtime failure; failures must be encoded as an `error` event inside the stream, with the final `AssistantMessage.stopReason` set to `"error"`/`"aborted"` and carrying an `errorMessage` (`types.ts:27-32`).
- The transcript it receives is already normalized: the system prompt and tool declarations are in system messages; `context.systemPrompt`/`context.tools` are not used.
- The returned stream's event protocol is in `ai/types.ts:796-828`: `start` must precede any update/done; `partial` is a shared live object, not a snapshot; text/thinking are empty at `*_start`, grow through `*_delta`, and become authoritative at `*_end`; `done` reasons are `stop|length|toolUse|deferred`, and `error` reasons are `aborted|error`.
- Even when streamFn breaks the contract and throws, Agent synthesizes a failure message (`agent.ts:542-558`), but the low-level `agentLoop` stream never ends (the `.then` at `agent-loop.ts:56-67` has no `.catch`). That is known TS behavior; the Kotlin side should instead complete the stream with an exception (see 8.10).

### 2.4 `AgentMessage` and the declaration-merging extension mechanism

TS definition (`types.ts:351-374`):

```ts
export interface CustomAgentMessages {}                       // empty interface for application extensions
export type AgentMessage = Message | CustomAgentMessages[keyof CustomAgentMessages];
declare module "@earendil-works/pi-agent-core" {
  interface CustomAgentMessages { artifact: ArtifactMessage; notification: NotificationMessage }
}
```

Mechanism and consequences:

- Type-level merge only: once `CustomAgentMessages` is declaration-merged on the application side, the `AgentMessage` union automatically includes the new message types; zero runtime cost.
- The loop makes only two structural checks on role: `role === "system"` (tool declaration merge/insert, `agent-loop.ts:344-349,369`) and `role === "assistant"` (continue validation, `agent-loop.ts:90-92,148-150`). Other custom messages are completely transparent to the loop and only go through `convertToLlm` filtering/conversion.
- coding-agent actually merges 4 roles: `bashExecution`, `custom`, `branchSummary`, `compactionSummary` (`ca/core/messages.ts:29-76`); `convertToLlm` turns them into user/toolResult messages or drops them at the application layer (around `ca/core/messages.ts:150-190`).

Kotlin representation options (A recommended; full rationale in 8.3):

- A (recommended): `interface AgentMessage { val role: String; val timestamp: Long }`; pi-ai's four core message classes implement it; pi-agent adds `interface CustomAgentMessage : AgentMessage` as the application extension point. `convertToLlm` takes `List<AgentMessage>` and filters/converts internally via `is Message`, while the core four are `sealed interface Message : AgentMessage` to preserve `when` exhaustiveness.
- B: generify into `Agent<M : AgentMessage>`/`AgentSession<M>`. Type-safest, but events, state, the loop, and tool results all get polluted by the type parameter, and `runToolCall`/`AgentEvent`, which coding-agent depends on, would be generic too — too expensive.
- C: a single `data class AppMessage(role, payload: JsonElement)` carrying all custom messages. Stays sealed, but loses per-type static typing and forces the application to parse payloads by hand.
- Conclusion: A is closest to TS semantics; `role: String` keeps wire/persistence compatibility; putting `AgentMessage` in pi-ai is a necessary module-direction compromise (Kotlin has no structural union types, and pi-ai cannot depend on pi-agent). See 8.2.

### 2.5 Tool type family (`types.ts:423-499`)

`AgentToolResult<T = JsonValue | undefined>`:

| Field | Type | Required | Semantics |
|---|---|---|---|
| `content` | `(TextContent \| ImageContent)[]` | Yes | Text/images returned to the model |
| `details` | `T` | Yes | Structured detail for logs/UI; `T` defaults to `JsonValue \| undefined` |
| `structuredContent` | `JsonValue` | No | Machine-readable result matching `outputSchema`; not sent to the model |
| `usage` | `Usage` | No | The tool's own execution cost; not part of the main LLM context accounting |
| `isError` | `boolean` | No | Report failure without throwing; the model sees an error result |
| `terminate` | `boolean` | No | Suggests ending this batch; only takes effect when every final result in the batch is true |

`AgentTool<TParameters extends TSchema = TSchema, TDetails = any> extends Tool<TParameters>` (`types.ts:465-499`):

| Field | Type | Required | Semantics/evidence |
|---|---|---|---|
| `name`/`description`/`parameters` | from pi-ai `Tool` | Yes | The schema drives validation |
| `label` | `string` | Yes | UI display |
| `prepareArguments` | `(args: unknown) => Static<TParameters>` | No | Compatibility shim before validation; when it returns the same reference the loop does not replace the toolCall (`agent-loop.ts:704-716`) |
| `outputSchema` | `TSchema` | No | Schema for `structuredContent` |
| `execute` | `(toolCallId, params, signal?, onUpdate?) => Promise<AgentToolResult<TDetails>>` | Yes | Throw or return `isError: true` on failure; do not merely describe the failure in content (`types.ts:479-488`) |
| `replay` | `"never" \| "safe"` | No | Recovery strategy when persisted intent exists but the outcome is unknown; unused in this package |
| `executionMode` | `"sequential" \| "parallel"` | No | Per-tool override; any sequential tool makes the whole batch serial (`agent-loop.ts:526-530`) |

Other:

- `AgentToolCall` = the extraction of `{type:"toolCall"}` from assistant content (`types.ts:57-58`).
- `AgentToolCallOutcome` = `{toolCall, result, isError, durationMs?}`; `durationMs` is monotonic milliseconds for `execute()`, excluding hooks, absent when it did not execute (`types.ts:448-455`).
- `AgentToolUpdateCallback<T> = (partialResult) => void`; its scope is the current `execute()` call, and calls after the tool promise settles are ignored (`types.ts:457-463`; implementation `agent-loop.ts:831-867`).

### 2.6 Hook types (`types.ts:60-158`)

| Type | Fields | Notes |
|---|---|---|
| `BeforeToolCallResult` | `block?`, `reason?`, `terminate?` | `block:true` prevents execution and produces an error tool result, with reason as the text; terminate participates in batch termination (`agent-loop.ts:755-765`) |
| `AfterToolCallResult` | `content?`, `details?`, `structuredContent?`, `isError?`, `usage?`, `terminate?` | Field-level replacement, no deep merge; **if content is provided without structuredContent, structuredContent is dropped** (`agent-loop.ts:893-906`) |
| `BeforeToolCallContext` | `assistantMessage`, `toolCall`, `args` (already validated), `context: AgentContext` | `types.ts:106-116` |
| `AfterToolCallContext` | The above + `result: AgentToolResult<any>`, `isError: boolean` | `types.ts:118-132` |
| `AgentTurnContext` | `message: AssistantMessage`, `toolResults: ToolResultMessage[]`, `context: AgentContext`, `newMessages: AgentMessage[]` | `newMessages` are "the messages this loop invocation would return if it exited now"; a prompt run includes the initial prompt message, a continue run excludes pre-existing context (`types.ts:134-144`) |
| `AgentTurnDecision` | `{action:"continue"} \| {action:"end"}` | `finishTurn` returning undefined means keep normal scheduling (`types.ts:146-147`) |
| `FinishTurn` | `(turn, signal?) => AgentTurnDecision \| void \| Promise<...>` | Called after the assistant + all tool results and before `turn_end`; also called on error/aborted but the decision is ignored (`types.ts:149-158`) |
| `AgentLoopTurnUpdate` | `context?`, `messages?`, `model?`, `thinkingLevel?` | Replaces runtime state for the next request; messages go through the normal lifecycle events (`types.ts:160-170`) |
| `PrepareRequestContext` | `context`, `model`, `thinkingLevel` | Snapshot before the request (`types.ts:172-177`) |
| `AgentRequestUpdate` | `Omit<AgentLoopTurnUpdate,"messages">` | Request replacement (`types.ts:179-180`) |
| `PrepareRequest` | `(request, signal?) => AgentRequestUpdate \| void \| Promise<...>` | Called before every request, including the first; pending messages have already been appended and their events emitted; does not poll queues (`types.ts:182-189`) |
| `PrepareNextTurnContext` | `extends AgentTurnContext` | `types.ts:191` |

### 2.7 `AgentLoopConfig` (`types.ts:193-342`)

`extends SimpleStreamOptions` (so the loop config object itself is stream options, and `streamAssistantResponse` passes `{...config, apiKey, identity, signal}` straight through, `agent-loop.ts:412-417`).

| Field | Type | Required/default | Timing and contract |
|---|---|---|---|
| `model` | `Model<any>` | Required | Every request |
| `convertToLlm` | `(AgentMessage[]) => Message[] \| Promise<Message[]>` | Required | Before every request; must not throw/reject, otherwise the low-level loop breaks with no normal event sequence (`types.ts:196-222`) |
| `transformContext` | `(AgentMessage[], signal?) => Promise<AgentMessage[]>` | Optional | Before `convertToLlm`; affects only this request and is not written back to loop context; must not throw (`types.ts:224-244`; `agent-loop.ts:397-401`) |
| `getApiKey` | `(provider) => Promise<string\|undefined> \| string \| undefined` | Optional | Every request; uses the return value or `config.apiKey`; must not throw (`types.ts:246-254`; `agent-loop.ts:408-410`) |
| `finishTurn` | `FinishTurn` | Optional | See 2.6/4.5 |
| `prepareRequest` | `PrepareRequest` | Optional | Before every request (including the first), after pending messages; the returned context/model/thinkingLevel replace state and persist for later requests in this run; does not poll queues (`types.ts:266-271`; `agent-loop.ts:228-248`) |
| `prepareNextTurn` | `(context) => AgentLoopTurnUpdate \| undefined \| Promise<...>` | Optional | After `turn_end`, when the next turn starts; returned messages are appended with normal message events (`types.ts:273-280`; `agent-loop.ts:194-217`) |
| `getSteeringMessages` | `() => Promise<AgentMessage[]>` | Optional | At run start, after each turn, and after `prepareNextTurn` (only when pending is empty); must not throw, returns `[]` when there are none (`types.ts:282-293`) |
| `getFollowUpMessages` | `() => Promise<AgentMessage[]>` | Optional | Only when the agent would otherwise stop (no tools, no steering); must not throw (`types.ts:295-306`) |
| `toolExecution` | `ToolExecutionMode` | `"parallel"` by default | Batch-level strategy (`types.ts:308-317`) |
| `beforeToolCall` | `(BeforeToolCallContext, signal?) => Promise<BeforeToolCallResult\|undefined>` | Optional | After argument validation, before execution; `block:true` prevents execution; the hook is responsible for responding to abort (`types.ts:319-326`) |
| `afterToolCall` | `(AfterToolCallContext, signal?) => Promise<AfterToolCallResult\|undefined>` | Optional | After execution, before `tool_execution_end` and the toolResult message events; field-level replacement only (`types.ts:328-341`) |
| Remaining `SimpleStreamOptions` fields | See pi-ai | Optional | Passed through to streamFn; Agent sets only some of them (see 2.9) |

### 2.8 `AgentContext`/`AgentState`/`AgentInitialState`/`AgentOptions`

`AgentContext` (`types.ts:501-507`): `messages: AgentMessage[]`, `tools?: AgentTool<any>[]` (optional). This is the loop's input snapshot; `context.tools` is the executable set, while system messages in the transcript declare the model-visible set.

`AgentState` (`types.ts:376-421`):

| Member | Type | Access | Semantics/evidence |
|---|---|---|---|
| `systemPrompt` | `string` | readonly getter | Replayed from transcript system messages (`agent.ts:90-92`); to change the prompt, append a system message (content or sections) |
| `model` | `Model<any>` | read/write | Used by subsequent turns |
| `thinkingLevel` | `ThinkingLevel` | read/write | Requested reasoning level |
| `tools` | `AgentTool<any>[]` | getter+setter | The setter copies the top-level array; the getter returns the internal array (`agent.ts:95-100`) |
| `messages` | `AgentMessage[]` | getter+setter | The setter copies the top-level array; the getter returns the internal array (`agent.ts:101-106`) |
| `isStreaming` | `boolean` | readonly | True from run start until the run fully settles (including agent_end listeners); `agent.ts:529,560-566` |
| `streamingMessage` | `AgentMessage?` | readonly | The most recent message that has had `message_start` but not `message_end` (any role gets set in practice, `agent.ts:577-586`) |
| `pendingToolCalls` | `ReadonlySet<string>` | readonly | toolCallIds currently executing; replaced with a new Set on every event (`agent.ts:590-602`) |
| `errorMessage` | `string?` | readonly | Error from the most recent failed/aborted turn; cleared at run start and written from assistant.errorMessage at `turn_end` (`agent.ts:531,604-608`) |

`AgentInitialState = Partial<Omit<AgentState,"pendingToolCalls"|"isStreaming"|"streamingMessage"|"errorMessage">>` (`agent.ts:78-81`): `systemPrompt?`, `model?`, `thinkingLevel?`, `tools?`, `messages?`.

`AgentOptions` (`agent.ts:114-147`):

| Field | Type/default | Notes |
|---|---|---|
| `initialState` | `AgentInitialState?` | See 5.1 construction rules |
| `convertToLlm` | Defaults to `defaultConvertToLlm` (keeps messages whose role is system/user/assistant/toolResult, `agent.ts:39-47`) | — |
| `transformContext` | Optional | — |
| `streamFn` | Type-required; at runtime falls back to `getDefaultStreamFn()` (`agent.ts:244`) | — |
| `getApiKey`, `onPayload`, `onResponse`, `onProviderStreamEvent` | Optional | Forwarded to stream options |
| `beforeToolCall`, `afterToolCall`, `finishTurn`, `prepareRequest` | Optional | Forwarded to the loop config |
| `prepareNextTurn` | Optional, old signature `(signal?) => update` | Mutually exclusive with `prepareNextTurnWithContext`, which takes precedence (`agent.ts:494-502`) |
| `prepareNextTurnWithContext` | Optional, new signature `(context, signal?) => update` | — |
| `steeringMode`/`followUpMode` | `"one-at-a-time"` by default | — |
| `sessionId`, `thinkingBudgets`, `transport` (`"auto"` by default), `maxRetryDelayMs`, `toolExecution` (`"parallel"` by default), `identity` | Optional | Forwarded as-is |

`Agent`'s own writable public fields (`agent.ts:200-236`): `convertToLlm`, `transformContext`, `streamFunction`, `getApiKey`, `onPayload`, `onResponse`, `onProviderStreamEvent`, `beforeToolCall`, `afterToolCall`, `finishTurn`, `prepareRequest`, `prepareNextTurn`, `prepareNextTurnWithContext`, `sessionId`, `thinkingBudgets`, `transport`, `maxRetryDelayMs`, `toolExecution`, `identity`. coding-agent installs hooks precisely by assigning these fields directly (see 7.2).

### 2.9 `AgentEvent` (`types.ts:509-539`)

| Variant | Payload | When emitted |
|---|---|---|
| `agent_start` | none | First event of a run; before the initial message events (`agent-loop.ts:126`) |
| `agent_end` | `messages: AgentMessage[]` | Last event of a run; the payload is this loop invocation's `newMessages` (prompt runs include the initial prompt; continue runs include only new messages; the Agent failure path includes only the synthesized failure message) |
| `turn_start` | none | Start of each turn; the first turn is emitted by the entry function (`agent-loop.ts:127,156`), later ones after prepareNextTurn and before pending message events (`agent-loop.ts:216`) |
| `turn_end` | `message: AgentMessage` (an AssistantMessage in practice), `toolResults: ToolResultMessage[]` | After each turn's assistant + all tool results; after finishTurn (`agent-loop.ts:262,296`) |
| `message_start` | `message: AgentMessage` | Start of a system (tool declaration), user, assistant (stream start or synthesized), or toolResult message |
| `message_update` | `message: AgentMessage`, `assistantMessageEvent: AssistantMessageEvent` | Only during assistant streaming, and only after `start` has been seen (`agent-loop.ts:442-451`) |
| `message_end` | `message: AgentMessage` | End of each message; the final assistant message; toolResult after tool_execution_end |
| `tool_execution_start` | `toolCallId`, `toolName`, `args: any` | Tool call starts; `args` are the assistant toolCall's raw arguments (before prepare/validation) |
| `tool_execution_update` | `toolCallId`, `toolName`, `args`, `partialResult: any` | When the tool calls `onUpdate`; the sink is awaited and updates after settle are ignored |
| `tool_execution_end` | `toolCallId`, `toolName`, `result: AgentToolResult`, `isError: boolean`, `durationMs?: number` | After the tool is finalized; `durationMs` exists only when it actually executed |

Strict rules for event emission order are in section 3.

### 2.10 proxy types (`proxy.ts:20-70`)

`ProxyAssistantMessageEvent`: events sent by the server with the partial field removed to save bandwidth:

| Variant | Fields |
|---|---|
| `start` | none |
| `text_start` / `thinking_start` | `contentIndex` |
| `text_delta` / `thinking_delta` | `contentIndex`, `delta` |
| `text_end` / `thinking_end` | `contentIndex`, `contentSignature?` |
| `toolcall_start` | `contentIndex`, `id`, `toolName` |
| `toolcall_delta` | `contentIndex`, `delta` |
| `toolcall_end` | `contentIndex`, `toolCall: ToolCall` |
| `done` | `reason: "stop"\|"length"\|"toolUse"`, `usage`, `providerThinkingLevel?` |
| `error` | `reason: "aborted"\|"error"`, `errorMessage?`, `usage`, `providerThinkingLevel?` |

`ProxySerializableStreamOptions = Pick<SimpleStreamOptions, temperature|samplingParams|maxTokens|reasoning|cacheRetention|sessionId|headers|metadata|transport|thinkingBudgets|maxRetryDelayMs>` (`proxy.ts:48-61`).

`ProxyStreamOptions` = the above + `signal?`, `authToken: string`, `proxyUrl: string` (`proxy.ts:63-70`).

## 3. Complete event flow spec

### 3.1 General rules

1. The sink is async: `AgentEventSink = (event) => Promise<void> | void` (`agent-loop.ts:35`), and every `emit(...)` is awaited. Agent's sink is `processEvents`, which first reduces internal state and then serially awaits each subscriber in registration order (`agent.ts:575-622`).
2. A run starts with one `agent_start` and ends with one `agent_end`; no loop events follow `agent_end`, but `Agent`'s `isStreaming` only flips to false after all `agent_end` subscribers settle (`agent.ts:559-566`; test `test/agent.test.ts:362-433`).
3. Message lifecycle events cover system→user→assistant→toolResult. `message_update` is only for assistant streaming updates; system/user/toolResult only have start/end.
4. Turns are wrapped by `turn_start`/`turn_end`. The first `turn_start` is emitted by the entry function immediately after agent_start (`agent-loop.ts:127,156`); later turns are emitted after `prepareNextTurn` and before pending message events (`agent-loop.ts:216`).
5. `agent_end.messages` are "all messages added by this loop invocation": prompt runs include the initial prompt and any system messages synthesized during the loop (`agent-loop.ts:119-124`), while continue runs include only new messages (`agent-loop.ts:152`). In the Agent failure path, `agent_end.messages` contains only the synthesized failure assistant message (`agent.ts:557`).
6. Message objects in event payloads share references with internal context (top-level shallow copies are covered in 3.3), so subscribers should treat them as read-only.

### 3.2 Exact sequences

(a) `prompt("Hello")`, no tools, normal stop (README and `test/agent-loop.test.ts:114-158`):

```text
agent_start
turn_start
message_start{user}
message_end{user}
message_start{assistant}          # emitted at stream start when the stream has a start event; synthesized at done otherwise
message_update{assistant} * N     # only when there are streaming deltas
message_end{assistant}
turn_end{message: assistant, toolResults: []}
agent_end{messages: [user, assistant]}
```

(b) A tool call followed by a final answer (`README.md` Event Flow section; `test/agent-loop.test.ts:305-399`):

```text
agent_start
turn_start
message_start/end{user}
message_start{assistant}
message_update* 
message_end{assistant(toolUse)}
tool_execution_start{id,name,args}
tool_execution_update*            # when the tool calls onUpdate
tool_execution_end{id,name,result,isError,durationMs?}
message_start{toolResult}
message_end{toolResult}
turn_end{message: assistant, toolResults: [toolResult]}
turn_start                        # next turn
message_start{assistant}
message_update*
message_end{assistant}
turn_end{message: assistant, toolResults: []}
agent_end{messages: [user, assistant, toolResult, assistant]}
```

Note: if `context.tools` differs from the tools declared in the transcript, `newMessages` has an extra synthesized tool-declaration system message before the user message; the role sequence in (b) then becomes `[system,user,assistant,toolResult,assistant]`. Test `test/agent-loop.test.ts:1732-1781` uses terminate to stop after the first turn and asserts the sequence `[system,user,assistant,toolResult]`.

(c) Exact sequence when `finishTurn` returns `{action:"end"}` (quoted from the test assertion, `test/agent-loop.test.ts:1713-1729`):

```text
agent_start, turn_start,
message_start/end{system(tool declaration)},   # synthesized when context.tools differs from the transcript declarations
message_start/end{user},
message_start/end{assistant},
tool_execution_start, tool_execution_end,
message_start/end{toolResult},
turn_end, agent_end
```

The case also asserts that the `messages` roles are `["system","user","assistant","toolResult"]`, showing that the tool-declaration system message enters both the return messages and the new context (`test/agent-loop.test.ts:1710-1713`).

(d) `continue()` resumes from a user/toolResult tail (`test/agent-loop.test.ts:2031-2070`):

```text
agent_start
turn_start
# no user message events
message_start/end{assistant}
turn_end
agent_end
```

The test asserts there is exactly one message_end and that it is the assistant (`test/agent-loop.test.ts:2062-2069`).

(e) Assistant `stopReason` is `error`/`aborted` (tests `test/agent-loop.test.ts:1162-1215`, `test/agent.test.ts:331-360`, `test/e2e.test.ts:208-219`):

```text
agent_start, turn_start,
message_start/end{user},
message_start/end{assistant(error|aborted)},
# finishTurn is called here (no events)
turn_end{message: assistant, toolResults: []},
agent_end{messages: [...]}
```

This path does not poll steering/follow-up and ignores `finishTurn`'s decision (`agent-loop.ts:254-265`; the test's call counts are providerCalls=1, followUpPolls=0).

(f) streamFn breaks the contract and throws (Agent layer, `test/agent.test.ts:331-360`):

```text
agent_start, turn_start,
message_start/end{user},
message_start/end{assistant(stopReason:"error", errorMessage:"provider exploded")},
turn_end, agent_end
```

The failure message is synthesized by `handleRunFailure` (`agent.ts:542-558`), and `agent.state.errorMessage` is written at the same time.

### 3.3 How streaming partials are constructed (line by line)

`streamAssistantResponse` (`agent-loop.ts:386-479`) is the only place that maps `AssistantMessageEvent` to `AgentEvent`:

1. Before the request: `transformContext` (optional, AgentMessage→AgentMessage, affects only this request and is not written back to context) → `convertToLlm` (required, AgentMessage→Message) → `normalizeContext({messages})` → `getApiKey` (fetched per request, for expired OAuth tokens) → `streamFn(model, llmContext, {...config, apiKey, identity: mergeClientIdentity(DEFAULT_AGENT_IDENTITY, config.identity), signal})` (`agent-loop.ts:397-417`).
2. `start` event: `partialMessage = event.partial`, push it into `context.messages` (same reference), set `addedPartial = true`, and emit `message_start{message: {...partialMessage}}` (a top-level shallow copy) (`agent-loop.ts:426-431`).
3. text/thinking/toolcall start/delta/end events: if a partial already exists, set `partialMessage = event.partial`, replace the last element of `context.messages`, and emit `message_update{message: {...partialMessage}, assistantMessageEvent: event}`. `message` is a shallow copy; `assistantMessageEvent.partial` is pi-ai's "shared live object", not a snapshot at event time (`ai/types.ts:796-811`). When `start` has not been seen, these events are ignored (`agent-loop.ts:442-451`).
4. `done`/`error`: `finalMessage = await response.result()`, then `Object.assign(finalMessage, {thinkingLevel: config.reasoning ?? "off"})` — that is, every final assistant message records this request's thinking level, either `"off"` or the requested level (`agent-loop.ts:418-419,453-466`). If a partial was added earlier, replace the last array element with finalMessage (no second message_start); otherwise push it and synthesize `message_start{...finalMessage}`. Finally emit `message_end{message: finalMessage}` (`message_end` passes finalMessage directly, with no shallow copy).
5. If stream iteration ends naturally without done/error: the same wrap-up logic as 4 runs (`agent-loop.ts:470-478`). If the stream neither ends nor produces a terminal state, `response.result()` hangs forever — the proxy synthesizes an error event at EOF precisely to avoid this hang (`proxy.ts:219-230`).
6. `durationMs` is not set by the loop; `AssistantMessageEventStream` fills it in when pushing done/error or on `end(result)` (monotonic clock, without overwriting an existing value or a message older than the stream's creation time; `ai/utils/event-stream.ts:91-130`).
7. Message-end payloads share the same object as the context; `message_update.message`'s `content` array is shared with the live partial (shallow copy). If Kotlin models messages as immutable data classes, specify and pin the "shared/copied" policy in tests; matching TS is recommended: top-level copy of the message (`copy()` shares the content list), with the live object owned by the pi-ai stream.

### 3.4 Ordering and settlement as observed by subscribers

- `Agent.subscribe(listener)` puts the listener in a `Set` and returns `() => this.listeners.delete(listener)` (typed `() => void`, but actually returns boolean; `agent.ts:275-278`).
- Registration order is call order; each event's listeners are awaited one by one before the next event is processed (`agent.ts:619-621`). There are no initial events, and state mutators do not emit events (test `test/agent.test.ts:309-329`).
- A listener receives `(event, signal)`, where `signal` is the current run's AbortController signal; without an activeRun, `processEvents` throws `"Agent listener invoked outside active run"` (`agent.ts:615-618`).
- A listener throwing propagates up through the sink and becomes a run failure: Agent runs `handleRunFailure` and emits another round of `message_start/message_end/turn_end/agent_end` (possibly invoking the throwing listener again); if failure handling throws again, `prompt()` rejects with that error while `finishRun` still runs (`agent.ts:517-540,542-558`).
- The low-level `agentLoop`'s `.then` has no `.catch` (`agent-loop.ts:56-67`): if the sink/loop throws, the returned EventStream never `end`s and consumers wait forever. The Kotlin port should complete the stream with an exception instead (see 8.10).
- JS `Set`'s `for..of` visits elements added during iteration; Agent does not snapshot listeners. Kotlin iterating a `LinkedHashSet` directly would throw a CME, so snapshotting is recommended (an allowed deviation with no test coverage).

## 4. agent-loop.ts state machine

### 4.1 Entry points and data flow

- `agentLoop` (`agent-loop.ts:47-70`): `createAgentStream()` creates `EventStream<AgentEvent, AgentMessage[]>` (completion condition `agent_end`, result taken from `agent_end.messages`, `162-167`); it starts `runAgentLoop` immediately with a sink that only does `stream.push`; after the promise settles it calls `stream.end(messages)` and returns the stream.
- `agentLoopContinue` (`80-109`): first synchronously validates `context.messages.length > 0` and that the last role is not `assistant`, otherwise it throws synchronously (`86-92`); after that it matches `agentLoop`.
- `runAgentLoop` (`111-135`): `initialMessages = declareToolChanges(context, prompts)`; `newMessages = [...initialMessages]`; `currentContext = {...context, messages: [...context.messages, ...initialMessages]}` (a new array); emits `agent_start`, `turn_start`, then start/end for each initialMessages entry; enters `runLoop`; returns `newMessages`.
- `runAgentLoopContinue` (`137-160`): same validation; `newMessages = []`; `currentContext = {...context}` — note that the `messages` array is not copied here, so the loop pushes directly into the caller's array; emits `agent_start`, `turn_start` (no message events); enters `runLoop`.
- `streamFn ?? getDefaultStreamFn()` is resolved before calling `runLoop` (`133`, `158`), so the global default is captured at run start.

### 4.2 Complete `runLoop` control flow (`agent-loop.ts:169-330`)

```text
currentContext = initialContext; config = initialConfig
lastCompletedTurn = undefined; explicitContinuation = false
pendingMessages = await getSteeringMessages?.() || []        # poll point 1: run start

while (true) {                                               # outer loop: follow-up revival
  hasMoreToolCalls = true
  while (hasMoreToolCalls || pendingMessages.length > 0) {   # inner loop: driven by tools/steering
    preparedMessages = []
    if (lastCompletedTurn) {                                  # not the first turn
      snapshot = await prepareNextTurn?.(lastCompletedTurn)
      if (snapshot) { merge context/messages/model/reasoning }
      if (pendingMessages.length === 0)                       # poll point 2
        pendingMessages = await getSteeringMessages?.() || []
      await emit(turn_start)
    }
    for (m of declareToolChanges(currentContext, [...preparedMessages, ...pendingMessages]))
      { await emit(message_start); await emit(message_end); context.messages.push(m); newMessages.push(m) }
    pendingMessages = []
    requestUpdate = await prepareRequest?.({context, model, thinkingLevel: config.reasoning ?? "off"}, signal)
    if (requestUpdate) { merge context/model/reasoning }
    assistant = await streamAssistantResponse(currentContext, config, signal, emit, streamFunction)
    newMessages.push(assistant)
    if (assistant.stopReason === "error" || assistant.stopReason === "aborted") {
      lastCompletedTurn = {message: assistant, toolResults: [], context: currentContext, newMessages}
      await finishTurn?.(lastCompletedTurn, signal)
      await emit(turn_end, toolResults: [])
      await emit(agent_end, messages: newMessages)
      return
    }
    toolCalls = assistant.content.filter(type === "toolCall")
    toolResults = []; hasMoreToolCalls = false
    if (toolCalls.length > 0) {
      batch = stopReason === "length" ? failToolCallsFromTruncatedMessage(...)
                                      : executeToolCalls(...)
      toolResults.push(...batch.messages)
      hasMoreToolCalls = !batch.terminate
      for (r of toolResults) { context.messages.push(r); newMessages.push(r) }
    }
    lastCompletedTurn = {message: assistant, toolResults, context: currentContext, newMessages}
    decision = await finishTurn?.(lastCompletedTurn, signal)  # poll point: finishTurn
    await emit(turn_end, message: assistant, toolResults)
    if (decision?.action === "end") { await emit(agent_end); return }
    explicitContinuation = decision?.action === "continue"
    pendingMessages = await getSteeringMessages?.() || []      # poll point 3
    if (hasMoreToolCalls || pendingMessages.length > 0) explicitContinuation = false
  }
  followUpMessages = await getFollowUpMessages?.() || []       # poll point 4: natural stop point
  if (followUpMessages.length > 0) { explicitContinuation = false; pendingMessages = followUpMessages; continue }
  if (explicitContinuation) { explicitContinuation = false; continue }  # one extra context-only request
  break
}
await emit(agent_end, messages: newMessages)
```

Key points:

- The first turn does not call `prepareNextTurn` (`lastCompletedTurn` is empty); later turns call it before `turn_start` (`agent-loop.ts:194-217`).
- `prepareNextTurn`'s returned `messages` are concatenated with steering pending messages, passed through `declareToolChanges`, then emitted one by one as start/end while also being written into both `currentContext.messages` and `newMessages` (`220-225`). Order: prepared first, pending after.
- Model/reasoning-level merge rules: `model: new ?? config.model`; `thinkingLevel === undefined` keeps `config.reasoning`, `"off"` becomes `undefined`, otherwise the new value is used (`199-208`, `236-248`).
- `prepareRequest` is called after pending message events and before the request; the returned context/model/thinkingLevel permanently replace `currentContext`/`config` for later requests in this run (`228-248`).
- `error`/`aborted` are a hard exit: `finishTurn` first, then `turn_end`, then `agent_end`; queues are not polled and the finishTurn decision is ignored (`254-265`).
- `stopReason === "length"`: all tool calls fail without executing (`276-279`, `481-513`).
- A tool batch with `terminate=true` only affects `hasMoreToolCalls`; steering/follow-up/explicit continuation can still keep the loop going (`281`, `304-307`, `310-323`).
- `explicitContinuation` is finishTurn's continue decision: if tool results, steering, or follow-up already naturally provide the next request, it is set to false; otherwise the outer loop runs again, the inner loop enters with `hasMoreToolCalls=true`, and one context-only request is made (test `test/agent-loop.test.ts:1273-1305`).
- That context-only turn calls `prepareNextTurn` again (`lastCompletedTurn` is not cleared); this is an observable call-count detail the port must preserve (see 8.9 B17).
- `agent_end` is emitted in exactly three places: hard exit (`263`), finishTurn end (`299`), and normal natural stop (`329`).

### 4.3 `declareToolChanges` (`agent-loop.ts:332-384`)

Purpose: `context.tools` is the "executable set", and system messages in the transcript declare the "model-visible set"; when they differ, a system message announces the additions/removals to the model before this request. Algorithm:

1. In `pendingMessages`, search backwards for the last `role === "system"` message (`342-350`).
2. If there is a pending system message, build `baseline`: copy it and clear `toolsAdded`/`toolsRemoved` (`withToolChanges(pending, NO_CHANGES)`), leaving other messages as-is; otherwise `baseline = pendingMessages` (`351-355`).
3. `changes = getToolStateChanges(getCurrentTools([...context.messages, ...baseline]), (context.tools ?? []).map(toToolDeclaration))` (`356-359`). That is, "committed transcript + pending messages with tool fields stripped" is the old state, compared against the executable set.
4. `unchanged = both lists are empty` (`360`).
5. If a pending system message exists (`362-366`):
   - If unchanged and the message had no tool fields originally → return `pendingMessages` as-is (preserving the caller's objects).
   - Otherwise return a mapped `baseline`, replacing that position with `withToolChanges(pending, changes)` — content/sections are preserved and tool fields are overwritten with the recomputed diff.
6. If no pending system message exists (`367-371`):
   - unchanged → return as-is;
   - otherwise create `{role:"system", content:"", timestamp: Date.now(), toolsAdded?, toolsRemoved?}` and insert it before the first non-system message (append at the end if everything is system).
7. `withToolChanges` (`376-384`): copy the message, drop the old tool fields, and add only non-empty lists.
8. `NO_CHANGES = {toolsAdded: [], toolsRemoved: []}` (`374`).

Corollaries (fixed by tests):

- The executable set takes precedence over intent declared in the caller's pending messages (`test/agent.test.ts:247-280`).
- Existing content/sections in a pending system message are preserved and the delta is merged in (`test/agent.test.ts:212-245`).
- The synthesized message's `toolsAdded` are stripped declarations (no `execute`/`label`, etc.; `toToolDeclaration` keeps only name/description/parameters/constrainedSampling; `test/agent.test.ts:199-210`).
- The function runs once at entry (prompts) and once per turn when processing pending messages; the emitted system message enters the transcript, so later replays naturally include it.

### 4.4 Tool execution (`agent-loop.ts:515-959`)

Strategy selection (`518-533`):

```text
toolCalls = blocks in assistantMessage.content with type==="toolCall"
hasSequentialToolCall = any toolCall maps to executionMode === "sequential" in currentContext.tools
if (config.toolExecution === "sequential" || hasSequentialToolCall) → serial
else → parallel
```

Serial (`540-594`), per call:

1. `emit(tool_execution_start{toolCallId, toolName, args: toolCall.arguments})` — args are the raw arguments, before prepare/validation.
2. `prepareToolCall(...)` (see below) → immediate or prepared.
3. immediate: used directly as finalized.
4. prepared: `executePreparedToolCall` (execute, collect onUpdate) → `finalizeExecutedToolCall` (afterToolCall merge).
5. `emit(tool_execution_end)`; `createToolResultMessage`; `emit(message_start)`; `emit(message_end)`; collect finalized/message.
6. After each call, check `signal?.aborted` and break if aborted; **the remaining calls get no events or results at all**. finishTurn/turn_end still run afterwards; `hasMoreToolCalls` is still `!terminate` (usually true), so the loop issues another request with the already-aborted signal, and toolCalls in the assistant message that were never executed have no matching toolResult in the transcript (TS behavior as-is; providers may reject it — do not "fix" this in the port).

Parallel (`596-670`), two phases:

1. Preflight (serial): per call, `emit(tool_execution_start)` → `prepareToolCall`.
   - immediate: `emit(tool_execution_end)` right away, add to finalized; break if aborted.
   - prepared: enqueue a lazy thunk (do not execute); break if aborted.
2. Execution phase: `Promise.all(entries.map(entry => typeof entry === "function" ? entry() : Promise.resolve(entry)))` — all thunks start together in microtasks (concurrent). At the start of each thunk, if `signal.aborted`, produce an `Operation aborted` error result, emit `tool_execution_end`, and return; otherwise execute + finalize + `tool_execution_end`.
3. Wrap-up phase: in the order of `Promise.all` results (= assistant source order), `createToolResultMessage` → `emit(message_start)` → `emit(message_end)` for each, collecting them as `messages`.
4. `terminate = shouldTerminateToolBatch(orderedFinalizedCalls)`, i.e. finalized count > 0 and every `result.terminate === true` (`700-702`).

Observable ordering in parallel mode: `tool_execution_start` in source order (during preflight), `tool_execution_end` in completion order, toolResult messages in source order, and `turn_end.toolResults` also in source order (test `test/agent-loop.test.ts:668-760` asserts end order `["tool-2","tool-1"]` and message order `["tool-1","tool-2"]`).

`prepareToolCall` (`718-787`), exact steps:

1. Look up the tool by name in `currentContext.tools`; if not found → immediate error `Tool ${name} not found`, `isError: true` (`726-733`) — start has already been emitted, end follows, and no hook runs.
2. try:
   a. `prepareArguments` (if present) produces `preparedToolCall`; if it returns the same reference, keep the original toolCall, otherwise overwrite with the new arguments (`704-716`).
   b. `validateToolArguments(tool, preparedToolCall)` → `validatedArgs`; a throw goes to catch.
   c. If `beforeToolCall` is configured: await it with context `{assistantMessage, toolCall: the original toolCall, args: validatedArgs, context}` (note: not the prepared toolCall), passing the signal through.
   d. After the hook returns, if `signal.aborted` → immediate `Operation aborted` (abort takes precedence over block, `748-754`).
   e. If `beforeResult.block` → error result text `reason || "Tool execution was blocked"`; if `terminate === true`, write terminate onto the result (`755-765`).
3. Check aborted again after the hook → `Operation aborted` (`767-773`).
4. Return prepared `{toolCall: preparedToolCall, tool, args: validatedArgs}` (`774-779`).
5. catch (prepareArguments throw, validation failure, beforeToolCall throw) → immediate error with text `error instanceof Error ? error.message : String(error)`, `isError: true` (`780-786`). So hook/validation exceptions surface as an error tool result rather than a run failure.
6. A call that is `block`ed does not run `afterToolCall` and its duration is not measured (the hookCalls assertion in `test/agent-loop.test.ts:2167-2212`).
7. `beforeToolCall` may mutate `args` in place; there is **no re-validation** afterwards, and execute receives the mutated object (`test/agent-loop.test.ts:528-587`).

`executePreparedToolCall` (`831-867`):

- `startedAt = performance.now()`; `acceptingUpdates = true`; the onUpdate callback collects `Promise.resolve(onUpdate(partialResult))` into `updateEvents`; after settle, `acceptingUpdates = false` and `await Promise.all(updateEvents)`.
- Success: `{result, isError: result.isError === true, durationMs: round(now-startedAt)}`.
- Exception: an error result (exception message), `isError: true`, still with `durationMs`; pending updates are still awaited.
- `finally` sets `acceptingUpdates = false` again. `onUpdate` calls after the tool settles are silently ignored (`test/agent.test.ts:473-612`).
- If an update promise rejects, the same catch captures it and the successful result is replaced with an error result (TS semantics; Kotlin must preserve this).

`finalizeExecutedToolCall` (`869-920`):

- When `afterToolCall` exists, await it; merge rules (`893-907`):
  - `structuredContent = afterResult.structuredContent ?? (afterResult.content ? undefined : result.structuredContent)`
  - `content/details/usage/terminate` individually `afterResult.x ?? result.x`
  - when `structuredContent === undefined` the field is removed, otherwise written
  - `isError = afterResult.isError ?? isError`
  - no deep merge
- If afterToolCall throws: the result is replaced with an error result, `isError = true`, and `durationMs` is kept.
- Output `{toolCall: prepared.toolCall, result, isError, durationMs}`.

`runToolCall` (`812-829`, public API for nested tool calls):

- The exact same pipeline as a model-initiated call (prepareArguments→validate→beforeToolCall→execute→afterToolCall), but **emits no events and produces no messages**.
- Parameters: `{tools, assistantMessage, context, signal?, onUpdate?, beforeToolCall?, afterToolCall?}` (`800-810`).
- immediate results return `{toolCall, result, isError}` (no durationMs); executed ones include durationMs.
- Never rejects for tool failure (unknown/validation failure/blocked/thrown are all `isError:true`).
- Tests: `test/agent-loop.test.ts:2133-2234`.

Message and event construction:

- `tool_execution_end` (`929-938`): `{toolCallId, toolName, result, isError, durationMs?}`; the field is omitted when durationMs is undefined.
- `toolResult` message (`940-954`): `{role:"toolResult", toolCallId, toolName, content: result.content ?? [], details: result.details, usage: result.usage, isError, durationMs?, timestamp: Date.now()}`.
- `message_start`/`message_end` (`956-959`).
- Error result helper (`922-927`): `{content:[{type:"text",text:message}], details:{}}` (**details is `{}`, not undefined**).
- Truncated-message path (`481-513`): for each toolCall emit start → error result (text includes `output token limit` and `Re-issue the tool call with complete arguments.`) → end (no durationMs) → toolResult message event; returns `terminate:false`, so the loop continues and lets the model re-issue (test `test/agent-loop.test.ts:456-526` asserts execute never ran and callIndex=2).

### 4.5 Stopping conditions (no maxTurns)

In priority order:

1. Assistant `stopReason` is `"error"` or `"aborted"`: hard exit, `finishTurn` then `turn_end`+`agent_end` directly (`agent-loop.ts:254-265`).
2. `finishTurn` returns `{action:"end"}`: after `turn_end`, stops before polling queues/`prepareNextTurn` (`agent-loop.ts:298-301`; test `test/agent-loop.test.ts:1217-1271`).
3. No tool calls this turn (or the whole batch has `terminate=true`) and no steering, no follow-up, no finishTurn continue: `agent_end` (`agent-loop.ts:305-329`).
4. Follow-up exists: inject first and continue (follow-up can keep the run alive indefinitely).
5. `finishTurn` returns `{action:"continue"}` but there is no natural request: one extra context-only request (can continue indefinitely; the README warns that unconditional continue loops forever).
6. Any of tool results, steering, or follow-up naturally satisfies the continue decision, with no extra request (test `test/agent-loop.test.ts:1307-1402`).

There is no turn limit, no token budget, and no timeout; a caller that never satisfies a stopping condition keeps issuing requests forever.

## 5. agent.ts: the `Agent` class

### 5.1 State construction (`agent.ts:83-112`)

1. `tools = initialState?.tools?.slice() ?? []`, `messages = initialState?.messages?.slice() ?? []`.
2. `initialMessage = createInitialSystemMessage(initialState?.systemPrompt, tools.map(toToolDeclaration))`.
3. Only when `messages[0]?.role !== "system"` and initialMessage exists, `unshift` initialMessage to the front of messages. Therefore: if the caller already supplies a leading system message, `initialState.systemPrompt` is no longer used for seeding, and `state.systemPrompt` comes from transcript replay.
4. Defaults: `model = initialState?.model ?? DEFAULT_MODEL` (`{id/name/api/provider:"unknown", baseUrl:"", reasoning:false, input:[], cost: all zeros, contextWindow:0, maxTokens:0}`, `agent.ts:58-69`); `thinkingLevel = initialState?.thinkingLevel ?? "off"`; `isStreaming=false`; `streamingMessage=undefined`; `pendingToolCalls=new Set()`; `errorMessage=undefined`.
5. `systemPrompt` is a getter: `getCurrentSystemPrompt(messages)` (replays content + sections + tools, `agent.ts:90-92`).
6. The `tools`/`messages` setters copy the top-level array; getters return the internal array (which can be mutated by push).

### 5.2 `PendingMessageQueue` (`agent.ts:149-180`)

| Method | `"all"` mode | `"one-at-a-time"` mode |
|---|---|---|
| `peek()` | Returns a shallow copy of all messages | Returns only the oldest one (`[]` if none) |
| `drain()` | Takes all and removes them from the queue | Takes the oldest one and removes it from the queue |
| `enqueue`/`hasItems`/`clear` | Plain append / non-empty check / clear | Same as left |

### 5.3 Run lifecycle (`agent.ts:182-186,517-566`)

`ActiveRun = {promise, resolve, abortController}`.

`runWithLifecycle(executor)`:

1. If an activeRun exists → throw `"Agent is already processing."`.
2. Create a new AbortController, hand-build a resolvable promise, and store it in `activeRun`.
3. `state.isStreaming = true`; `streamingMessage = undefined`; `errorMessage = undefined`.
4. `await executor(signal)`; on exception → `handleRunFailure(error, signal.aborted)`.
5. `finally` → `finishRun()`: isStreaming=false, streamingMessage=undefined, pendingToolCalls gets a new Set, resolve activeRun.promise, activeRun=undefined.

`handleRunFailure` (`542-558`): synthesizes a failure assistant:

```ts
{ role:"assistant", content:[{type:"text",text:""}],
  api: state.model.api, provider: state.model.provider, model: state.model.id,
  usage: EMPTY_USAGE, stopReason: aborted ? "aborted" : "error",
  errorMessage: error.message, timestamp: Date.now() }
```

It then calls `processEvents` in order: message_start, message_end, turn_end{toolResults: []}, agent_end{messages:[failureMessage]}.

### 5.4 Public API and error conditions

| Method/property | Behavior | Errors/boundaries |
|---|---|---|
| `subscribe(listener)` | Adds to the Set and returns a cancel function | No initial events |
| `state` | Returns the internal mutable state object (same reference) | — |
| `steeringMode`/`followUpMode` get/set | Controls queue drain mode | — |
| `steer(m)`/`followUp(m)` | Enqueues without immediately changing state.messages | — |
| `clearSteeringQueue`/`clearFollowUpQueue`/`clearAllQueues` | Clears | — |
| `hasQueuedMessages()` | Either queue is non-empty | — |
| `peekQueuedMessages()` | Returns steering.peek() if non-empty, otherwise followUp.peek() | Does not consume |
| `signal` | The current run's AbortSignal, or undefined | — |
| `abort()` | Aborts the signal if a run exists; does not throw when idle | `test/agent.test.ts:669-674` |
| `waitForIdle()` | Returns the current run's promise, or an already-resolved one | Resolves only after the run ends |
| `reset()` | Throws if a run exists; otherwise messages = replayed baseline (`[baseline]` if any, else `[]`); clears run-state fields and both queues | Throws `"Agent is already processing. Wait for completion before resetting."` |
| `prompt(string \| AgentMessage \| AgentMessage[], images?)` | Throws if a run exists; normalizes then starts a run | Throws `"Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion."` |
| `continue()` | Throws if a run exists; branches by tail (see 5.5) | `"Agent is already processing. Wait for completion before continuing."` / `"No messages to continue from"` / `"Cannot continue from message role: assistant"` |

`normalizePromptInput` (`422-439`): arrays are returned as-is (not copied); a non-string single item is wrapped as `[input]`; a string produces `{role:"user", content:[{type:"text",text}, ...images], timestamp: Date.now()}`.

### 5.5 `continue()` branches (`agent.ts:392-420`)

1. If `messages` is empty or all system → throw `"No messages to continue from"`, **without consuming queues** (test `test/agent.test.ts:945-959`).
2. If the last role is `assistant` (cannot resume directly):
   - `steeringQueue.drain()` non-empty → `runPromptMessages(queuedSteering, {skipInitialSteeringPoll:true})`, treating steering as this prompt's messages and skipping the loop-start steering poll (to avoid delivering the same batch twice; `agent.ts:404-408,476-515`).
   - Otherwise `followUpQueue.drain()` non-empty → `runPromptMessages(queuedFollowUps)`.
   - Otherwise throw `"Cannot continue from message role: assistant"`.
3. Otherwise (user/toolResult/custom role) → `runContinuation()`, going through `runAgentLoopContinue`: the loop start polls steering; follow-up waits for a natural stop (test `test/agent.test.ts:981-1069`).

### 5.6 Loop config assembly (`agent.ts:476-515`)

- `model = state.model`; `reasoning = thinkingLevel === "off" ? undefined : thinkingLevel` (`480`).
- Forwarded: `sessionId`, `onPayload`, `onResponse`, `onProviderStreamEvent`, `transport`, `thinkingBudgets`, `maxRetryDelayMs`, `identity`, `toolExecution`, `beforeToolCall`, `afterToolCall`, `finishTurn`, `prepareRequest`, `convertToLlm`, `transformContext`, `getApiKey`.
- `prepareNextTurn` wrapper: prefer `prepareNextTurnWithContext(context, this.signal)`, otherwise `prepareNextTurn(this.signal)`; it passes the **current activeRun's signal**, not the signal from loop parameters (`494-502`).
- `getSteeringMessages`: on the first call, if `skipInitialSteeringPoll` is true, return [] and clear the flag; afterwards drain the steering queue (`506-512`).
- `getFollowUpMessages`: drain the follow-up queue (`513`).
- The config is reassembled on every run, so Agent field changes made by the caller between two prompts take effect (the sessionId test at `test/agent.test.ts:1177-1201`).

### 5.7 `processEvents` state reduction (`agent.ts:575-622`)

State reduction happens before listeners:

| Event | State change |
|---|---|
| `message_start` | `streamingMessage = event.message` (any role, including user/toolResult) |
| `message_update` | `streamingMessage = event.message` |
| `message_end` | `streamingMessage = undefined`; `messages.push(event.message)` |
| `tool_execution_start` | Copy the Set and add the toolCallId |
| `tool_execution_end` | Copy the Set and remove the toolCallId |
| `turn_end` | If message is an assistant with an errorMessage, write it to `state.errorMessage` |
| `agent_end` | `streamingMessage = undefined` |

It then takes the activeRun signal; without a signal it throws `"Agent listener invoked outside active run"`; and it awaits `listener(event, signal)` one by one in `listeners` Set order.

## 6. proxy.ts and stream-fn.ts

### 6.1 Purpose and protocol

Purpose: the application routes LLM calls to its own server, which holds the credentials and proxies to the provider; the client passes `streamProxy` to Agent as `streamFn` (`proxy.ts:1-5,72-105`).

Request:

- `POST ${proxyUrl}/api/stream`
- Headers: `Authorization: Bearer ${authToken}`, `Content-Type: application/json`
- Body: `{ model, context, options: ProxySerializableStreamOptions }` (`proxy.ts:147-159`; serialized field whitelist `48-61,91-105`)

Downstream event protocol: SSE-style, one `data: <JSON>` per line; the JSON is a `ProxyAssistantMessageEvent` (the server strips partial to save bandwidth, `proxy.ts:20-46,179-189`). The client rebuilds the partial and maps it to pi-ai's `AssistantMessageEvent`.

Options that are dropped: `apiKey`, `signal`, `identity`, `onPayload`, `onResponse`, `onProviderStreamEvent`, `fetch`, `env`, `timeoutMs`, `maxRetries`, `toolChoice`, `deferred`, `websocketConnectTimeoutMs`. Only the 11 whitelisted fields are sent to the server; `signal` is used only for local cancellation (`proxy.ts:91-105`).

### 6.2 Client-side partial rebuild (`proxy.ts:107-252,254-393`)

Initial partial: `{role:"assistant", stopReason:"pending", content:[], api: model.api, provider: model.provider, model: model.id, usage: all zeros, timestamp: Date.now()}` (`116-132`).

Mapping table (server event → partial mutation → produced pi-ai event):

| proxy event | Partial mutation | Produced AssistantMessageEvent | Exceptions |
|---|---|---|---|
| `start` | none | `{type:"start", partial}` | — |
| `text_start` | `content[idx]={type:"text",text:""}` | `{type:"text_start",contentIndex,partial}` | — |
| `text_delta` | requires content[idx] to be text, appends delta | `{type:"text_delta",contentIndex,delta,partial}` | not text → throw `Received text_delta for non-text content` |
| `text_end` | writes `textSignature` | `{type:"text_end",contentIndex,content:text,partial}` | same as above; non-text throws |
| `thinking_start` | `content[idx]={type:"thinking",thinking:""}` | `{type:"thinking_start",contentIndex,partial}` | — |
| `thinking_delta` | requires thinking, appends | `{type:"thinking_delta",contentIndex,delta,partial}` | not thinking → throw |
| `thinking_end` | writes `thinkingSignature` | `{type:"thinking_end",contentIndex,content:thinking,partial}` | not thinking → throw |
| `toolcall_start` | `content[idx]={type:"toolCall",id,name,arguments:{},partialJson:""}` | `{type:"toolcall_start",contentIndex,partial}` | — |
| `toolcall_delta` | appends `partialJson`; `arguments = parseStreamingJson(partialJson) \|\| {}`; replaces with `{...content}` to trigger reactivity | `{type:"toolcall_delta",contentIndex,delta,partial}` | not toolCall → throw |
| `toolcall_end` | `Object.assign(content, toolCall)`; deletes `partialJson` | `{type:"toolcall_end",contentIndex,toolCall:content,partial}`; returns undefined (emits no event) if content is not a toolCall | — |
| `done` | writes `stopReason`, `usage`, optional `providerThinkingLevel` | `{type:"done",reason,message:partial}` | — |
| `error` | writes `stopReason`, `errorMessage`, `usage`, optional `providerThinkingLevel` | `{type:"error",reason,error:partial}` | — |
| unknown | none | `console.warn` then undefined (exhaustiveness check) | — |

Key points: `toolcall_start` carries only id/toolName; `arguments` accumulates through `toolcall_delta`; the complete `toolCall` at `toolcall_end` is merged wholesale (so fields that appear only at end, such as `namespace`, survive; test `test/proxy.test.ts:33-75`).

### 6.3 Stream reading, errors, and abort (`proxy.ts:134-249`)

- Create an `AssistantMessageEventStream`; an async IIFE performs the request; an abort listener on `options.signal` calls `reader.cancel("Request aborted by user")`; the listener is removed at the end (`136-144,244-248`).
- Non-2xx `fetch`: first try to parse JSON `{error}`; the message is `Proxy error: ${error}` or `Proxy error: ${status} ${statusText}`; throw into catch (`161-172,233-243`).
- Read loop: after each `reader.read()`, check `signal.aborted` and throw if set; decode, split lines on `\n`, keeping the line remainder in the buffer; call `processLine` per line. `processLine` only handles the `data: ` prefix; a JSON.parse failure throws to the outer catch (`174-206`).
- EOF: check aborted first; then flush the decoder (`decoder.decode()`) and process the last line (no trailing newline required; test `test/proxy.test.ts:78-97`); if done/error was never seen, synthesize `partial.stopReason="error"`, `partial.errorMessage="Connection closed by proxy server before the response completed"`, and push `{type:"error",reason:"error",error:partial}` (`208-232`; test `test/proxy.test.ts:99-118`).
- catch: `reason = signal.aborted ? "aborted" : "error"`; write stopReason/errorMessage into the partial; push the error event; `stream.end()` (`233-243`).
- Every path ends with `stream.end()`, guaranteeing `result()` can resolve (unlike the low-level loop's hang, the proxy catches this itself).

### 6.4 stream-fn.ts (`stream-fn.ts:1-20`)

- Module-level `let defaultStreamFn: StreamFn | undefined`.
- `setDefaultStreamFn(fn | undefined)`: sets/clears the global default.
- `getDefaultStreamFn()`: throws `"No default stream function configured. Pass streamFn explicitly or call setDefaultStreamFn()."` when unset.
- Usage sites: `Agent`'s constructor does `runtimeOptions.streamFn ?? getDefaultStreamFn()` (`agent.ts:244`); `runAgentLoop`/`runAgentLoopContinue` do `streamFn ?? getDefaultStreamFn()` before entering runLoop (`agent-loop.ts:133,158`).
- Behavior: globally mutable and process-scoped; tests reset with `setDefaultStreamFn(undefined)` after each case (`test/agent.test.ts:93-112`, `test/agent-loop.test.ts:79-111`). coding-agent calls `setDefaultStreamFn(streamSimple)` when the sdk module loads (`ca/core/sdk.ts:46`).

## 7. What coding-agent actually depends on in pi-agent

### 7.1 Dependency symbol list

Imported from `@earendil-works/pi-agent-core` (`ca/core/agent-session.ts:19-33`):

- Types: `Agent`, `AgentContext`, `AgentEvent`, `AgentMessage`, `AgentState`, `AgentTool`, `AgentToolCallOutcome`, `BeforeToolCallContext`, `BeforeToolCallResult`, `AfterToolCallContext`, `AfterToolCallResult`, `PrepareNextTurnContext`, `ThinkingLevel`.
- Values: `runToolCall`.
- Other files: `setDefaultStreamFn` (`ca/core/sdk.ts:2,46`), `Agent` (`ca/core/sdk.ts:403`), `AgentMessage`/`ThinkingLevel` (same file).

### 7.2 Fields/methods/semantics relied on (by importance)

1. `new Agent({initialState, convertToLlm, streamFn, onPayload, onResponse, onProviderStreamEvent, sessionId, transformContext, steeringMode, followUpMode, transport, thinkingBudgets, maxRetryDelayMs})` (`ca/core/sdk.ts:403-445`). `initialState.messages` is loaded directly with existing session messages; `systemPrompt: ""`, `tools: []`.
2. Hooks are installed by assigning Agent's public fields directly:
   - `agent.beforeToolCall` / `agent.afterToolCall` (`ca/core/agent-session.ts:653-656`);
   - `agent.prepareRequest` wrapper (`788-886`): before each request it replaces `context.messages` with the session projection while `context.tools` keeps the executable implementations; optional virtual-model routing; may trigger compaction;
   - `agent.finishTurn` wrapper (`887-897`): runs the extension's turn_end boundary first, then delegates to the previous finishTurn; `end` takes precedence, while `continue` can be requested by an extension;
   - `agent.prepareNextTurnWithContext` wrapper (`899-937`): compaction, recomputes the system prompt and tool loadout, returns context/messages/model/thinkingLevel;
   - `agent.transformContext` wrapper (around `ca/core/agent-session.ts:1773-1801`): hooks the extension's context event.
   These fields must be **replaceable public mutable properties**, and the hooks must receive the current Agent state.
3. The run loop composed of `agent.prompt(messages)` and `agent.continue()` (`ca/core/agent-session.ts:1884-1893`): after a prompt, post-run handling decides whether to continue, possibly several times in a row; so `prompt()` must resolve only after the run fully settles (including agent_end listeners), otherwise it races with the subsequent continue.
4. `agent.steer(...)` / `agent.followUp(...)` (`2296,2313,2361-2363`); `agent.peekQueuedMessages()` (`997`); `agent.hasQueuedMessages()` (`1020-1021,1973,2001,3262`); `agent.abort()` (`1403`).
5. Reading and writing `agent.state`: `state.model`, `state.thinkingLevel`, `state.messages`, `state.tools` (`752-753,799,805-806,924-930,944,1273`, etc.); `state.messages` is replaced wholesale (`944`), and element object identity is used by SessionManager as a map key (`_entryIdsByMessage`, `1218-1240`).
6. `agent.subscribe(handler)` (`512-514`); persistence/extensions/retries are event-driven; `_handleAgentEvent` consumes every event type.
7. `agent.toolExecution` (`739`) and `agent.streamFunction === streamSimple` (`591`) are read; the name set of `agent.state.tools` is the model-visible tool set.
8. `runToolCall(toolCall, {tools, assistantMessage, context, beforeToolCall, afterToolCall, signal, onUpdate})` is used for nested tool calls (`ca/core/agent-session.ts:736-760`): it must reuse the same before/after hook pipeline, emit no events/messages, and not reject on failure.
9. Message objects are **mutable with stable identity**:
   - `_handleAgentEvent` mutates `message.nestedCalls`/`message.usage` directly on `message_start` (toolResult) (`ca/core/agent-session.ts:1107-1122`);
   - `_replaceMessageInPlace` deletes all own keys of the target and assigns the replacement's fields back to preserve object identity (`1271-1287`); the comment explicitly relies on "Agent has already put the final message object into state before message_end";
   - `_findPersistedMessageEntryId` uses `state.messages.indexOf(message)` and object equality (`1218-1240`).
   So Kotlin message types cannot all be immutable data classes with copies in events, or coding-agent's persistence mapping and in-place rewrite semantics break.
10. `AgentEvent` semantics relied on: `message_start` (user) removes queued messages from the UI queue (`1127-1145`); `message_end` drives persistence; `turn_end` marks boundaries; `agent_end` drives retry decisions (`1202-1216`); at `agent_start`, a previously requested abort is honored immediately (`1104-1108`, relying on agent_start being awaited before the first request).
11. `convertToLlm` is supplied by the caller; pi-agent calls it once per turn at request boundaries (`ca/core/messages.ts:150-190`); Agent's `defaultConvertToLlm` does not fit coding-agent.

### 7.3 Hard Kotlin compatibility requirements derived from this

- `Agent`'s `beforeToolCall`/`afterToolCall`/`prepareRequest`/`prepareNextTurnWithContext`/`finishTurn`/`transformContext`/`convertToLlm`/`streamFunction` must be public mutable properties replaceable at runtime (or an equivalent installation API).
- Message objects must support identity comparison and controlled in-place replacement (in Kotlin, `===` reference comparison still works with data classes, but breaks if events and state carry different instances).
- `runToolCall` must be public API.
- Message objects in event payloads must be the same instances ultimately stored in `state.messages` (what `message_end` pushes is the event object, `agent.ts:587`).
- `prepareRequest`'s context replacement must persist across later requests (coding-agent overwrites it with the session projection every time), and pending messages must already have been written to events/state.

## 8. Kotlin port design

### 8.1 Module and package layout

Keep the existing Gradle multi-module layout: `:pi-ai` → `:pi-agent` → `:pi-coding-agent`; `pi-agent` depends on `pi-ai`, and all new code goes in `pi-agent/src/main/kotlin/io/github/whywood/deepdeep/sdk/agent/`. `pi-agent` should not depend on okhttp (the proxy depends only on `java.net.http` or reuses pi-ai's HTTP abstraction; the existing `pi-ai/build.gradle.kts` already includes okhttp, so either move the proxy's HTTP client into pi-ai or use the JDK HttpClient directly in pi-agent to avoid a new dependency).

Suggested package split:

| File | Contents |
|---|---|
| `Types.kt` | Enums, StreamFn typealias, AgentMessage/CustomAgentMessage, hook interfaces and results, AgentLoopConfig, turn/prepare types, AgentTool family/results, AgentContext, AgentState, AgentInitialState, AgentOptions, sealed AgentEvent, proxy event types |
| `AgentLoop.kt` | `agentLoop*`, `runAgentLoop*`, `runLoop`, `declareToolChanges`, `streamAssistantResponse`, tool execution pipeline, `runToolCall`, internal helpers |
| `Agent.kt` | `Agent`, `PendingMessageQueue`, default state/model/usage, lifecycle, event reduction, queue API |
| `Proxy.kt` | `streamProxy`, SSE parsing, partial rebuild, proxy types |
| `StreamFn.kt` | Global default StreamFn registry |

### 8.2 Type mapping rules

| TS | Kotlin | Notes |
|---|---|---|
| `interface` optional fields | `val` + nullable + default; named constructor arguments | Semantically equivalent |
| Union `A \| B` | `sealed interface` + data class subclasses | Used for `Message`, the AgentMessage core layer, `AssistantMessageEvent`, `AgentEvent` |
| `any`/`unknown` | `JsonElement` (details/structuredContent/args); generics when gradual typing is needed | Note `args` **mutability** (8.3) |
| `JsonValue`/`JsonObject` | `JsonElement`/`JsonObject` | Use `LinkedHashMap<String, JsonElement>` when a mutable version is needed |
| `AbortSignal` | Explicit `RunSignal` (`isAborted` + `abort()` + callback/await), not `Job` directly (8.4) | pi-ai's streamFn uses it too |
| `Promise<T>` | `suspend fun` / `Deferred<T>` | StreamFn/event sinks are all suspend |
| `AsyncIterable<T>` + `result()` | `Flow<T>` + `CompletableDeferred<R>` (or a custom `EventStream` class) | Needs buffering, a single consumer, and result-before-consumption |
| `Date.now()` / `performance.now()` | `currentTimeMillis()` / `System.nanoTime()` | durationMs uses a monotonic clock and rounds |
| `Set<string>` copy-on-write | `Set<String>` immutable collection, replaced wholesale on event handling | Preserves "subscribers keep the old Set unchanged" |
| `Record<string, string\|null>` (headers) | `Map<String, String?>` | — |

`AgentLoopConfig` extends `SimpleStreamOptions` in TS; Kotlin has no data class inheritance, so:

- pi-ai defines `class SimpleStreamOptions(...)` or a `data class` containing all optional fields;
- pi-agent defines `class AgentLoopConfig(val model, val convertToLlm, ..., val streamOptions: SimpleStreamOptions?)` and exposes convenience properties (`reasoning`, `sessionId`, `transport`, `thinkingBudgets`, `maxRetryDelayMs`, `onPayload`, `onResponse`, `onProviderStreamEvent`, `identity`, `temperature`, etc.) so tests and callers can construct it TS-style;
- `streamAssistantResponse` explicitly builds a `SimpleStreamOptions` (pi-ai fields only) before passing it to `StreamFn`. TS actually spreads the config with extra fields into streamFn, and extra keys are harmless in JS; Kotlin should not stuff Agent-specific fields into options (allowed deviation, see 8.10).

### 8.3 Kotlin representation of AgentMessage (decides module direction)

Problem: TS's `AgentMessage = Message | CustomAgentMessages[keyof ...]` relies on declaration merging, and Kotlin has no equivalent; also core types such as `UserMessage` live in `:pi-ai`, while the `AgentMessage` concept belongs to `:pi-agent`, and module dependencies only allow pi-agent → pi-ai.

Recommended option (A): push the "messages an agent can carry" interface down into pi-ai:

```kotlin
// pi-ai
interface AgentMessage { val role: String }
sealed interface Message : AgentMessage
class SystemMessage(...) : Message
class UserMessage(...) : Message
class AssistantMessage(...) : Message
class ToolResultMessage(...) : Message

// pi-agent
interface CustomAgentMessage : AgentMessage
// application-side example
class BashExecutionMessage(...) : CustomAgentMessage { override val role = "bashExecution" }
```

Rationale:

- `AgentContext.messages`, `AgentEvent`, and `AgentState.messages` all use `List<AgentMessage>`;
- inside `convertToLlm`, `filterIsInstance<Message>()` yields the core messages, and `sealed interface Message` preserves `when` exhaustiveness;
- custom application messages implement `CustomAgentMessage`, equivalent to TS declaration merging (the compiler cannot add custom types to a "closed union", but you can `when (m) { is Message -> ...; is CustomAgentMessage -> ... }` where needed; the loop itself only checks role, so transparency matches);
- pi-ai gains only one empty/one parent interface and does not depend on pi-agent in reverse.

Costs and caveats:

- Loses TS's compile-time exhaustiveness hint that custom message types automatically join the `AgentMessage` union; compensate with `else -> error(...)` or an explicit registry.
- Serialization: core messages are discriminated by `role`; custom messages need application-side codecs (`CustomAgentMessage` can require `val role` + an application `JsonElement` payload). pi-agent does no persistence, so this is not mandatory.
- If pi-ai has already settled on `sealed interface Message` and cannot add a parent interface, fall back to option C (pi-agent defines an `AgentMessage` wrapper layer + core message subclasses), but that introduces a conversion layer and instance-identity problems; not recommended.

Another decision that must be made: `AgentTool.execute`'s `args` and hook `args` are **mutable objects**. In TS, `beforeToolCall` can mutate `args` in place and execute uses the mutated object without re-validation (`agent-loop.ts:738-779`; test `test/agent-loop.test.ts:528-587`). kotlinx.serialization's `JsonObject` is immutable, so:

- raw and validated tool arguments use a `MutableJsonObject` (for example `LinkedHashMap<String, JsonElement>` or a custom mutable wrapper);
- `AgentToolExecute` generic parameters can provide an `Args` type + deserialization, but the hook layer must still be able to mutate;
- keep one internal mutable JSON object across prepare→hook→execute and freeze it only when producing `ToolCall.arguments`/message payloads.

Similarly, `AgentToolResult.details` is any JSON-serializable value on the coding-agent side, so Kotlin uses `JsonElement` (whether `JsonNull` means undefined or null must be unified: in TS `details: undefined` differs from omission, `test/agent-loop.test.ts:1225` uses `details: undefined`; using `JsonElement?` with explicit null distinction is recommended).

### 8.4 Concurrency and cancellation model

Three fundamental differences between Kotlin coroutines and the JS event loop must be handled explicitly:

1. **Cancellation semantics**: TS abort never throws; it only flips the AbortSignal to aborted, and each layer checks it. After `Agent.abort()`, `prompt()` still resolves normally (the final assistant message has stopReason=aborted). So a Kotlin run cannot implement abort by "cancelling the coroutine" (that throws `CancellationException` and diverges from TS). Recommended:
   - Define `class RunSignal { val isAborted; fun abort(cause); fun onAbort(handler); suspend fun awaitAbort() }`, equivalent to AbortSignal;
   - `Agent.abort()` calls `activeRun.signal.abort()` without cancelling the coroutine;
   - the run coroutine uses `coroutineScope`/`supervisorScope` for children; when the caller's coroutine is cancelled, abort the signal in `finally` and propagate `CancellationException` (an extra Kotlin semantic that TS lacks; record it as an allowed deviation);
   - streamFn/tool/hook signatures all carry `RunSignal`, mirroring TS one-to-one;
   - the loop only checks `signal.isAborted` at the same points as TS (tool preflight, before/after execute, after each sequential call); do not add a check at the top of every turn, as that would change the observable behavior "abort still finishes the current tool / still sends one request".
2. **Concurrent tool execution**: TS's `Promise.all` is single-threaded microtask concurrency; Kotlin uses `coroutineScope { toolCalls.map { async { ... } }.awaitAll() }`:
   - preflight stays serial;
   - one `async` per thunk, with `awaitAll` returning in order (source order);
   - `tool_execution_end` is emitted inside each async, so order = completion order;
   - the event sink must be serialized (see below);
   - `CoroutineStart.LAZY` is unnecessary (`async` schedules immediately, matching TS thunks that all start together).
3. **Event/state serialization**: TS's single thread guarantees `emit` and `state` mutations never interleave. Kotlin parallel tools call the sink concurrently, so:
   - Agent's `processEvents` and the loop's `emit` need a `Mutex` for serialization (or run the entire run on `Dispatchers.Default.limitedParallelism(1)`); running the whole run on a single-threaded dispatcher/`limitedParallelism(1)` is recommended, as it is closest to event-loop semantics while still allowing real parallel IO at suspension points in concurrent tools;
   - with a Mutex, a listener's await inside the lock blocks other tools' end events, which is close to TS ordering (in TS, awaiting an emit also blocks subsequent calls on that sink, but other thunks can still interleave at await points — ordering is not strictly deterministic; tests use deterministic gating and do not depend on interleaving);
   - `steer()`/`followUp()` may be called from other coroutines: the queues must be thread-safe (`synchronized`/`ConcurrentLinkedQueue` or a `Mutex` wrapper; for non-suspend methods use the shortest possible critical section);
   - `state.pendingToolCalls` is replaced wholesale during event handling, and subscribers should not read it under a lock; a volatile/atomic reference or a single-thread-confined "visible on the sink thread only" guarantee is recommended.
4. **Event streams (low-level API)**: `agentLoop` uses `Channel<AgentEvent>(UNLIMITED)` + `CompletableDeferred<List<AgentMessage>>`: start the run coroutine immediately (either pass in a `CoroutineScope`, or return a cold wrapper with `start(scope)`; an explicit scope parameter is recommended), `Flow = channel.receiveAsFlow()`, `result = deferred.await()`; on run failure, `channel.close(cause)`/complete the deferred exceptionally. `send` on `Channel.UNLIMITED` does not suspend, equivalent to `stream.push`; `agent_end` completes the deferred before closing.
5. **Subscription API**: to match TS, `Agent.subscribe` takes `suspend (event: AgentEvent, signal: RunSignal) -> Unit` and stores listeners in a `LinkedHashSet`, awaiting in insertion order; do not substitute `SharedFlow.emit` (that loses "await one by one, prompt settlement includes listeners"). You may additionally expose `val events: Flow<AgentEvent>` (sending events to a Channel internally) for UI use, but settlement still follows listeners.

### 8.5 State concurrency recommendations

- `AgentState` stays "mutable object + stable identity": `var model`, `var thinkingLevel`, custom getter/setter `tools`/`messages` (setters copy the top-level list), `val isStreaming`, etc. Do not replace `Agent.state` with `StateFlow<Snapshot>`; coding-agent depends on `agent.state.messages = ...`, `agent.state.messages.indexOf(msg)`, and object identity.
- `systemPrompt` is a computed property: replay all system messages in the current messages (`getCurrentSystemPrompt`); caching is optional in Kotlin, but every read must see the latest value.
- Clear `errorMessage`/`streamingMessage`/`pendingToolCalls` exactly as in 5.3/5.7; `pendingToolCalls` gets a new immutable Set on every event.
- Document in KDoc that `Agent` calls (prompt/continue/reset/subscribe/queue APIs) are not thread-safe and, except for `steer`/`followUp`/`abort`, should come from the same coroutine; `steer`/`followUp`/`abort` may be called from any thread.

### 8.6 Kotlin shape of StreamFn / transport / proxy

- `typealias StreamFn = suspend (Model<Api>, TranscriptContext, SimpleStreamOptions?) -> AssistantMessageEventStream`.
- On the pi-ai side, `AssistantMessageEventStream` should be a "write end + read end":
  - write end: `push(event)`, `end(result?)`;
  - read end: `Flow<AssistantMessageEvent>`, `suspend fun result(): AssistantMessage`;
  - internally `Channel(UNLIMITED)` + `CompletableDeferred<AssistantMessage>`; terminal events (done/error) resolve the result first; pushes after done are ignored; `durationMs` is filled in on done/error/end.
  This is equivalent to `ai/utils/event-stream.ts:26-135` and can be reused by pi-ai's provider implementations.
- `transport`: `enum class Transport(val wire: String) { SSE("sse"), WEBSOCKET("websocket"), WEBSOCKET_CACHED("websocket-cached"), AUTO("auto") }`; `Agent.transport` defaults to `AUTO`; serialization/pass-through uses `wire`.
- `proxy`: use the JDK `HttpClient` (`java.net.http`) to POST and read SSE line by line; no new dependency. Key points map one-to-one to 6.2/6.3:
  - maintain a mutable partial (a Kotlin `class`, not a data class, with mutable `stopReason`, `errorMessage`, `usage`, `content` list);
  - parse `data: ` per line with `Json.decodeFromString<ProxyAssistantMessageEvent>` (use `JsonElement` + manual type discrimination to avoid polymorphic serialization configuration);
  - on abort, cancel response body reading (the `HttpResponse.BodyHandlers.ofLines()` stream can be closed);
  - at EOF without a terminal state, synthesize an error (same error text).
- `setDefaultStreamFn`: Kotlin uses `@Volatile var defaultStreamFn: StreamFn?` + `synchronized` assignment; throw the same message when unset. Be careful to isolate the global mutable state in parallel tests (clear it in teardown).

### 8.7 Queue / interrupt / steer semantics mapping

| TS behavior | Kotlin implementation |
|---|---|
| `steer` = enqueue steering, not immediately visible | `ArrayDeque<AgentMessage>` + lock; `steer` is not suspend |
| `followUp` = enqueue, consumed only when the agent would stop | Same, in a separate queue |
| `steeringMode`/`followUpMode` can change at any time | The queue holds `var mode` (or Agent holds it and passes it to drain) |
| `peek` returns a shallow copy of all / the first of one | `toList()` / `listOf(first)` |
| `drain` consumes | `removeFirst(n)` or clear |
| The loop's pull points are fixed (3 steering, 1 follow-up) | Mapped to `AgentLoopConfig`'s `getSteeringMessages`/`getFollowUpMessages` callbacks, with Agent draining internally |
| `continue()` assistant tail: steering first, then follow-up, else throw | Implement per 5.5; pass `skipInitialSteeringPoll` to the loop config via a local boolean closure (as TS does, `agent.ts:477,507-510`) |
| `abort` keeps queues; `reset` clears them | One-to-one |
| `waitForIdle` | `CompletableDeferred<Unit>`, completed in `finishRun`; returns an already-completed Deferred when idle |

There is no separate "interrupt" method in this package: interrupt = `abort()`; steer is "speak during a run", and follow-up is "the next input after this run finishes". coding-agent's `/steer` and `/followUp` ultimately map to these two queues (`ca/core/agent-session.ts:2296-2313`).

### 8.8 Error/exception mapping

| TS scenario | TS behavior | Kotlin recommendation |
|---|---|---|
| streamFn throws | Agent synthesizes a failure message (`AbortController` state decides error/aborted); prompt resolves normally | `catch (e: Throwable)` (excluding `CancellationException`) → synthesize a failure message; if the outer coroutine was cancelled, abort the signal and rethrow `CancellationException` |
| stream event error/aborted | Final assistant message; hard exit | Same |
| Tool execute throws | Error tool result, run continues | `catch (e: Throwable)` → `AgentToolResult(isError=true, content=[text(e.message)])` |
| beforeToolCall throws | Error tool result (tool not executed), run continues | Same (do not swallow `CancellationException`; if the caller cancelled, propagate) |
| afterToolCall throws | Error tool result, durationMs retained | Same |
| validateToolArguments fails | Error tool result with the validation error text | The pi-ai validator throws an exception with the same message format; the loop turns it into an error result |
| Subscriber throws | Treated as run failure, synthesizes failure events; if it throws again, prompt rejects | Follow TS if exact parity is required; otherwise log and continue (document the deviation) |
| agentLoop internal throw (low level) | The returned stream never ends (bug-like behavior) | Complete the stream with an exception instead (allowed deviation) |
| Concurrent prompt/continue/reset | Exact error text | Same text, throwing `IllegalStateException` |
| Listener invoked with no run | `"Agent listener invoked outside active run"` | Same (or assertion failure) |

### 8.9 Behavior checklist that must be preserved verbatim

Verify each item when implementing; reference the numbers directly in Kotlin tests.

- B01 `agent_start` is the first event and `agent_end` the last; the first `turn_start` immediately follows `agent_start`; later `turn_start` events come after `prepareNextTurn` and before pending message events (`agent-loop.ts:126-131,216`).
- B02 A prompt run's initial messages (the prompt + any synthesized tool-declaration system message) emit `message_start`+`message_end` one by one after `agent_start`/`turn_start`, and enter the returned messages (`agent-loop.ts:119-131`).
- B03 Continue runs do not re-emit events for existing messages; `newMessages`/`agent_end.messages` contain only this run's additions (`agent-loop.ts:152-158`; test `test/agent-loop.test.ts:2031-2070`).
- B04 Every final assistant message records `thinkingLevel = reasoning ?? "off"` (`agent-loop.ts:418-419`).
- B05 `message_update` is emitted only after the stream's `start` has been seen; if the stream goes straight to done/error, synthesize `message_start` (`agent-loop.ts:442-451,456-463,470-476`).
- B06 The message in streaming `message_start`/`message_update` is a top-level shallow copy; `message_end` passes the final object itself (`agent-loop.ts:430,448,464,477`).
- B07 `tool_execution_start.args` are the assistant toolCall's raw `arguments`, before `prepareArguments`/validation (`agent-loop.ts:552-557`; in `test/agent-loop.test.ts:589-666` prepareArguments only affects validation/execution).
- B08 Parallel batch: `tool_execution_end` in completion order, toolResult messages and `turn_end.toolResults` in source order (`agent-loop.ts:656-669`; test `test/agent-loop.test.ts:668-760`).
- B09 Serial decision: global `"sequential"` or any tool in the batch with `executionMode: "sequential"` → whole batch serial; otherwise parallel (`agent-loop.ts:518-533`; test `test/agent-loop.test.ts:867-1106`).
- B10 Parallel-batch preflight (start, lookup, prepareArguments, validation, beforeToolCall) runs serially; only the execution phase is concurrent (`agent-loop.ts:606-658`).
- B11 Abort checkpoints and effects: after beforeToolCall (abort before block), after the hook, after each preflight call, at the start of parallel thunks, and after each serial call (`agent-loop.ts:748-773,585-587,623-653`).
- B12 Tool not found / argument validation failure / `prepareArguments` throw / beforeToolCall throw → error tool result (`isError=true`), the run does not fail (`agent-loop.ts:726-733,780-786`; the hookCalls assertion in `test/agent-loop.test.ts:2167-2212`).
- B13 After `beforeToolCall` mutates `args` in place, execute uses the mutated value without re-validation (`agent-loop.ts:738-779`; test `test/agent-loop.test.ts:528-587`).
- B14 When `prepareArguments` returns the same reference, `toolCall` is not replaced; otherwise arguments are replaced (`agent-loop.ts:704-716`).
- B15 The error text for `block:true` is `reason || "Tool execution was blocked"`; `terminate:true` is written onto the result (`agent-loop.ts:755-765`; test `test/agent-loop.test.ts:1783-1839`).
- B16 `afterToolCall` does field-level overrides only, with no deep merge; content without structuredContent drops structuredContent; a hook throw → error result retaining durationMs (`agent-loop.ts:880-919`; test `test/agent-loop.test.ts:2214-2233`).
- B17 `finishTurn` is called after the assistant + all tool results and before `turn_end`, for normal/error/aborted alike; error/aborted decisions are ignored; `{action:"end"}` emits agent_end immediately after turn_end, skipping queue polling and prepareNextTurn; `{action:"continue"}` with no tool results/steering/follow-up makes one extra context-only request, and that turn calls `prepareNextTurn` again (`agent-loop.ts:194-217,254-265,289-307,319-323`; tests `test/agent-loop.test.ts:1108-1402`, `test/agent.test.ts:1071-1134`).
- B18 `prepareRequest` is called before every request (including the first), after pending messages have been appended and emitted; it does not poll queues; the returned context/model/thinkingLevel persist for later requests in this run (`agent-loop.ts:228-248`; test `test/agent-loop.test.ts:1404-1501`).
- B19 `prepareNextTurn` is called only after a turn has completed and before the next turn starts; returned messages go through the normal message lifecycle and enter newMessages (`agent-loop.ts:194-225`; test `test/agent-loop.test.ts:1503-1578`).
- B20 Steering poll points: run start, after each turn, and after prepareNextTurn when pending is empty; follow-up is polled only when the run "would stop" (`agent-loop.ts:185,213-215,304-317`; tests `test/agent-loop.test.ts:1458-1501`, `test/agent.test.ts:981-1069`).
- B21 Batch termination: `finalizedCalls.length > 0 && every(result.terminate === true)`; both blocked and afterToolCall can set terminate (`agent-loop.ts:700-702`; test `test/agent-loop.test.ts:1732-2009`).
- B22 With `stopReason === "length"`, all toolCalls from that assistant fail without executing, the error text contains "output token limit", and the loop continues (`agent-loop.ts:276-279,481-513`; test `test/agent-loop.test.ts:456-526`).
- B23 Tool updates: `onUpdate` before execute settles is accepted and awaited; calls after settle are silently ignored; an update promise exception can turn a successful result into an error result (`agent-loop.ts:831-867`; test `test/agent.test.ts:473-612`).
- B24 `durationMs` measures only `execute()` (monotonic clock, rounded, excluding hooks); when it did not actually execute, the result/message/event have no durationMs (`agent-loop.ts:838-866,936,951`; test `test/agent-loop.test.ts:402-454`).
- B25 toolResult message fields: `content: result.content ?? []`, `details`, `usage`, `isError`, optional `durationMs`, `timestamp: Date.now()`; error results use `details={}` (`agent-loop.ts:922-927,940-954`).
- B26 `tool_execution_end` precedes the toolResult's `message_start`; the toolResult start/end come in pairs (`agent-loop.ts:579-583,929-959`).
- B27 `tool_execution_end.result` is the final `AgentToolResult` (including hook-overridden content/details/structuredContent/usage/terminate) (`agent-loop.ts:914-937`).
- B28 Queues default to `"one-at-a-time"`; `peek` does not consume; `peekQueuedMessages` prefers steering, then follow-up when empty (`agent.ts:255-256,339-342`; test `test/agent.test.ts:1136-1153`).
- B29 `abort()` does not throw when idle, does not clear queues, and does not change state; after abort, `prompt()` still resolves normally with assistant stopReason=aborted (`agent.ts:350-352`; `test/agent.test.ts:669-674`; `test/e2e.test.ts:208-219`).
- B30 In-flight guards and exact error text for `prompt`/`continue`/`reset` (5.4 table; `agent.ts:365-366,383-386,394-396,518-520`).
- B31 `continue()`'s three tail cases and queue consumption: empty/system-only throws `"No messages to continue from"` without consuming; an assistant tail tries steering then follow-up, and throws `"Cannot continue from message role: assistant"` if both are empty; other tails continue, with steering polled at start and follow-up deferred (`agent.ts:398-419`; test `test/agent.test.ts:824-1069`).
- B32 `reset()` replays the baseline with `getCurrentSystemMessage` (including the current prompt/sections/tool declarations) and clears both queues and run-state fields (`agent.ts:363-377`; test `test/agent.test.ts:282-307`).
- B33 State reduction precedes listeners; `message_start` sets `streamingMessage` (any role); `message_end` pushes and clears it; `tool_execution_*` copies and replaces the pendingToolCalls set (`agent.ts:576-613`).
- B34 `isStreaming` becomes false only after all `agent_end` listeners settle; `waitForIdle` waits the same way (`agent.ts:517-566`; test `test/agent.test.ts:362-433`).
- B35 Listeners are awaited serially in insertion order; a listener exception triggers the run-failure path (3.4; `agent.ts:619-621,517-540`).
- B36 When streamFn breaks the contract and throws, the synthesized failure message has empty text, the current model's api/provider/id, `EMPTY_USAGE`, stopReason=aborted|error, errorMessage, and a timestamp; the event sequence is 3.2(f) (`agent.ts:542-558`).
- B37 `agent_end.messages` has three semantics (prompt includes initial messages, continue includes only additions, failure path includes only the failure message) (`agent-loop.ts:134,159,263,299,329`; `agent.ts:557`).
- B38 The default `convertToLlm` keeps only messages whose role is system/user/assistant/toolResult (`agent.ts:39-47`).
- B39 `Agent` construction: top-level copy of tools/messages; `createInitialSystemMessage` generates a leading system message when there is a prompt or tools, and only unshifts it when messages[0] is not a system message (`agent.ts:83-112`; test `test/agent.test.ts:114-162`).
- B40 Tool loadout diff: the executable set wins; tool fields in pending system messages are treated as intent and recomputed; if a pending system message exists it is merged, otherwise a declaration message `{content:"", timestamp: now}` is inserted before the first non-system message (4.3; test `test/agent.test.ts:164-280`).
- B41 Global default StreamFn: the error text when unconfigured is exact; both prompt and the low-level loop support omitting streamFn (`agent-loop.ts:133,158`; `agent.ts:244`; `stream-fn.ts:15-19`).
- B42 Request identity: defaults to `{attribution: PI_ATTRIBUTION}`; a configured identity is field-level overridden; `{attribution:null}` disables it; the options.identity passed to streamFn is the merged result (`agent-loop.ts:41,415`; test `test/agent.test.ts:1203-1228`).
- B43 `sessionId`, `transport`, `thinkingBudgets`, `maxRetryDelayMs`, `onPayload`, `onResponse`, `onProviderStreamEvent` go into stream options as-is (`agent.ts:481-488`; test `test/agent.test.ts:1155-1201`).
- B44 `getApiKey` is called on every request; its return value or `config.apiKey` is used; the provider is `config.model.provider` (`agent-loop.ts:409-410`).
- B45 proxy protocol and fallbacks: request path/headers/field whitelist, SSE `data: ` parsing, toolcall incremental rebuild, complete metadata merge at `toolcall_end`, terminal state without a trailing newline, synthesized error at EOF without a terminal state, reason=aborted on abort (section 6; `test/proxy.test.ts:32-118`).
- B46 Custom messages are transparent to the loop: they are structurally recognized only as `role === "system"` (tool declarations) and `role === "assistant"` (continue validation); everything else goes through `convertToLlm` (`agent-loop.ts:90,148,344-349`; `types.ts:351-374`).
- B47 Synthesized tool-declaration system messages have empty content and timestamp=now; when a system message already exists in `pending`, its content/sections are preserved (`agent-loop.ts:362-371`).
- B48 For error/aborted, `turn_end.toolResults` is an empty array and `turn_end.message` is the assistant message (`agent-loop.ts:262`).
- B49 In parallel preflight, an immediate failure's `tool_execution_end` is emitted during the preflight phase, earlier than other tools' ends (`agent-loop.ts:615-627`).
- B50 The event sink is awaited backpressure: slow listeners slow the loop (`agent-loop.ts:35,126`; `agent.ts:575-622`).

### 8.10 Allowed deviations (must be documented in the implementation notes)

1. Low-level `agentLoop`/`agentLoopContinue`: TS hangs the stream on internal exceptions; completing the `Channel`/`Deferred` exceptionally in Kotlin is safer and does not conflict with existing tests (existing low-level tests do not cover that path).
2. The options `AgentLoopConfig` passes to StreamFn contain only pi-ai `SimpleStreamOptions` fields; TS also spreads agent-specific fields. coding-agent's streamFn reads only pi-ai fields, so there is no impact.
3. `message_update.message` shallow-copy/sharing policy: if Kotlin uses immutable message types, tests must pin whether subscribers receive a snapshot or a shared object; matching TS (top-level copy with a shared content list) is recommended.
4. The visibility of listeners added during JS `Set` iteration, and behavior after a listener throws, may be handled in Kotlin by snapshotting/logging and continuing; bit-exact replication is not required, and no tests pin these behaviors.
5. Clocks: `Date.now()` / `performance.now()` map to wall clock / `nanoTime` respectively; `durationMs` only guarantees relative ranges, not numeric equality with TS (tests also assert ranges only).
6. `typebox` runtime validation: Kotlin substitutes pi-ai's JSON Schema validator; keeping the error text format (`Validation failed for tool ...`) is recommended, but not all implicit coercions map 1:1 (they must be defined explicitly on the pi-ai side). Note that `docs/analysis/pi-ai-types.md` proposes a `{ok:false, errors}` structure for the validator; pi-agent's catch/error-result semantics need a thrown exception or error text, so integration must agree on a throwing wrapper (or let the agent consume structured errors directly).
7. `args` mutability: Kotlin must use a mutable JSON object to carry hook in-place mutations; if pi-ai's validator returns immutable objects, explicitly copy them into a mutable structure before handing them to hook/execute.
8. `partialJson` is a proxy-internal temporary field; Kotlin must not pollute the public `ToolCall` type with it.
9. The test utility's `new Function` evaluation (`test/utils/calculate.ts`) becomes a safe expression evaluator in Kotlin; this is a test-infrastructure difference, not runtime behavior.

### 8.11 File-level breakdown and code-size estimate

Main code (`pi-agent/src/main/kotlin/io/github/whywood/deepdeep/sdk/agent/`):

| File | Contents | Estimated lines |
|---|---|---|
| `Types.kt` | Enums/typealias/AgentMessage and Custom/StreamFn/hooks/loop config/turn and prepare types/AgentTool family/AgentContext/AgentState/initial state/AgentOptions/sealed AgentEvent/proxy event types | 380-460 |
| `AgentLoop.kt` | Entry and event stream wrappers, runLoop, declareToolChanges, streamAssistantResponse, serial/parallel tool execution, prepare/execute/finalize, truncation handling, runToolCall, error results and message construction | 780-950 |
| `Agent.kt` | Default usage/model, Agent mutable state factory, PendingMessageQueue, the Agent class (subscriptions/lifecycle/entry points/config assembly/event reduction/queue API) | 520-650 |
| `Proxy.kt` | ProxyStreamOptions/event types, HTTP+SSE, partial rebuild, error fallback | 280-360 |
| `StreamFn.kt` | Global default StreamFn | 25-35 |
| **Main code total** | | **about 2000-2450** |

Test code (`pi-agent/src/test/kotlin/io/github/whywood/deepdeep/sdk/agent/`):

| File | Contents | Estimated lines |
|---|---|---|
| `AgentLoopTest.kt` | Port all `agent-loop.test.ts` cases + a local scripted StreamFn utility | 900-1100 |
| `AgentTest.kt` | Port `agent.test.ts` | 700-850 |
| `AgentE2eTest.kt` | Port `e2e.test.ts`, depending on pi-ai's faux provider test double | 350-450 |
| `ProxyTest.kt` | Port `proxy.test.ts`, using MockWebServer or a custom `HttpServer` to simulate SSE | 200-300 |
| `TestSupport.kt` | Scripted stream, gating, event-sequence assertion helpers | 250-350 |
| **Test total** | | **about 2400-3050** |

pi-ai prerequisite work (not delivered by this document but blocking pi-agent): message/Model/Usage types, EventStream/AssistantMessageEventStream, transcript replay helpers, tool validator, identity merge, partial JSON parsing, system prompt rendering, and the faux provider test double (about 1200-1800 lines).

### 8.12 Implementation order

1. Land the pi-ai prerequisites (1.3 items 1-11) first, especially `EventStream`/`TranscriptContext`/`validateToolArguments`/transcript replay.
2. `Types.kt` + event/tool types (can compile without implementations first).
3. `AgentLoop.kt`: `streamAssistantResponse` and `declareToolChanges` first, then the `runLoop` body, then the two tool execution strategies and hooks; pair with the first 30 `AgentLoopTest` cases (a scripted stream suffices, no faux provider needed).
4. `Agent.kt`: state factory → PendingMessageQueue → lifecycle → prompt/continue → processEvents; pair with `AgentTest`.
5. `StreamFn.kt` + `Proxy.kt`; pair with proxy tests.
6. End-to-end: pi-ai's faux provider double + `AgentE2eTest`; finally sign off item by item against the section 9 acceptance checklist.

## 9. Test inventory and acceptance criteria

### 9.1 Overview

| File | Lines | Declared cases | Executions (including it.each expansion) | Coverage |
|---|---|---|---|---|
| `test/agent-loop.test.ts` | 2234 | 36 | 38 | Low-level loop, event order, tool execution, finishTurn/prepareRequest/prepareNextTurn, termination rules, runToolCall |
| `test/agent.test.ts` | 1229 | 36 | 41 | Agent state/subscriptions/lifecycle/queues/continue branches/hook forwarding/identity |
| `test/e2e.test.ts` | 416 | 10 | 10 | Agent end-to-end with the faux provider (text, tools, abort, multi-turn, thinking, continue) |
| `test/proxy.test.ts` | 118 | 3 | 3 | streamProxy incremental rebuild and EOF fallback |
| `test/utils/*` | 89 | — | — | calculate/get_current_time tools, waitForTick |

92 test executions in total. The tables below list every case per file and the behavior it pins; Kotlin test classes should map one-to-one.

### 9.2 `test/agent-loop.test.ts`

| Line | Case | Purpose/key assertion |
|---|---|---|
| 80 | uses the configured default when a legacy caller omits streamFn | Uses the function registered via `setDefaultStreamFn` when streamFn is omitted; called once |
| 114 | should emit events with AgentMessage types | After prompt, messages are [user, assistant]; events include start/turn_start/message_start/message_end/turn_end/agent_end |
| 160 | should build provider context exclusively from transcript messages | The context streamFn receives has only the `messages` key; the first system message object is the same reference as the prompt's |
| 199 | should handle custom message types via convertToLlm | The notification custom message in context is filtered out by convertToLlm, leaving only user in converted |
| 253 | should apply transformContext before convertToLlm | transformContext trims to 2 messages first, and convertToLlm receives the trimmed 2 |
| 305 | should handle tool calls and results | Tool executes; toolStart/toolEnd events; afterToolCall can change usage and write it into toolResult |
| 402 | records how long execute() took on the tool result, excluding hooks | With a 30ms execute and a 100ms beforeToolCall, durationMs is in [25,100); a blocked result is an error with no durationMs |
| 456 | should not execute tool calls from a length-truncated assistant message | With stopReason=length, tools do not execute, the end event's error text contains "output token limit", and the loop continues (callIndex=2) |
| 528 | should execute mutated beforeToolCall args without revalidation | After the hook changes args.value to 123, execute receives 123 |
| 589 | should prepare tool arguments for validation | prepareArguments converts oldText/newText into an edits array, which is validated and executed |
| 668 | should emit tool_execution_end in completion order but persist tool results in source order | End order ["tool-2","tool-1"]; toolResult messages/turn_end.toolResults order ["tool-1","tool-2"]; concurrency observed |
| 762 | should inject queued messages after all tool calls complete | Steering is injected only after both tools finish; in the event order the two tool results precede the interrupt; the second request contains the interrupt |
| 867 | should force sequential execution when a tool has executionMode=sequential even with default parallel config | Parallelism is not observed; toolResult order is fixed |
| 949 | should force sequential execution when one of multiple tools has executionMode=sequential | executionOrder[0]=="slow:a"; fast runs only after slow settles |
| 1035 | should allow parallel execution when all tools have executionMode=parallel | second starts before first settles |
| 1108 | runs finishTurn after tool-result messages and before turn_end | Event tail order [message_end:toolResult, finishTurn, turn_end]; the last context message inside finishTurn is the toolResult |
| 1162 | runs finishTurn for a %s assistant before turn_end without changing the hard exit (one each for error/aborted) | Order ["finishTurn","turn_end"]; providerCalls=1; steeringPolls=1; followUpPolls=0; the continue decision is ignored |
| 1217 | action:end skips queue polling and next-turn preparation | providerCalls=1; steeringPolls=1; followUpPolls=0; prepareNextTurnCalls=0 |
| 1273 | makes exactly one context-only request when no natural request satisfies continuation | finishTurn returns continue the first time and undefined the second; providerCalls=2, finishCalls=2 |
| 1307 | lets a natural tool-result request satisfy continuation | The continue decision is satisfied naturally by the tool result; providerCalls=2, finishCalls=2 |
| 1347 | lets a natural steering/follow-up request satisfy continuation (one each) | The second request contains the corresponding queued messages; providerCalls=2, finishCalls=2 |
| 1404 | prepares the initial request after pending messages and can replace request state | prepareRequest is called after the steering event; the returned context/model/thinkingLevel take effect (model replaced, reasoning="high") |
| 1458 | does not poll steering after prepareRequest | Requests contain late steering as [false,true]; prepareRequest called twice; steeringPolls=3 |
| 1503 | should use prepareNextTurn snapshot before continuing | prepareNextTurn called once; the second request contains its returned "updated guidance" system message |
| 1580 | picks up steering queued during prepareNextTurn before the next request | The second request contains late steering |
| 1633 | action:end receives finalized turn context and stops before queue polling | Exact event sequence in 3.2(c); finishTurn receives toolResults and context roles ["system","user","assistant","toolResult"]; returned messages have the same roles |
| 1732 | should stop after a tool batch when every tool result sets terminate=true | llmCalls=1; messages [system,user,assistant,toolResult]; turn_end occurs once |
| 1783 | should stop after a blocked tool call when beforeToolCall sets terminate=true | Tool not executed; error result text "Blocked by policy"; llmCalls=1 |
| 1841 | should continue after a mixed batch with one terminating blocked call | Only second executes; llmCalls=2 (not all terminate, so it does not stop) |
| 1899 | should continue after parallel tool calls when not all tool results terminate | A second request occurs; messages [system,user,assistant,toolResult,toolResult,assistant] |
| 1964 | should allow afterToolCall to mark a tool batch as terminating | After afterToolCall returns terminate=true, llmCalls=1 |
| 2013 | should throw when context has no messages | `agentLoopContinue` throws synchronously with "Cannot continue: no messages in context" |
| 2031 | should continue from existing context without emitting user message events | Returns exactly 1 assistant message; message_end only for the assistant |
| 2072 | should allow custom message types as last message (caller responsibility) | The custom role is converted to user by convertToLlm without throwing; returns 1 assistant message |
| 2167 | runToolCall validates, runs the hooks, and reports failures as error outcomes | Five categories: success/validation failure/blocked/unknown/isError inside the tool; hookCalls order ["before a","after a","before c","before e","after e"]; update fires once |
| 2214 | runToolCall afterToolCall replace structured content / drop when content replaced | structuredContent result sequence [undefined, {value:"replaced"}, {value:"both"}, {value:"original"}] |

### 9.3 `test/agent.test.ts`

| Line | Case | Purpose/key assertion |
|---|---|---|
| 93 | uses the configured default when a legacy caller omits streamFn | Constructing `Agent({})` uses the global default; calls=1 after prompt |
| 114 | should create an agent instance with default state | model is defined, thinkingLevel="off", tools=[], messages=[], isStreaming=false, streamingMessage=undefined, pendingToolCalls=empty set, errorMessage=undefined |
| 128 | should create an agent instance with custom initial state | messages = a leading system message (content=systemPrompt, timestamp=0); model/thinkingLevel take effect |
| 144 | converts initial prompt and tools into transcript state | The leading system message contains content and the toolsAdded names |
| 164 | declares tool loadout changes to the model before the next request | The request system tool declaration sequences across three prompts are ["+first","-"], ["+first","-","+second","-first"], and a repeat; synthesized message fields and timestamp; declarations have no execute |
| 212 | merges tool changes into a pending system message | The pending system message (sections) gets toolsAdded merged in; the request has 2 system messages total |
| 247 | rewrites pending tool declarations to match the executable set | Pending declarations second/-first are recomputed to empty; the final replayed tools are ["first"] |
| 282 | restores the transcript baseline when reset | After reset, messages contains only one replayed system message (content + toolsAdded) |
| 309 | should subscribe to events | subscribe emits no initial events; state mutators emit no events; unsubscribe works |
| 331 | emits full lifecycle events for thrown run failures | Event sequence in 3.2(f); the final assistant has stopReason=error and errorMessage="provider exploded"; state.errorMessage is in sync |
| 362 | should await async subscribers before prompt resolves | While the agent_end listener is suspended, prompt does not resolve and isStreaming=true; after release it resolves and isStreaming=false |
| 400 | waitForIdle should wait for async subscribers | Same as above; waitForIdle waits too |
| 435 | should pass the active abort signal to subscribers | The signal the agent_start listener receives is not aborted; after agent.abort() it is aborted |
| 473 | should ignore tool updates after the tool execution settles | Calling onUpdate after the tool settles produces no events and no unhandledRejection |
| 538 | should ignore a settled parallel tool update while another tool is still running | Updates from the settled tool are ignored; tool_execution_update count is 0 throughout |
| 614 | should update state with mutators | model/thinkingLevel can change; tools/messages assignment copies the top-level array; messages.push works; messages=[] clears |
| 649 | should support steering message queue | After steer, the message is not in state.messages |
| 659 | should support follow-up message queue | After followUp, the message is not in state.messages |
| 669 | should handle abort controller | Aborting while idle does not throw |
| 676 | should reject reset while processing without corrupting the transcript | reset throws the exact text; state is unchanged; after the run completes messages=[user,assistant] |
| 710 | should throw when prompt() called while streaming | The second prompt throws the exact text; the original prompt ends after abort |
| 750 | should throw when continue() called while streaming | Throws the exact text |
| 786 | continue() should process queued follow-up messages after an assistant turn | assistant tail + followUp queue → continue injects it and produces a new assistant message |
| 824 | continue() keeps $mode steering semantics for assistant-tail fallback (2 modes) | one-at-a-time needs 2 requests, all needs only 1; the first batch contains Steering 1, etc. |
| 860 | keeps legacy prepareNextTurn signal callback behavior | The old signature receives an AbortSignal instance; requestCount=2 |
| 901 | forwards finishTurn through AgentOptions with the active abort signal | finishTurn receives a signal; context roles ["system","user","assistant","toolResult"]; requestCount=1 (end) |
| 945 | rejects a queued continuation from $name context without draining queues (1 each for empty/system-only) | Throws "No messages to continue from"; peek still returns steering then followUp |
| 961 | defers follow-up input on the first continuation request from $name tail (1 each for user/toolResult) | The first request contains no follow-up; the second contains it |
| 1007 | polls $mode steering at continuation startup (2 modes) | The first batch contains first; with one-at-a-time the second batch contains second; with all, the first batch already contains second |
| 1043 | keeps steering ahead of follow-up from a non-assistant continuation tail | The first request contains steering but not follow-up; the second contains follow-up |
| 1071 | keeps queues on a %s response even when finishTurn requests continuation (1 each for error/aborted) | Queues are not consumed; peek order is steering→followUp |
| 1109 | keeps queues when finishTurn ends the run | Same as above; end does not consume queues |
| 1136 | previews the next selected queued messages without consuming them | peek is idempotent; after steering is cleared, peek returns followUp |
| 1155 | forwards provider stream event observers through AgentOptions | onProviderStreamEvent receives the data [{request_cost:0.01}] |
| 1177 | forwards sessionId to streamFunction options | The default value is passed; changes through the setter take effect |
| 1203 | attributes requests to pi by default and merges a configured identity over it | The identity of the three requests is `{attribution:PI_ATTRIBUTION}`, `{attribution:acme,userAgent:"short"}`, `{attribution:PI_ATTRIBUTION,userAgent:"short"}` respectively |

### 9.4 `test/e2e.test.ts`

| Line | Case | Purpose/key assertion |
|---|---|---|
| 187 | handles a basic text prompt | faux returns "4"; messages [system,user,assistant]; isStreaming=false |
| 193 | executes tools and tracks pending tool calls | The tool message content contains 123*456=56088; pendingToolCalls in events is {start:["calc-1"]}, {end:[]}, and finally empty |
| 208 | handles abort during streaming | tokensPerSecond=20, abort after 30ms; the final assistant has stopReason=aborted and an errorMessage; state.errorMessage is in sync |
| 221 | emits lifecycle updates while streaming | Events include update, with order start<message_start<message_end<agent_end |
| 227 | maintains context across multiple turns | After two prompts there are 5 messages; the second answer contains Alice |
| 243 | preserves thinking content blocks | The final assistant content exactly equals [{thinking:"step by step"},{text:"4"}] |
| 270 | continue throws when no messages in context | Throws "No messages to continue from" |
| 283 | continue throws when last message is assistant | Throws "Cannot continue from message role: assistant" |
| 318 | continue from user message | Only 1 request is made; messages [user,assistant]; the answer contains HELLO WORLD |
| 352 | continue from tool result | Messages ≥4; the last is an assistant message containing 8 |

### 9.5 `test/proxy.test.ts`

| Line | Case | Purpose/key assertion |
|---|---|---|
| 33 | preserves tool-call metadata received only on toolcall_end | namespace appears only at toolcall_end, and both the event and the final message keep it |
| 78 | processes terminal metadata when the event is not newline-terminated | The last line without `\n` is still processed: events ["start","done"], providerThinkingLevel="high" |
| 99 | emits an error instead of hanging when the stream ends without a terminal event | Events ["start","error"]; stopReason=error; errorMessage contains "Connection closed by proxy server" |

### 9.6 Test infrastructure porting requirements

1. **Scripted StreamFn**: low-level tests do not depend on the faux provider; they construct streams directly and push events per script. Kotlin needs a `ScriptedStream` test utility that:
   - returns the write end of an `AssistantMessageEventStream`;
   - lets `runTest` control event timing with `launch`/`CompletableDeferred`;
   - captures `(model, context, options)` for assertions;
   - supports shapes such as "only done (no start)", "error", and "start then delayed delta" (existing tests heavily use the done-only shape, e.g. `test/agent-loop.test.ts:127-134`).
2. **Gating**: parallel ordering/concurrency cases rely on `Deferred` gates (TS uses Promise+setTimeout); Kotlin uses `CompletableDeferred` and must not use sleep, to avoid flakiness. Only the e2e abort uses real time (30ms); Kotlin can use `withTimeout`/real `delay`.
3. **Reading final results**: tests consume both the event stream and `result()`; Kotlin's `Flow` is collected once plus `Deferred.await()`. Start collecting before awaiting (or start collection concurrently) to avoid deadlock (TS's EventStream supports iterator-then-result).
4. **Assertion helpers**: `assertEventTypes(events, listOf("agent_start", ...))`, `assertMessageRoles(...)`, `assertToolResultOrder(...)`; unwrap event payload types with a sealed `when`.
5. **Faux provider (e2e)**: the pi-ai test module needs an equivalent implementation:
   - `registerFauxProvider({models?, tokensPerSecond?, tokenSize?})` → returns a registration object with `setResponses([...])`, `getModel()`, `state.callCount`, `unregister()` (around `ai/providers/faux.ts:120-180`);
   - `fauxAssistantMessage(text|blocks, {stopReason?, ...})`, `fauxText`, `fauxThinking`, `fauxToolCall(name,args,{id?})` (around `ai/providers/faux.ts:70-120`);
   - responses can be functions `(context, options, state, model) => AssistantMessage` for multi-turn context assertions (`test/e2e.test.ts:231-238`);
   - streaming chunking is controlled by `tokensPerSecond`/`tokenSize`; `abort` produces an aborted message (`test/e2e.test.ts:208-219` relies on this);
   - unregister after each case to avoid polluting the global registry (`test/e2e.test.ts:18-37`).
6. **Test utility ports**: `calculate.ts` (replace `new Function` with an expression evaluator), `get-current-time.ts`, `wait-for-tick.ts`.

### 9.7 Acceptance criteria

A. Functional acceptance (all required):

1. All 92 ported test executions pass; test names and groupings map one-to-one to TS (Kotlin tests can keep the English names via DisplayName for comparison).
2. The five exact event sequences use hard assertions (element-by-element type list comparison), not merely `contains`: 3.2(a)(b)(c)(d)(e/f).
3. All fixed strings match verbatim (see 10.1); concurrency/state error paths throw `IllegalStateException` with text verbatim from TS.
4. Parallel ordering assertions use gates rather than time: end completion order ([tool-2,tool-1]), message source order ([tool-1,tool-2]), turn_end.toolResults source order.
5. Tool argument mutability: beforeToolCall mutates args in place → execute receives the mutated value (B13); afterToolCall's structuredContent drop rule (B16).
6. abort: after Agent.abort(), prompt returns normally with the final message stopReason=aborted and a non-empty state.errorMessage; idle abort does not throw; tools/subscribers still receive the same signal.
7. Lifecycle: isStreaming flips to false only after agent_end listeners settle; waitForIdle is equivalent; the concurrency-guard text for prompt/continue/reset is exact.
8. Queues: the three continue tail branches, both QueueModes, peek not consuming, and end/error/aborted not consuming queues.
9. Assembly semantics: call timing, call counts, and return-value scope for prepareRequest/prepareNextTurn/finishTurn (B17-B20); tool declaration diff (B40).
10. Event payload identity: `message_end.message` is the same instance as `state.messages.last()`; `turn_end.toolResults` are the same instances as the toolResults in state (a coding-agent dependency, `ca/core/agent-session.ts:1218-1287`).
11. Message mutability: toolResult's `nestedCalls`/`usage` can be modified in a `message_start` listener and persist into state (a coding-agent usage, `ca/core/agent-session.ts:1107-1122`); assistant messages can have their fields replaced wholesale in place (`1271-1287`).
12. The three proxy cases pass; SSE line buffering, terminal state without a newline, and EOF fallback behave identically.

B. Engineering acceptance (Kotlin-specific):

1. `:pi-agent:test` has no coroutine leaks (`runTest` checks for child coroutines at the end) and no uncaught exceptions (configure a `CoroutineExceptionHandler` or assert in tests).
2. Parallel tool tests are stable under different dispatchers (single-threaded `limitedParallelism(1)` and the default test dispatcher); ordering assertions do not depend on real sleep.
3. `Agent`'s public API matches coding-agent's dependency surface (section 7), so compiling the `:pi-coding-agent` port branch requires no changes to pi-agent's exports.
4. Every `B01-B50` has a corresponding reference in the test code (test comments/names annotated with the number) for regression audits.

## 10. Appendix

### 10.1 Fixed strings and constants

Error/prompt text (verbatim):

| Text | Source |
|---|---|
| `No default stream function configured. Pass streamFn explicitly or call setDefaultStreamFn().` | `stream-fn.ts:17` |
| `Cannot continue: no messages in context` | `agent-loop.ts:87,145`; asserted in `test/agent-loop.test.ts:2028` |
| `Cannot continue from message role: assistant` | `agent-loop.ts:91,149` |
| `Tool ${toolCall.name} not found` | `agent-loop.ts:730` |
| `Operation aborted` | `agent-loop.ts:633,751,770` |
| `Tool execution was blocked` | `agent-loop.ts:756` |
| `Tool call "${toolCall.name}" was not executed: the response hit the output token limit, so its arguments may be truncated. Re-issue the tool call with complete arguments.` | `agent-loop.ts:503` |
| `Agent is already processing. Wait for completion before resetting.` | `agent.ts:366` |
| `Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion.` | `agent.ts:384-386` |
| `Agent is already processing. Wait for completion before continuing.` | `agent.ts:395` |
| `No messages to continue from` | `agent.ts:400` |
| `Cannot continue from message role: assistant` | `agent.ts:416` |
| `Agent is already processing.` | `agent.ts:519` |
| `Agent listener invoked outside active run` | `agent.ts:617` |
| `Request aborted by user` | `proxy.ts:138,196,209` |
| `Proxy error: ${status} ${statusText}` / `Proxy error: ${error}` | `proxy.ts:162-171` |
| `Connection closed by proxy server before the response completed` | `proxy.ts:224` |
| `Received text_delta for non-text content` and 4 other proxy type errors | `proxy.ts:280,294,312,326,352` |

Constants:

| Constant | Value | Source |
|---|---|---|
| `DEFAULT_AGENT_IDENTITY` | `{attribution: PI_ATTRIBUTION}`; `PI_ATTRIBUTION = {name:"pi", url:"https://pi.dev", categories:["cli-agent"]}` | `agent-loop.ts:41`; `ai/utils/identity.ts:24` |
| `EMPTY_USAGE` | `{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{all zeros}}` | `agent.ts:49-56` |
| `DEFAULT_MODEL` | `{id:"unknown",name:"unknown",api:"unknown",provider:"unknown",baseUrl:"",reasoning:false,input:[],cost:{0},contextWindow:0,maxTokens:0}` | `agent.ts:58-69` |
| Default queue mode | `"one-at-a-time"` (steering and follow-up) | `agent.ts:255-256` |
| Default tool execution | `"parallel"` | `agent.ts:261` |
| Default transport | `"auto"` | `agent.ts:259` |
| Default thinkingLevel | `"off"` | `agent.ts:94` |
| Tool declaration message | `{role:"system", content:"", timestamp: Date.now()}` | `agent-loop.ts:368` |
| Error tool result | `{content:[{type:"text",text:message}], details:{}}` | `agent-loop.ts:922-927` |

### 10.2 Evidence index (by topic)

| Topic | Primary evidence |
|---|---|
| Event definitions | `types.ts:509-539` |
| Event sequences and loop control flow | `agent-loop.ts:111-330` |
| Tool declaration diff | `agent-loop.ts:332-384`; `ai/utils/transcript.ts:58-167` |
| Assistant streaming mapping | `agent-loop.ts:386-479`; `ai/types.ts:796-828`; `ai/utils/event-stream.ts:91-130` |
| Tool execution | `agent-loop.ts:481-959` |
| finishTurn/prepareRequest/prepareNextTurn | `types.ts:146-191,256-306`; `agent-loop.ts:194-248,289-323` |
| Agent lifecycle | `agent.ts:83-112,149-186,238-623` |
| Queues and continue | `agent.ts:149-180,289-342,392-420,476-515` |
| proxy protocol | `proxy.ts:20-70,107-393` |
| Default StreamFn | `stream-fn.ts:1-20`; `agent.ts:244`; `agent-loop.ts:133,158` |
| pi-ai message/model types | `ai/types.ts:420-480,542-650,760-828,830-904` |
| Transcript/identity/validation | `ai/utils/transcript.ts`, `ai/utils/identity.ts`, `ai/utils/validation.ts:295-353` |
| coding-agent dependencies | `ca/core/sdk.ts:403-445`; `ca/core/agent-session.ts:19-33,512-520,645-760,788-944,1104-1287,1884-1893,2280-2380` |
| Tests | `test/agent-loop.test.ts`, `test/agent.test.ts`, `test/e2e.test.ts`, `test/proxy.test.ts` |

