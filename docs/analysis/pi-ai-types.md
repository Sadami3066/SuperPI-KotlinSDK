# pi-ai type model and event protocol (Kotlin modeling baseline)

Version baseline: `@earendil-works/pi-ai` 1.3.0 (source `upstream pi checkout: packages/ai`; version in `src/utils/version.ts:2`).
All line numbers are `file:line` relative to `packages/ai`.

Goal of this document: an implementer can write the Kotlin data models, serializers, event streams, and acceptance tests from this document alone, without reading the TypeScript again.

---

## 0. General modeling principles

1. Two naming domains must be modeled separately:
   - In-process/session JSON: all camelCase, with field names exactly matching the TypeScript types in this document (`AssistantMessage`, `ToolCall`, `Usage`, etc.; see `src/types.ts:414-671`). pi's session persistence uses this shape, and Kotlin must not change field names, otherwise it cannot read or write existing pi sessions.
   - Wire: each provider's native protocol, mostly snake_case (OpenAI `prompt_tokens`, Anthropic `input_tokens`, Bedrock `inputTokens`, Mistral `prompt_tokens`, etc.). Wire DTOs are kept separate from in-memory types, and conversion code is written per API implementation.
   - Exception: the event JSON of the `pi-messages` custom protocol is camelCase (`src/api/pi-messages.ts:55-96`).
2. All union types use `sealed interface/class`: `Message` (4 variants), `AssistantMessageEvent` (12 variants), `Content` (4 variants), `Credential` (2 variants), `ClassifierAnswer` (3 variants), `AuthEvent` (4 variants).
3. Nullability semantics: TypeScript's `?:` means "may be missing", not "the value is null". Kotlin uses nullable + `encodeDefaults = false`; do not use `null` to mean omitted, or writing JSON back adds `"field": null` and pollutes pi sessions.
4. Time: every `timestamp` is Unix milliseconds Long; `durationMs` is milliseconds Long.
5. `arguments`/`details`/`state` are arbitrary JSON: Kotlin uses `kotlinx.serialization.json.JsonObject`, not fixed data classes.
6. The model catalog JSON gains fields across pi versions (for example radius's `lab`/`enabled`/`providers`), so Kotlin deserialization must use `ignoreUnknownKeys = true`; server-side catalogs may even carry a `providers` sub-array.
7. Enum wire values must be string constants, never ordinal; unknown values must degrade gracefully (stop reason and similar have default branches).

---

## 1. Scalars and enums

### 1.1 API / Provider / model types

| Type | Values | Evidence |
|---|---|---|
| `KnownApi` | `openai-completions`, `mistral-conversations`, `openai-responses`, `azure-openai-responses`, `openai-codex-responses`, `anthropic-messages`, `bedrock-converse-stream`, `google-generative-ai`, `google-vertex`, `pi-messages` | `src/types.ts:62-72` |
| `Api` | `KnownApi \| (string & {})` (custom strings allowed) | `src/types.ts:74` |
| `KnownImageApi` | `openrouter-images` | `src/types.ts:76` |
| `KnownClassifierApi` | `typesafe-system-one`, `cloudflare-workers-ai-system-one`, `llama-cpp-classify`, `openai-decisions` | `src/types.ts:80-84` |
| `KnownProvider` | 42: `amazon-bedrock`, `ant-ling`, `anthropic`, `google`, `google-vertex`, `openai`, `azure`, `openai-codex`, `radius`, `typesafe`, `nvidia`, `deepseek`, `github-copilot`, `xai`, `groq`, `cerebras`, `openrouter`, `vercel-ai-gateway`, `zai`, `zai-coding-cn`, `mistral`, `minimax`, `minimax-cn`, `moonshotai`, `moonshotai-cn`, `huggingface`, `fireworks`, `together`, `baseten`, `opencode`, `opencode-go`, `kimi-coding`, `meta`, `cloudflare-workers-ai`, `cloudflare-ai-gateway`, `qwen-token-plan`, `qwen-token-plan-cn`, `qwen-token-plan-individual`, `xiaomi`, `xiaomi-token-plan-cn`, `xiaomi-token-plan-ams`, `xiaomi-token-plan-sgp` | `src/types.ts:88-130` |
| `ProviderId` | `KnownProvider \| string` | `src/types.ts:131` |
| `ModelType` | `chat` / `image` / `classifier` | `src/types.ts:894-901` |
| `AnyModel` | `Model \| ImageModel \| ClassifierModel` | `src/types.ts:904` |

Model type determination: a missing `type` field is treated as `chat` (`src/utils/model-operations.ts:18-25`), so `Model`'s `type?: "chat"` is optional (`src/types.ts:845-851`), while `ImageModel.type: "image"` and `ClassifierModel.type: "classifier"` are required.

### 1.2 Reasoning levels and caching

| Type | Values | Evidence |
|---|---|---|
| `ThinkingLevel` | `minimal`, `low`, `medium`, `high`, `xhigh`, `max` | `src/providers/model-schema.ts:3` |
| `ModelThinkingLevel` | `off` + the 6 above | `src/providers/model-schema.ts:4` |
| `ThinkingLevelMap` | `Partial<Record<ModelThinkingLevel, string \| null>>`; `null` = the level is unsupported; an absent key = use the provider default (for higher levels, xhigh/max are treated as unsupported) | `src/providers/model-schema.ts:14-18`, semantics noted at `src/types.ts:853-857` |
| `ModelInputModality` | `text` / `image` | `src/providers/model-schema.ts:5` |
| `CacheRetention` | `none` / `short` / `long` (default `short`) | `src/providers/model-schema.ts:6`, `src/types.ts:245-249` |
| `ModelPromptCache` | `{ short?: seconds, long?: seconds }`, positive numbers | `src/providers/model-schema.ts:7,20-26` |
| `Transport` | `sse` / `websocket` / `websocket-cached` / `auto` (codex only) | `src/types.ts:145` |
| `GrammarFormat` | `openai_lark` / `openai_regex` | `src/types.ts:739` |
| `SessionAffinityFormat` | `openai` / `openai-nosession` / `openrouter` | `src/providers/compat-schema.ts:4-6` |
| `ThinkingTokenBudgetField` | `thinking_token_budget` / `thinking_budget` / `thinking_budget_tokens` | `src/providers/compat-schema.ts:12-17` |
| `ToolChoice` (simple) | `auto` / `none` | `src/types.ts:133` |
| `AuthType` | `api_key` / `oauth` | `src/auth/types.ts:117` |

### 1.3 stop reason

`StopReason = pending | stop | length | toolUse | error | aborted | deferred` (`src/types.ts:480`).
Images: `ImagesStopReason = stop | error | aborted` (`src/types.ts:659`). Classifiers: `ClassifierStopReason` matches images (`src/types.ts:722`).
`done` event reasons allow only `stop|length|toolUse|deferred`; `error` events allow only `aborted|error` (`src/types.ts:823-828`).
`pending` exists only while the stream is in flight and must be replaced before termination, otherwise each API throws "stream ended without ..." (e.g. `src/api/anthropic-messages.ts:898-900`).

### 1.4 Base JSON types

`JsonValue = null | boolean | number | string | JsonValue[] | JsonObject`, `JsonObject = map<string, JsonValue>` (`src/types.ts:482-483`). Kotlin uses `JsonElement`/`JsonObject`.

---

## 2. Content blocks (Content)

Union: `TextContent | ThinkingContent | ImageContent | ToolCall`. Kotlin uses `@Serializable @JsonClassDiscriminator("type")` polymorphism, with `type` as the discriminator key.

### 2.1 TextContent

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | `"text"` | Yes | Discriminator key |
| `text` | String | Yes | The text |
| `textSignature` | String? | No | OpenAI Responses entry metadata: a legacy message id string, or a `TextSignatureV1` JSON string (`src/types.ts:420-424`) |

`TextSignatureV1 = { v: 1; id: string; phase?: "commentary" | "final_answer" }` (`src/types.ts:414-418`); encoding/parsing is in `src/api/openai-responses-shared.ts:54-80`. Responses replay uses it to restore `message.id` and `phase` (`src/api/openai-responses-shared.ts:339-359`).

### 2.2 ThinkingContent

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | `"thinking"` | Yes | |
| `thinking` | String | Yes | Thinking text; the fixed placeholder `[Reasoning redacted]` when redacted |
| `thinkingSignature` | String? | No | Provider-private replay data: Anthropic signature, OpenAI reasoning item JSON, Google thoughtSignature, Bedrock base64 encrypted block (`src/types.ts:426-434`) |
| `redacted` | Boolean? | No | A redacted block caused by safety filtering; the ciphertext is in `thinkingSignature` and must be sent back as-is |

Key invariants:
- Thinking is dropped when replaying across models/providers; signatures are only replayed for the same provider and same model (`src/api/transform-messages.ts:127-138`).
- Anthropic's signature accumulates from streaming `signature_delta` (`src/api/anthropic-messages.ts:808-815`).
- Google's `thoughtSignature` is replayed only for the same provider+model and only when it is valid base64 (`src/api/google-shared.ts:148-162`).
- OpenAI Responses reasoning items are stored wholesale as a JSON string in `thinkingSignature` (`src/api/openai-responses-shared.ts:766-771`).

### 2.3 ImageContent

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | `"image"` | Yes | |
| `data` | String | Yes | base64 (without the data URL prefix) |
| `mimeType` | String | Yes | `image/jpeg`, `image/png`, etc. |

On the wire it converts to `data:<mimeType>;base64,<data>` or the provider's native inline structure; see section 10.

### 2.4 ToolCall

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | `"toolCall"` | Yes | |
| `id` | String | Yes | May be constrained by provider format; Responses uses a composite `callId\|itemId` id |
| `name` | String | Yes | Tool name |
| `arguments` | JsonObject | Yes | Parsed arguments; `{}` when the JSON is invalid |
| `thoughtSignature` | String? | No | Google-specific thought context signature |
| `namespace` | String? | No | OpenAI Responses dynamic/namespaced tools |
| `argumentsError` | String? | No | When the complete argument text is not a valid JSON object, `arguments={}` and this field describes the error and includes the original text (`src/types.ts:442-455`, implementation `src/utils/json-parse.ts:106-120`) |

Note: `arguments` is an "argument object", not a JSON string. Most wire protocols deliver string increments, so Kotlin must maintain a partial buffer per API before parsing.

---

## 3. Message types (Message)

`Message = SystemMessage | UserMessage | AssistantMessage | ToolResultMessage` (`src/types.ts:650`). The discriminator key is `role`, with values `system|user|assistant|toolResult`.

### 3.1 SystemMessage

| Field | Type | Required | Notes |
|---|---|---|---|
| `role` | `"system"` | Yes | |
| `content` | `String \| TextContent[]` | Yes | The first = base system prompt; later ones = appended instructions |
| `sections` | `Map<String, String?>` | No | Named prompt sections; `null` deletes that section; rendered after `content` (`src/types.ts:552-568`) |
| `toolsAdded` | `Tool[]` | No | Complete tool definitions available from this point on |
| `toolsRemoved` | `{name}[]` | No | Tools removed from this point on |
| `timestamp` | Long | Yes | Milliseconds; the synthesized leading message is fixed to 0 (`src/utils/transcript.ts:17-22`) |

Replay model: replay all system messages in order to obtain the current prompt + tool set (`src/utils/transcript.ts:58-96`). APIs that support only a single system message first fold them with `collapseSystemMessages` (`src/utils/transcript.ts:104-120`). Kotlin must implement this replay, otherwise sessions with mid-conversation system messages or tool changes cannot be sent correctly.

### 3.2 UserMessage

| Field | Type | Required |
|---|---|---|
| `role` | `"user"` | Yes |
| `content` | `String \| (TextContent \| ImageContent)[]` | Yes |
| `timestamp` | Long | Yes |

### 3.3 AssistantMessage

| Field | Type | Required | Notes |
|---|---|---|---|
| `role` | `"assistant"` | Yes | |
| `content` | `(TextContent \| ThinkingContent \| ToolCall)[]` | Yes | May be mixed and ordered |
| `api` | `Api` | Yes | The API that produced this response |
| `provider` | `ProviderId` | Yes | |
| `model` | String | Yes | The requested model id |
| `responseModel` | String? | No | The model the provider actually returned (set only when different from the request) |
| `responseId` | String? | No | Provider response id |
| `providerThinkingLevel` | String? | No | The effort string actually sent to the provider |
| `thinkingLevel` | `ModelThinkingLevel`? | No | The level requested by the agent loop |
| `diagnostics` | `AssistantMessageDiagnostic[]` | No | Redacted diagnostics |
| `usage` | `Usage` | Yes | See 3.5 |
| `stopReason` | `StopReason` | Yes | |
| `deferred` | `DeferredHandle` | No | Async response handle |
| `errorMessage` | String? | No | Error when stopReason=error/aborted |
| `rawStopReason` | String? | No | Provider's raw stop reason |
| `endTurn` | Boolean? | No | Whether the provider explicitly ended the turn |
| `timestamp` | Long | Yes | Request start time (ms) |
| `durationMs` | Long? | No | Filled in by the event stream on the terminal message (`src/utils/event-stream.ts:126-129`) |

`DeferredHandle`: `{ provider, modelId, api, id, expiresAt?, pollAfterMs?, data? }` (`src/types.ts:530-540`).

### 3.4 ToolResultMessage

| Field | Type | Required | Notes |
|---|---|---|---|
| `role` | `"toolResult"` | Yes | |
| `toolCallId` | String | Yes | Corresponds to ToolCall.id |
| `toolName` | String | Yes | |
| `content` | `(TextContent \| ImageContent)[]` | Yes | Images supported |
| `details` | JsonValue? | No | Tool-defined structured result |
| `usage` | `Usage`? | No | The tool's own cost; not part of the main context |
| `nestedCalls` | `NestedToolCalls`? | No | Records of nested tool calls; not sent to the model |
| `isError` | Boolean | Yes | |
| `timestamp` | Long | Yes | |
| `durationMs` | Long? | No | |

`NestedToolCalls = { calls: NestedToolCallRecord[]; complete: boolean }`; record fields `id,name,arguments?,argumentsBytes?,status("ok"|"error"|"unfinished"),durationMs?,error?` (`src/types.ts:609-629`).

### 3.5 Usage / Cost

| Field | Type | Required | Notes |
|---|---|---|---|
| `input` | Long | Yes | Non-cached input tokens |
| `output` | Long | Yes | Output tokens (already include reasoning) |
| `cacheRead` | Long | Yes | Cache-hit read tokens |
| `cacheWrite` | Long | Yes | Cache-write tokens |
| `cacheWrite1h` | Long? | No | Subset of cacheWrite written with the 1h retention policy (reported only by Anthropic/Bedrock) |
| `reasoning` | Long? | No | Reasoning tokens, a subset of output; null for providers that do not report it |
| `totalTokens` | Long | Yes | Per-provider semantics in section 10 |
| `cost.input/output/cacheRead/cacheWrite/total` | Double | Yes | USD, rounded to about 1e-6 |

`calculateCost` rules (`src/models.ts:1236-1256`):
- `inputTokens = input + cacheRead + cacheWrite`.
- Pricing tiers: among `cost.tiers[]`, the tier with the highest threshold where `inputTokens > tier.inputTokensAbove`; the whole request uses that tier's rates, otherwise base rates apply.
- `cost.input = rate.input/1e6*input`, and likewise for output/cacheRead.
- `cost.cacheWrite = (rate.cacheWrite*shortWrite + rate.input*2*longWrite)/1e6`, where `longWrite=usage.cacheWrite1h` and `shortWrite=cacheWrite-longWrite` (Anthropic 1h cache writes are charged at 2× the input price).
- OpenAI Responses' `service_tier` applies a multiplier after the calculation: `flex=0.5`, `priority/fast=2` (2.5 for gpt-5.5), see `src/api/openai-responses.ts:431-459`.

## 4. Event protocol (AssistantMessageEvent)

Defined at `src/types.ts:812-828`. One response's streaming protocol has 12 variants:

| type | Fields | Semantics |
|---|---|---|
| `start` | `partial: AssistantMessage` | Must precede any update/done; exactly once per response |
| `text_start` | `contentIndex: Int`, `partial` | A text block appears; at this event the block's text in `partial` is empty |
| `text_delta` | `contentIndex`, `delta: String`, `partial` | Incremental append |
| `text_end` | `contentIndex`, `content: String`, `partial` | Final authoritative text |
| `thinking_start` | `contentIndex`, `partial` | A thinking block appears |
| `thinking_delta` | `contentIndex`, `delta`, `partial` | |
| `thinking_end` | `contentIndex`, `content`, `partial` | |
| `toolcall_start` | `contentIndex`, `partial` | A tool call appears; arguments may not be parseable yet |
| `toolcall_delta` | `contentIndex`, `delta: String`, `partial` | Argument JSON text increments |
| `toolcall_end` | `contentIndex`, `toolCall: ToolCall`, `partial` | Final argument object |
| `done` | `reason: stop\|length\|toolUse\|deferred`, `message: AssistantMessage` | Successful termination |
| `error` | `reason: aborted\|error`, `error: AssistantMessage` | Failed termination |

Invariants (`src/types.ts:796-811`):
1. `start` comes first; updates/done must not precede `start`.
2. `partial` is the same object as "the live response so far", not a snapshot; text/thinking blocks are empty at `*_start`, grow only through the corresponding `*_delta`, and `*_end` gives the authoritative value.
3. Redacted thinking may already be complete at start, with no deltas afterwards.
4. At `toolcall_start`, `arguments` depends on the provider; `toolcall_delta` carries subsequent JSON increments.
5. No events are accepted after termination (`src/utils/event-stream.ts:43-58`).
6. Terminal messages get `durationMs`; forwarded streams (deferred fetch) do not overwrite existing values and do not time messages older than the stream's start (`src/utils/event-stream.ts:91-129`).
7. `stream()` may throw synchronously (missing auth); once a stream is returned, request/model/runtime errors must be encoded as `error` events (`src/types.ts:385-400`). Kotlin cannot both return a Flow and throw, so model it explicitly: `fun stream(...): StreamHandle`, catching setup exceptions internally and converting them to error events; the synchronous-throw semantics of `streamSimple` can use a returned `Result`/`StreamHandle` or throw directly (per caller needs).
8. `result()`: `done.message` or `error.error` as the final `AssistantMessage` Promise (`src/utils/event-stream.ts:97-130`); `complete()` = `stream().result()` (`src/models.ts:921-927`).

All event timestamps are milliseconds Long; `contentIndex` starts at 0, increments, and is valid in the final message.content.

---

## 5. Tools and JSON Schema

### 5.1 Tool

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | String | Yes | |
| `description` | String | Yes | |
| `parameters` | `TSchema` (TypeBox, essentially a JSON Schema object) | Yes | Kotlin stores the raw JSON Schema in `JsonObject` |
| `constrainedSampling` | `false \| ConstrainedSamplingConfig`? | No | Constrained sampling declaration |

`ConstrainedSamplingConfig`:
- `{ type: "json_schema"; strict: "prefer" | "require" }`
- `{ type: "grammar"; variants: { openai_lark?: string; openai_regex?: string } }` (`src/types.ts:739-758`)

### 5.2 strict JSON Schema conversion (must be ported)

`src/api/constrained-sampling.ts:15-124`:
- Rejected keys: `$ref,$defs,definitions,allOf,oneOf,patternProperties,dependentSchemas,dependencies,unevaluatedProperties,propertyNames,contains,prefixItems,not,if,then,else`.
- `anyOf` only allows non-object/array structures, and each item is recursively strict.
- Objects must be `type:"object"`; `required` is only allowed when `properties` exists; `required` must not contain unknown properties.
- Properties that are not required and do not allow null are automatically wrapped as `anyOf:[schema,{type:"null"}]`.
- The final `required` = all property names, and `additionalProperties:false`.
- If `strict:"prefer"` fails, fall back to non-strict; if `strict:"require"` fails, throw (`src/api/constrained-sampling.ts:225-249`).

### 5.3 grammar constraints

`resolveGrammarConstrainedSampling` (`src/api/constrained-sampling.ts:251-284`) requires the parameter schema to be an object with exactly one required string property, whose name is used as the field carrying the grammar input; OpenAI custom tools are sent as `custom` with a string input, and streaming increments need `appendGrammarToolInputJsonDelta`'s bracket-completion algorithm (`src/api/constrained-sampling.ts:146-200`). It only takes effect when compat enables `supportsOpenAIGrammarTools`.

### 5.4 Tool argument parsing/repair

`src/utils/json-parse.ts`:
- `repairJson`: escapes bare control characters inside strings; prefixes a backslash before invalid escapes; drops a truncated `\u` (`:33-84`).
- `parseStreamingJson`: `JSON.parse` first; on failure repair then parse; then parse with `partial-json`; then partial-parse the repaired text; finally `{}` (`:129-149`). The Kotlin side must port the partial-json algorithm or an equivalent.
- `parseToolCallArguments`: the completed form must be a JSON object; otherwise `arguments={}` and an `argumentsError` is generated (`:106-120`).
- Validation: `validateToolArguments` / `validateToolCall` are compiled from TypeBox and return a structured `{ ok: false, errors }` on failure (`src/utils/validation.ts:302-353`). Kotlin should use a JSON Schema validation library (such as networknt/json-schema-validator); the failure text must be returnable to the model by the tool.

---

## 6. Model catalog data model (Model / ImageModel / ClassifierModel)

### 6.1 Base fields

After merging `BaseModel` (`src/types.ts:831-842`) and `Model` (`:845-878`), the actual field set of model JSON (based on a full count of the 1.3.0 pinned catalog):

| JSON field | Type | Scope | Notes |
|---|---|---|---|
| `id` | String | All | Model id, unique within the provider |
| `name` | String | All | Display name |
| `api` | String | All | Must be a registered API implementation |
| `provider` | String | All | provider id |
| `baseUrl` | String | All | The model's actual endpoint |
| `type` | `"chat"\|"image"\|"classifier"` | All | Chat may be omitted in source, but generated data explicitly carries `"chat"` (`getModels` at `src/models.ts:1127-1128` relies on `isModelType`) |
| `input` | `("text"\|"image")[]` | All | Non-empty |
| `inputLimits` | `ModelInputLimits`? | Common for chat | Request/image preprocessing limits |
| `cost` | `ModelCost` | All | All 4 rates required |
| `headers` | `map<String,String>`? | Some (e.g. github-copilot) | Model-level default headers |
| `reasoning` | Boolean | Required for chat | |
| `thinkingLevelMap` | `ThinkingLevelMap`? | Some chat models | |
| `promptCache` | `{short?: seconds, long?: seconds}`? | Some chat models | |
| `contextWindow` | Long | Required for chat/classifier | |
| `maxTokens` | Long | Required for chat | Output limit |
| `samplingParams` | `Map<String, JsonValue>`? | Optional | Model default sampling parameters |
| `samplingParamsByThinkingLevel` | `Map<ModelThinkingLevel, Map>`? | Optional | Per-level overrides |
| `compat` | per-api object? | Some chat models | See 6.4 |
| `output` | `("text"\|"image")[]` | Required for image | Contains `image` |
| `lab` / `enabled` / `providers` | any | radius only (gateway pass-through) | Not pi model fields; Kotlin must ignore them |
| Extension fields beyond `type` | any | Catalog evolution | ignoreUnknownKeys |

`ModelCost`: `{ input, output, cacheRead, cacheWrite, tiers? }`, in USD per million tokens; `tiers[] = { inputTokensAbove, input, output, cacheRead, cacheWrite }` (`src/providers/model-schema.ts:28-49`).
`ModelInputLimits` (`src/providers/model-schema.ts:51-86`): `maxRequestBytes?`, `images?: { resize?: {maxWidth?,maxHeight?,maxBytes?,jpegQuality?}, maxPerMessage?, maxPerRequest? }`.

### 6.2 Type discrimination

- `getModelType(model) = model.type ?? "chat"` (`src/utils/model-operations.ts:18-20`).
- `isModelType` / `assertChatModel` / `assertImageModel` / `assertClassifierModel` (`:22-43`).
- `hasApi(model, api)` is true only for chat models with an equal api (`src/models.ts:1220-1234`).

### 6.3 Two catalog shapes

1. Published catalog `models.all.json`: `{ "<providerId>": [model, ...] }`; values are arrays, and the same id may appear under different types (for example both a chat and an image entry); consumed by `scripts/hydrate-model-catalog.ts:37-61` and also returned by pi.dev `/api/models/revisions/<rev>?types=chat,image,classifier`.
2. In-package grouped data `src/providers/data/<provider>.json`: `{ "<api>": { "<type>:<id>": model } }`, keyed by `type:id`, used for TypeScript type inference (`scripts/model-data.ts:308-335`, `scripts/model-data.ts:72-87`). The installed 1.0.0 build uses this shape: 42 files, 968 KB.
3. Generated TS aggregates: `src/models.generated.ts` exports `MODELS` (`:47`), `IMAGE_MODELS` (`:135`), and `CLASSIFIER_MODELS` (`:223`); each provider has a `providers/<id>.models.ts` that internally filters by `type` via `flattenChatModelCatalog` and similar into an id→model map (`src/model-catalog.ts:53-80`).

### 6.4 compat fields (Kotlin must model them fully)

Unified entry `src/providers/compat-schema.ts:604-612`, with a schema per API.

**OpenAICompletionsCompat** (`src/providers/compat-schema.ts:188-350`):

| Field | Type | Default/automatic |
|---|---|---|
| `supportsStore` | Bool | Detected from URL |
| `supportsDeveloperRole` | Bool | Detected |
| `supportsReasoningEffort` | Bool | Detected |
| `supportsUsageInStreaming` | Bool | true by default |
| `supportsFinishReason` | Bool | true by default; when false, infer stop/toolUse at stream end |
| `maxTokensField` | `max_completion_tokens`\|`max_tokens` | Detected |
| `requiresToolResultName` | Bool | false by default |
| `requiresAssistantAfterToolResult` | Bool | false by default |
| `requiresThinkingAsText` | Bool | false by default |
| `requiresReasoningContentOnAssistantMessages` | Bool | false by default (detected as true for DeepSeek) |
| `thinkingFormat` | `openai`\|`openrouter`\|`deepseek`\|`together`\|`baseten`\|`zai`\|`qwen`\|`chat-template`\|`qwen-chat-template`\|`string-thinking`\|`ant-ling` | Detected |
| `chatTemplateKwargs` / `chatTemplateArgs` | Map<String, String\|Number\|Bool\|Null\|{$var:...}> | `{}` by default |
| `openRouterRouting` | OpenRouterRouting | `{}` by default |
| `vercelGatewayRouting` | {only?, order?} | `{}` by default |
| `zaiToolStream` | Bool | false by default |
| `thinkingTokenBudgetField` | one of three | none |
| `supportsThinkingTokenBudget` | Bool | false by default (alias) |
| `supportsOpenAIGrammarTools` | Bool | false by default |
| `supportsMidConvoSystemMessages` | Bool | false by default |
| `supportsMidConvoToolAdditions` | Bool | false by default |
| `supportsStrictMode` | Bool | false by default |
| `cacheControlFormat` | `anthropic` | none |
| `sendSessionAffinityHeaders` | Bool | Detected |
| `sessionAffinityFormat` | one of three | Detected |
| `supportsLongCacheRetention` | Bool | Detected |
| `vllmPriority` | Number | none |

**OpenAIResponsesCompat** (`src/providers/compat-schema.ts:352-414`): `supportsDeveloperRole` (true by default), `supportsMidConvoSystemMessages` (false), `sessionAffinityFormat`, `supportsLongCacheRetention` (true), `supportsStrictMode`, `supportsOpenAIGrammarTools` (false), `supportsAdditionalTools` (false), `supportsToolSearch` (false), `supportsExplicitPromptCacheMode` (false), `supportsMidConvoEffort` (false), `supportsMaxOutputTokens` (true).

**AnthropicMessagesCompat** (`src/providers/compat-schema.ts:416-501`): `supportsEagerToolInputStreaming` (true), `supportsLongCacheRetention` (true), `sendSessionAffinityHeaders`, `sessionAffinityFormat`, `supportsCacheControlOnTools` (true), `supportsTemperature` (true), `forceAdaptiveThinking` (false), `allowEmptySignature` (false), `supportsStrictTools` (false), `supportsMidConvoEffort` (false), `supportsMidConvoSystemMessages` (false), `supportsMidConvoToolChanges` (false), `allowedFallbackModels` (at most 3 `{provider,model,cost}`).

**BedrockCompat**: only `supportsStrictMode` (false) (`:503-511`).
**MistralConversationsCompat**: only `supportsMidConvoSystemMessages` (false) (`:513-522`).

`ProviderCompatSchema` is the merged superset of the above, used for custom models.json (`src/providers/compat-schema.ts:614-622`).

### 6.5 Catalog validation rules

`scripts/model-data.ts:144-206` validates:
- The key must equal `<type>:<id>`; `value.id` equals the id, `value.provider` equals the provider, and `value.api` equals the API group it is under.
- `name` is non-empty; `baseUrl` is a string; `input` is a non-empty modality array.
- image must have an `output` containing image, and no other type may carry output.
- chat must have a boolean `reasoning`, a positive `contextWindow`, and a positive `maxTokens`; classifier must have a positive `contextWindow`.
- The 4 `cost` fields must be finite numbers.
These rules can be translated directly into Kotlin catalog-loading assertions.

## 7. Request option types

### 7.1 Common

`ProviderRequestOptions` (`src/types.ts:152-216`):

| Field | Type | Notes |
|---|---|---|
| `signal` | AbortSignal? | Cancellation |
| `telemetry` | TelemetrySpan? | Trace span |
| `apiKey` | String? | Explicit key overriding auth resolution |
| `fetch` | FetchFunction? | Custom HTTP; some adapters such as Google/Codex may not support it |
| `env` | Map<String,String>? | Provider-scoped env, taking precedence over process.env |
| `onPayload` | (payload, model) => payload? | Request body rewrite hook |
| `onResponse` | (status, headers, model) => void | Response header hook |
| `headers` | Map<String, String\|null>? | Extra headers; null removes the same-named default header; for Bedrock they are injected after SigV4 |
| `identity` | ClientIdentity? | UA/attribution headers |
| `timeoutMs` | Long? | Request timeout |
| `maxRetries` | Int? | Client retry count; the SDK defaults to 2, but pi uses 0 and retries outside |
| `maxRetryDelayMs` | Long? | 60000 by default; 0 = no limit; beyond it, fail immediately |

`ClientIdentity = { userAgent?: "short"|"detailed"|{custom}, attribution?: {name,url,categories?}|null, version? }` (`src/utils/identity.ts:11-37`).

`StreamOptions` additions (`src/types.ts:218-268`): `onProviderStreamEvent?`, `temperature?`, `samplingParams?`, `maxTokens?`, `transport?`, `cacheRetention?`, `sessionId?`, `websocketConnectTimeoutMs?`, `metadata?`.
`SimpleStreamOptions` additions (`:374-383`): `toolChoice?: "auto"|"none"`, `reasoning?: ThinkingLevel`, `deferred?: boolean|{window}`, `thinkingBudgets?`.
`ThinkingBudgets = {minimal?,low?,medium?,high?}` (`:136-142`).

### 7.2 Per-API options

| API | Options interface | Specific fields |
|---|---|---|
| `openai-completions` | `OpenAICompletionsOptions` (`src/api/openai-completions.ts:154-159`) | `toolChoice` (OpenAI union type), `reasoningEffort: minimal\|low\|medium\|high\|xhigh\|max`, `thinkingBudgets` |
| `openai-responses` | `OpenAIResponsesOptions` (`src/api/openai-responses.ts:144-150`) | `reasoningEffort`, `reasoningSummary: auto\|detailed\|concise\|null`, `serviceTier`, `toolChoice` |
| `azure-openai-responses` | `AzureOpenAIResponsesOptions` (`src/api/azure-openai-responses.ts:31-36`) | `azureApiVersion`, `azureResourceName`, `azureBaseUrl`, `azureDeploymentName`, `reasoningEffort`, `toolChoice`, `reasoningSummary` |
| `anthropic-messages` | `AnthropicOptions` (`src/api/anthropic-messages.ts:235-295`) | `thinkingEnabled`, `thinkingBudgetTokens`, `effort: low\|medium\|high\|xhigh\|max`, `thinkingDisplay: summarized\|omitted`, `interleavedThinking`, `toolChoice: auto\|any\|none\|{type:"tool",name}`, `client` (injected SDK client) |
| `google-generative-ai` | `GoogleOptions` (`src/api/google-generative-ai.ts:47-54`) | `toolChoice: auto\|none\|any`, `thinking: {enabled,budgetTokens?,level?}` |
| `google-vertex` | `GoogleVertexOptions` (`src/api/google-vertex.ts:51-61`) | Same as above + `project`, `location` |
| `mistral-conversations` | `MistralOptions` (`src/api/mistral-conversations.ts:37-42`) | `toolChoice`, `promptMode: "reasoning"`, `reasoningEffort` |
| `bedrock-converse-stream` | `BedrockOptions` (`src/api/bedrock-converse-stream.ts:80-112`) | `region`, `profile`, `toolChoice`, `reasoning`, `thinkingBudgets`, `interleavedThinking`, `thinkingDisplay`, `requestMetadata`, `bearerToken` |
| `openai-codex-responses` | `OpenAICodexResponsesOptions` (`src/api/openai-codex-responses.ts:83-90`) | `reasoningEffort: none\|minimal\|low\|medium\|high\|xhigh\|max`, `reasoningSummary`, `serviceTier`, `textVerbosity`, `toolChoice: auto\|none\|required` |
| `pi-messages` | `PiMessagesOptions` (`src/api/pi-messages.ts:34-40`) | `reasoning`, `toolChoice: auto\|none\|required\|{type:"function",...}`, `debug` |
| Images | `ImagesOptions` (`src/types.ts:364-370`) | `metadata` |
| Classifiers | `ClassifierOptions` (`src/types.ts:355-362`) | `temperature` |

### 7.3 Defaults and limits

| Item | Default | Evidence |
|---|---|---|
| `cacheRetention` | `short` (changeable via env `PI_CACHE_RETENTION=long`) | `src/types.ts:245-249`, `src/api/openai-completions.ts:294-302` |
| simple reasoning level | `options.reasoning ?? "off"` | `src/api/simple-options.ts:42` |
| default thinking budget | minimal 1024 / low 2048 / medium 8192 / high 16384 | `src/api/simple-options.ts:71-76` |
| answer reserve tokens | `MIN_ANSWER_TOKENS = 1024`; when sharing a ceiling, the thinking budget is squeezed to `ceiling-1024` | `src/api/simple-options.ts:69,88-91` |
| maxTokens | `options.maxTokens ?? model.maxTokens`, then tightened by `contextWindow - estimateContextTokens - 4096`, minimum 1 | `src/api/simple-options.ts:15-22,46` |
| Anthropic thinking budget | `thinkingBudgetTokens \|\| 1024` | `src/api/anthropic-messages.ts:1303-1307` |
| Anthropic thinkingDisplay | `summarized` | `src/api/anthropic-messages.ts:1287,1294` |
| retries | Retry only on HTTP 408/409/429/5xx; `x-should-retry` takes precedence | `src/utils/provider-retry.ts:27-42` |
| retry backoff | `min(0.5*2^n,8)s * (1-0.25*random)`; prefer `retry-after-ms`/`retry-after` | `src/utils/provider-retry.ts:60-84` |
| server-requested delay cap | 60s by default; fail immediately beyond it | `src/utils/provider-retry.ts:44-58` |
| OAuth early refresh | 5 minutes before expiry | `src/auth/resolve.ts:102,161-191` |
| OAuth refresh timeout | 15s | `src/auth/resolve.ts:103,140` |
| stream duration | Measured with a monotonic clock and written to `durationMs` | `src/utils/event-stream.ts:97-129` |

Reasoning level clamping: `getSupportedThinkingLevels` and `clampThinkingLevel` (`src/models.ts:1258-1290`): `reasoning=false` returns only `["off"]`; `thinkingLevelMap[level]===null` means unsupported; `xhigh/max` are supported only with an explicit mapping; when the requested value is unsupported, search upward first, then downward.

## 8. Auth types

### 8.1 Credentials

| Type | Shape | Evidence |
|---|---|---|
| `ApiKeyCredential` | `{type:"api_key", key?: string, env?: Map<String,String>}` | `src/auth/types.ts:13-21` |
| `OAuthCredential` | `{type:"oauth", refresh: string, access: string, expires: Long(ms), ...arbitrary extensions}` | `src/auth/types.ts:23-34` |
| `Credential` | One of the above | `src/auth/types.ts:36-37` |
| On-disk auth.json | `{ "<providerId>": Credential }` | Shape shown at `src/cli.ts:20-24` |

`expires` is an absolute millisecond timestamp; each OAuth flow also puts extension fields on the credential, such as GitHub Copilot's `availableModelIds` (`src/providers/github-copilot.ts:28-33`), Radius's `gatewayConfig` (`src/providers/radius-config.ts:22-24`), and OpenAI ChatGPT's client id/scopes (`src/auth/oauth/openai-chatgpt.ts:155-175`).

### 8.2 Request-level auth and resolution results

`ModelAuth = { apiKey?, headers?: Map<String,String>, baseUrl? }` (`src/auth/types.ts:7-11`).
`AuthResult = { auth: ModelAuth, env?: Map, source?: string }` (`:103-110`). `source` is used by status UI, e.g. `"ANTHROPIC_API_KEY"`, `"OAuth"`, `"~/.aws/credentials"`.
`AuthCheck = { source?, type: "api_key"|"oauth" }` (`:112-115`).

### 8.3 CredentialStore (application-side implementation)

Interface (`src/auth/types.ts:65-94`): `read(providerId)`, `list()`, `modify(providerId, fn)`, `delete(providerId)`.
- `modify` is the only write path and must be serialized per provider (file locks are preferable across processes); `fn` sees the current credential, and returning undefined means unchanged.
- read returns undefined when missing; only storage failures reject.
- OAuth refresh must run inside the `modify` lock, to avoid concurrent/multi-process duplicate refreshes rotating the refresh token (`src/auth/resolve.ts:105-155`).
- Kotlin recommendation: a `Mutex` per provider + atomic file writes (tmp+rename), with a format fully compatible with pi auth.json for easy migration.

### 8.4 Login interaction

`AuthPrompt` (`src/auth/types.ts:125-130`): `text`/`secret`/`select`/`manual_code`, each with an optional `signal`.
`AuthEvent` (`:137-147`): `info{message,links?}`, `auth_url{url,instructions?}`, `device_code{userCode,verificationUri,intervalSeconds?,expiresInSeconds?}`, `progress{message}`.
`AuthInteraction` (`:156-161`): `prompt(prompt): Promise<String>` (select returns an option id), `notify(event)`, `signal`.
`LoginOptions` (`:202-214`): `getDeviceId?()`, `agentName?` (used by OpenAI ChatGPT/Codex login).

### 8.5 ProviderAuth structure

`ApiKeyAuth = { name, login?(interaction), check?({ctx,credential,signal}), resolve({ctx,credential,signal}) }` (`src/auth/types.ts:170-199`).
`OAuthAuth = { name, isSubscription?, loginLabel?, login(interaction, options?), refresh(credential, signal), toAuth(credential) }` (`:221-245`).
`ProviderAuth = { apiKey?: ApiKeyAuth, oauth?: OAuthAuth }`, with at least one (`:252-255`). Env-only providers must also supply apiKey auth (whose resolve reports whether it is configured).

### 8.6 Key constants of the 9 OAuth flows

| flow | authorize URL | token URL | client_id | redirect / mode | scope | Credentials |
|---|---|---|---|---|---|---|
| anthropic | `https://claude.ai/oauth/authorize` | `https://platform.claude.com/v1/oauth/token` | Parsed from base64: `9d1c250a-e61b-44d9-88ed-5944d1962f5e` | Local callback `http://localhost:53692/callback`, with copy-code fallback (`https://platform.claude.com/oauth/code/callback`) | `src/auth/oauth/anthropic.ts:26+` (including `org:create_api_key user:profile user:inference` etc.) | refresh/access/expires (expires 5 minutes early) `:123-141` |
| openai-chatgpt | `https://auth.openai.com/api/accounts/authorize` | `https://auth.openai.com/api/accounts/oauth/token` | `dynamic_agent_client` (dynamically registers a user client) | `http://127.0.0.1:1455/auth/callback` | `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct` | access/refresh/expires + extended client_id/scope |
| openai-codex | `https://auth.openai.com/oauth/authorize` (device flow can be used instead) | `https://auth.openai.com/oauth/token` | `app_EMoamEEZ73f0CkXaXp7hrann` | `http://localhost:1455/auth/callback`; device: `/api/accounts/deviceauth/usercode`, `/token`, `/codex/device` | `openid profile email offline_access` | access/refresh/expires; accountId extracted from the access JWT claim `https://api.openai.com/auth` |
| github-copilot | GitHub device flow | GitHub token endpoint | GitHub Copilot client id (base64-decodes to `Iv1.b507a08c87ecfe98`) | device code | — | `refresh` stores the GitHub token, `access` stores the short-lived copilot token; `toAuth` returns the baseUrl derived from proxy-ep and the apiKey (`src/auth/oauth/github-copilot.ts:495-507`) |
| xai | `https://auth.x.ai/oauth2/device/code` | `https://auth.x.ai/oauth2/token` | `b1a00492-073a-47ea-816f-4c329264a828` | device code | `openid profile email offline_access grok-cli:access api:access` | access/refresh/expires (refresh 5 minutes before expiry) |
| kimi-coding | `https://auth.kimi.com` (overridable) device flow | Same host | `17e5f671-d194-4dfb-9706-5516cb48c098` | device code, 15-minute timeout | — | access/refresh/expires |
| radius | Authorization endpoint discovered from the gateway | Gateway discovery/token | `pi-gateway` | Browser callback `http://127.0.0.1:1456/oauth/callback` or device code | `gateway offline_access` | access/refresh/expires; `toAuth` returns only `apiKey=access`, and gatewayConfig is stored as an extension field on the credential for the provider to read (`src/auth/oauth/radius.ts:305-320`, `src/providers/radius-config.ts:22-24`) |
| openrouter | `https://openrouter.ai/auth` (PKCE) | `https://openrouter.ai/api/v1/auth/keys` | PKCE dynamic | Local one-shot loopback callback; the redirect URL/raw code can also be pasted manually | — | The exchanged permanent API key is stored as access, refresh is an empty string, expires=MAX_SAFE_INTEGER |
| meta | `https://auth.meta.com/oidc/device/authorization/` | `https://auth.meta.com/oidc/device/token/` (device grant) | `1031625952748946` | device code; then `POST https://api.meta.ai/muse-code/key` mints the key | — | refresh=identity token, access=the minted API key (about 24h) |

Detailed evidence: the header constants of each file in `src/auth/oauth/*.ts` (anthropic:14-26, openai-chatgpt:16-29, openai-codex:22-35, github-copilot:11-19, xai:8-14, kimi-coding:14-19, radius:18-27, openrouter:19-22, meta:20-26).
Shared device-code polling: `src/auth/oauth/device-code.ts:1-98`; PKCE: `src/auth/oauth/pkce.ts`; local callback server: `src/auth/oauth/callback-server.ts` (ignores stray requests, accepts only the first, with timeout/cancel semantics at `:52-183`).

## 9. Image and classifier types

### 9.1 Images

`ImagesContext = { input: (TextContent|ImageContent)[] }` (`src/types.ts:652-657`).
`AssistantImages = { api, provider, model, output: (TextContent|ImageContent)[], responseId?, usage?, stopReason: stop|error|aborted, errorMessage?, timestamp }` (`:661-671`).
Image model: `ImageModel = BaseModel + type:"image" + output: ("text"|"image")[]` (`:881-885`). The only registered image API is `openrouter-images` (`:76`); its protocol is in `src/api/openrouter-images.ts:42-118` (OpenAI chat completions + `modalities:["image"]`, returning `choices[0].message.images[].image_url` data URLs).

### 9.2 Classifier

Question union (`src/types.ts:673-691`):
- `{type:"choice", instructions, criteria: Map<String,String>}`
- `{type:"score", instructions, criteria: String[]}`
- `{type:"bool", instructions, criteria: {true:String,false:String}}`

`ClassifierContext = { state: JsonObject, images?: ImageContent[], questions: Map<String,ClassifierQuestion> }` (`:693-701`).
Answer union (`:703-722`): choice `{choice, probabilities: Map<String,Double>, confidence}`; score `{score, confidence}`; bool `{probability}` (TypeSafe calls it `noul` on the wire).
`ClassifierResult = { api, provider, model, answers: Map<String,ClassifierAnswer>, usage?, stopReason, errorMessage?, timestamp }` (`:724-734`).

## 10. Wire mapping tables (the Kotlin implementation follows them directly)

### 10.1 Endpoints

| API | Request | Evidence |
|---|---|---|
| openai-completions | Official `openai` SDK → `POST {baseUrl}/chat/completions` (`client.chat.completions.create`) | `src/api/openai-completions.ts:376-383,797-803` |
| openai-responses | `POST {baseUrl}/responses` (`client.responses.create`) | `src/api/openai-responses.ts:220-227,336-342` |
| azure-openai-responses | AzureOpenAI SDK, baseUrl normalized to `.../openai/v1`, the deployment name can override model, api-version defaults to `v1` | `src/api/azure-openai-config.ts:16-107`, `src/providers/azure.ts:40-55` |
| anthropic-messages | `client.beta.messages.create(..., stream:true)` → `POST {baseUrl}/v1/messages`; betas go through the `betas` request parameter | `src/api/anthropic-messages.ts:655-662,1219-1225` |
| google-generative-ai | `@google/genai` `client.models.generateContentStream` → `{baseUrl}/models/{id}:streamGenerateContent`; apiVersion is "" when baseUrl contains a version | `src/api/google-generative-ai.ts:100,348-368` |
| google-vertex | `@google/genai` vertexai mode, `v1`, project/location required, baseUrl template `https://{location}-aiplatform.googleapis.com` | `src/api/google-vertex.ts:364-418,454-473` |
| mistral-conversations | Native `POST {baseUrl}/v1/chat/completions`, `Accept: text/event-stream` | `src/api/mistral-conversations.ts:298-341,355-372` |
| bedrock-converse-stream | AWS SDK `ConverseStreamCommand` (SigV4 or bearer token; `AWS_BEDROCK_SKIP_AUTH=1` skips signing) | `src/api/bedrock-converse-stream.ts:128-365` |
| openai-codex-responses | `POST {baseUrl}/codex/responses`, or WebSocket (`ws(s)://.../codex/responses`) | `src/api/openai-codex-responses.ts:663-676` |
| pi-messages | `POST {baseUrl}/messages`, custom SSE (`data: {...}` events, `[DONE]` can be ignored), `?debug=1` | `src/api/pi-messages.ts:277-321,356-436` |
| openrouter-images | OpenAI chat completions non-streaming, `modalities` | `src/api/openrouter-images.ts:73-83,156-166` |
| typesafe-system-one | `POST {baseUrl}/systemone`, `{model, state, questions}` | `src/api/typesafe-system-one.ts:15-22` |
| openai-decisions | `POST {baseUrl}/decisions`, `{model, input, questions}` | `src/api/openai-decisions.ts:19-27,173-185` |
| cloudflare-workers-ai-system-one | Workers AI REST: `{baseUrl}/run` | `src/api/cloudflare-workers-ai-system-one.ts` (48 lines, REST classifier) |
| llama-cpp-classify | The llama.cpp server's classification endpoint | `src/api/llama-cpp-classify.ts` (461 lines) |

### 10.2 Message mapping (in-memory → wire)

| In-memory | openai-completions | anthropic-messages | openai-responses | google | mistral | bedrock |
|---|---|---|---|---|---|---|
| SystemMessage (first) | `role: developer` (when reasoning and supported) otherwise `system`, content is the rendered text | Top-level `system: [{type:"text",text,cache_control?}]` | `role: developer/system` + text input | `systemInstruction` | `role:"system"` | Top-level `system: [{text}]` |
| Later SystemMessage | Sent in place when mid-convo is supported, otherwise folded | Sent in place (when supported)/folded | In-place developer/system, or tool-change control items | Folded | Folded | Folded |
| UserMessage text | `{role:"user",content:"..."}` | `{role:"user",content:[{type:"text",text}]}` | `{role:"user",content:[{type:"input_text",text}]}` | `{role:"user",parts:[{text}]}` | `{role:"user",content}` | `user` message + text block |
| UserMessage images | `image_url: {url:data URL}` | `{type:"image",source:{type:"base64",media_type,data}}` | `{type:"input_image",detail:"auto",image_url:data URL}` | `{inlineData:{mimeType,data}}` | `imageUrl` | image block |
| AssistantMessage text | `content` string (not an array) | text block | `{type:"message",role:"assistant",content:[{type:"output_text",text}],id,phase?}` | `{role:"model",parts:[{text}]}` | assistant content | text block |
| thinking replay | Same model: a `reasoning_details` array or signature field (`reasoning_content`/`reasoning`/`reasoning_text`); dropped across models | Same model `{type:"thinking",thinking,signature}`; redacted → `{type:"redacted_thinking",data}` | Same model: deserialize `thinkingSignature` into a reasoning item; dropped across models | Same model `{thought:true,text,thoughtSignature}`; degrades to plain text across models | thinking chunk | reasoningContent block; encrypted block signature base64 |
| ToolCall | assistant `tool_calls:[{id,type:"function",function:{name,arguments:JSON string}}]` | `{type:"tool_use",id,name,input}` | `{type:"function_call",id?,call_id,name,arguments,namespace?}` | `{functionCall:{name,args,id?},thoughtSignature?}` | `toolCalls:[{id,type,function:{name,arguments:string},index}]` | `{toolUse:{toolUseId,name,input}}` |
| ToolResultMessage | `{role:"tool",tool_call_id,content:text}`, consecutive results merged; images become subsequent user image messages when supported | `{role:"user",content:[{type:"tool_result",tool_use_id,content,is_error}]}`, consecutive merged | `{type:"function_call_output",call_id,output}` | `{functionResponse:{name,response:{output\|error},parts?}}` | `{role:"tool",toolCallId,name,content}` | `{toolResult:{toolUseId,content:[...]}}` |

Evidence: openai `src/api/openai-completions.ts:1199-1490`; anthropic `:1339-1558`; responses `src/api/openai-responses-shared.ts:191-432`; google `src/api/google-shared.ts:193-345`; mistral `src/api/mistral-conversations.ts:808-923`; bedrock `src/api/bedrock-converse-stream.ts:988-1159`.

### 10.3 usage mapping

| API | Wire fields → in-memory |
|---|---|
| openai-completions | `prompt_tokens/details.cached_tokens→cacheRead`, `prompt_tokens_details.cache_write_tokens→cacheWrite`, `completion_tokens→output`, `completion_tokens_details.reasoning_tokens→reasoning`; `input = max(0,prompt-cacheRead-cacheWrite)`; total = sum of the four (`src/api/openai-completions.ts:1529-1570`) |
| anthropic-messages | `input_tokens/output_tokens/cache_read_input_tokens/cache_creation_input_tokens/cache_creation.ephemeral_1h_input_tokens/output_tokens_details.thinking_tokens`; total computed locally (`src/api/anthropic-messages.ts:711-721,861-890`) |
| openai-responses | `input_tokens - input_tokens_details.cached_tokens - cache_write_tokens` is input; `output_tokens`; `total_tokens` (`src/api/openai-responses-shared.ts:630-661`) |
| google | `promptTokenCount-cachedContentTokenCount→input`, `candidatesTokenCount+thoughtsTokenCount→output`, `cachedContentTokenCount→cacheRead`, `thoughtsTokenCount→reasoning`, `totalTokenCount` (`src/api/google-generative-ai.ts:232-251`) |
| mistral | `prompt_tokens-cached→input`, `completion_tokens→output`, `cached→cacheRead`, `total_tokens` (`src/api/mistral-conversations.ts:616-628`) |
| bedrock | `inputTokens/outputTokens/cacheReadInputTokens/cacheWriteInputTokens/cacheDetails[ttl=1h]→cacheWrite1h`; total is input+output when missing (`src/api/bedrock-converse-stream.ts:717-734`) |
| Images | OpenRouter chat usage uses the same fields as OpenAI (`src/api/openrouter-images.ts:169-199`) |
| classifier | `{input_tokens,output_tokens}` → Usage (`src/api/classifier-shared.ts:117-131`) |

### 10.4 stop reason mapping

| API | Mapping |
|---|---|
| openai-completions | `stop/end→stop`, `length→length`, `function_call/tool_calls→toolUse`, `content_filter/network_error/unknown→error`; null→stop (`src/api/openai-completions.ts:1572-1596`) |
| anthropic | `end_turn→stop`, `max_tokens→length`, `tool_use→toolUse`, `refusal→error`, `pause_turn/stop_sequence→stop`, `sensitive→error`; unknown throws (`src/api/anthropic-messages.ts:1664-1690`) |
| responses | status `completed→stop` (toolUse if there are toolCalls), `incomplete`+`max_output_tokens→length`, other `incomplete→error`, `failed/cancelled→error`, `in_progress/queued→stop`; decided by `mapStopReason(status, incompleteReason)` (`src/api/openai-responses-shared.ts:655-675,857-887`) |
| google | `STOP→stop`, `MAX_TOKENS→length`, safety/recall/tool errors etc.→error; unknown throws (`src/api/google-shared.ts:443-484`) |
| mistral | `stop→stop`, `length/model_length→length`, `tool_calls→toolUse`, `error` and unknown values→error (the error's message is `Provider stopped with: error (server error)`, used for retry decisions) (`src/api/mistral-conversations.ts:936-952`) |
| bedrock | `end_turn/stop_sequence→stop`, `max_tokens/model_context_window_exceeded→length`, `tool_use→toolUse`; everything else (including guardrail, etc.)→error with `Provider stopped with: <reason>` (`src/api/bedrock-converse-stream.ts:1196-1213`) |

### 10.5 Tool call id normalization

Each API constrains the id charset/length, and replay must handle it (`src/api/transform-messages.ts` is the main entry):
- openai-completions: composite ids containing `|` are split into `callId_itemId`, invalid characters are replaced with `_`, ids over 40 characters are shortened with a hash; provider=`openai` additionally truncates to 40 (`src/api/openai-completions.ts:1208-1232`).
- anthropic: only `^[a-zA-Z0-9_-]+$` and ≤64 allowed (`src/api/anthropic-messages.ts:1334-1338`).
- responses: `callId|itemId`, each part cleaned and ≤64, the item id must start with `fc_`, and cross-model use is `fc_<shortHash>` (`src/api/openai-responses-shared.ts:200-223`).
- google: Gemini 3+/claude/gpt-oss need an explicit id; replace non-`[a-zA-Z0-9_-]` and truncate to 64 (`src/api/google-shared.ts:167-200`).
- mistral: ids must be 9 alphanumeric characters, derived with a short hash and guaranteed bidirectionally unique (`src/api/mistral-conversations.ts:238-268`).

### 10.6 Orphan tool call / error message replay rules

`transformMessages` (`src/api/transform-messages.ts:91-256`):
- An assistant message with `stopReason=error/aborted` is dropped entirely.
- When a tool call has no result, insert a synthesized `toolResult`: `content=[{type:"text",text:"No result provided"}]`, `isError=true`.
- When system messages occur between tool calls, the system message is buffered and emitted after the tool results (avoiding Anthropic rejecting the `tool_use→system→tool_result` order).
- Models that do not support images: user images are replaced with the text `(image omitted: model does not support images)`, and tool result images with `(tool image omitted: model does not support images)`.
- When an error tool result carries images (rejected by Anthropic/Bedrock Claude), split it into a text result + subsequent images (`src/api/transform-messages.ts:16-60`).

## 11. Error and diagnostic types

- `ModelsErrorCode = "model_source" | "model_validation" | "provider" | "stream" | "auth" | "oauth"` (`src/utils/models-error.ts:4`). The `ModelsError` message includes the cause text (`:15-21`).
- `AssistantMessageDiagnostic = { type: string, timestamp: Long, error?: {name?,message,stack?,code?}, details?: JsonObject }` (`src/utils/diagnostics.ts:2-7`).
- Error normalization: `normalizeProviderError` extracts status/body/message from SDK error objects; `formatProviderError` produces `"<status>: <body>"` or `"<prefix> (<status>): <body>"` (`src/utils/error-body.ts:38-135`). Kotlin's error text must match pi so the upper layer can recognize retries/overflow.
- Context overflow detection: `isContextOverflow(message, contextWindow?)` (`src/utils/overflow.ts:142-176`), covering 3 cases: error text matching 30+ regex groups, silent overflow (`input+cacheRead > contextWindow`), and length+zero-output filling the window. Overflow regex table `:38-67`, non-overflow exclusion table `:80-84`.
- Recoverable length: `isRecoverableLength` (`:184-186`).
- Retry decision: `isRetryableAssistantError` (`src/utils/retry.ts:266-270`); non-retryable quota/billing/subscription-limit patterns are at `:7-31`.

---

## 12. Test inventory to port (packages/ai/test, 177 files, by priority)

### 12.1 MUST (pure logic, no network, used directly as Kotlin acceptance)

| Test file | Purpose |
|---|---|
| `event-stream.test.ts` | Event stream queue order, end semantics, durationMs (`test/event-stream.test.ts:7-156`) |
| `json-parse.test.ts` | JSON repair/partial parsing, invalid argument handling (`:6-51`) |
| `message-types.test.ts` | Message/content type constraints and serialization (`:14-49`) |
| `model-types.test.ts` | Three model types, isModelType, providers without getAllModels (`:54-105`) |
| `models-entry.test.ts` / `models-runtime.test.ts` | Models registration, queries, api dispatch |
| `providers.test.ts` | 42 provider factories/catalog/auth consistency |
| `model-data-validation.test.ts` | Catalog hydrate/validation/key grouping (`:87-199`) |
| `model-catalog-types.test.ts` | Catalog type inference and copilot routing |
| `model-cost-tiers.test.ts` | Cost tiers, missing-rate inheritance, timezone override skipping (`:15-97`) |
| `tokens.test.ts` / `total-tokens.test.ts` | usage/totalTokens semantics (the latter needs real keys; for reference) |
| `context-estimate.test.ts` | 3.5 chars/token, expired usage is invalid (`:46-79`) |
| `overflow.test.ts` | Overflow regexes per provider (`:57-101`) |
| `retry.test.ts` / `provider-retry.test.ts` | Retry classification, backoff, retry-after, abort (`:16-73`) |
| `abort.test.ts` | Terminal messages for mid-stream/immediate abort |
| `pre-generation-error.test.ts` | Synchronous throw when auth is missing (`:32`) |
| `transform-messages-cross-model-thinking.test.ts` | Cross-model thinking drop (`:40-71`) |
| `tool-call-id-normalization.test.ts` | Tool id normalization |
| `sampling-options.test.ts` / `reasoning-options.test.ts` / `max-thinking.test.ts` / `supports-xhigh.test.ts` | Sampling/reasoning mapping |
| `compat-env.test.ts` / `compat-schema.test.ts` | compat defaults and environment |
| `validation.test.ts` | Tool argument validation error structure |
| `faux-provider.test.ts` | Fake provider scripted responses (needed for the Kotlin test double) |
| `context-overflow.test.ts` | Real-provider overflow e2e (optional, needs keys) |

### 12.2 SHOULD (ported per API, mostly with a mock server instead of real network)

`openai-completions-retry.test.ts`, `openai-completions-prompt-cache.test.ts`, `openai-completions-tool-choice.test.ts`, `openai-completions-reasoning-details.test.ts`, `openai-completions-thinking-as-text.test.ts`, `openai-completions-response-model.test.ts`, `openai-responses-compat.test.ts`, `openai-responses-terminal-event.test.ts`, `openai-responses-partial-json-cleanup.test.ts`, `openai-responses-message-id.test.ts`, `anthropic-sse-parsing.test.ts`, `anthropic-thinking-disable.test.ts`, `anthropic-temperature-compat.test.ts`, `anthropic-strict-tool-schema.test.ts`, `anthropic-tool-name-normalization.test.ts`, `google-shared-convert-tools.test.ts`, `google-thinking-level-map.test.ts`, `google-thinking-signature.test.ts`, `google-shared-retry.test.ts`, `mistral-tool-schema.test.ts`, `mistral-raw-stop-reason.test.ts`, `mistral-http-transport.test.ts`, `bedrock-convert-messages.test.ts`, `bedrock-thinking-payload.test.ts`, `bedrock-error-metadata.test.ts`, `pi-messages.test.ts`, `openrouter-images.test.ts`, `images.test.ts`, `images-models.test.ts`, `classifier-models.test.ts`, `openai-decisions.test.ts`, `typesafe-system-one.test.ts`, `azure-openai-base-url.test.ts`, `azure-openai-completions.test.ts`.

### 12.3 OAuth / login (DEFER or on demand)

`oauth-auth.test.ts`, `oauth-device-code.test.ts`, `oauth-callback-server.test.ts`, `anthropic-oauth.test.ts`, `openai-codex-oauth.test.ts`, `openai-chatgpt-oauth.test.ts`, `github-copilot-oauth.test.ts`, `xai-oauth.test.ts`, `kimi-coding-oauth.test.ts`, `radius-oauth.test.ts`, `meta-oauth.test.ts`, `openrouter-oauth.test.ts`, `env-api-keys.test.ts`.
Of these, `oauth-device-code`, `oauth-callback-server`, and `env-api-keys` are good candidates to port first (pure state machines/callbacks/env variables).

### 12.4 Can be skipped

Real-key smoke/e2e tests (`stream.test.ts`, the various `*-e2e.test.ts`, `anthropic-opus-4-8-smoke.test.ts`, `codex-websocket-cached-probe.ts`, `scratch.ts`, `test/utils` helper files) and heavy-environment tests such as Anthropic/Bedrock federation and telemetry.

---

## 13. Kotlin modeling checklist

1. Use sealed + discriminator keys `role`/`type` for `Message` / `AssistantMessageEvent` / `Content`; unknown variants should not occur before `done`, but deserializing old sessions may skip them.
2. Use `JsonObject` for `arguments`, `details`, `state`, and `Tool.parameters`; if `Tool.parameters` needs strict conversion, deep-copy first and then rewrite.
3. `Usage.cost` is Double; `reasoning`/`cacheWrite1h` are nullable; `totalTokens` must be assigned explicitly, not derived.
4. Timestamps are uniformly Long ms; `durationMs` is written only on terminal messages.
5. `Model.compat` uses a per-api sealed structure or a merged "all fields nullable" data class; unknown compat fields are ignored.
6. Catalog loading: `ignoreUnknownKeys=true`; a missing `type` is treated as chat; `type:id` is unique; the same id may coexist under different types.
7. Keep camelCase and omitted fields when writing sessions back (do not write null); `textSignature`/`thinkingSignature` are opaque strings, do not decode them.
8. The "synchronous throw" semantics of event streams are incompatible with Kotlin coroutine Flow and must be designed explicitly (see 4.7).
9. When tool argument validation fails, keep the `argumentsError` text; the tool layer must return it to the model as an error result.
10. Land the catalog/tests first: use `models.all.json` for JSON parse round-trip tests, and a faux provider equivalent for event protocol tests.

