# pi-ai source analysis and Kotlin porting plan

Source baseline: `upstream pi checkout`, `packages/ai` version **1.3.0** (`packages/ai/package.json`, `src/utils/version.ts:2`). Read-only analysis; no source was modified and no npm install/build was run.
Line numbers: `file:line` is relative to `packages/ai` (for example `src/types.ts:62`), or relative to the repository root (for example `nix/package.nix:26`).
Companion doc: `docs/analysis/pi-ai-types.md` (type-by-type/field-by-field/event protocol/test inventory); this document focuses on package structure, provider/API protocols, auth, model data, and the Kotlin implementation plan.

---

## 0. Summary of conclusions

1. Size and structure: `src` totals **27,709 lines** (201 `.ts` files); `src/api` has 43 files/13,486 lines, `src/providers` 95 files/3,827 lines, `src/auth` 18 files/3,832 lines (OAuth 13 files/3,189 lines), `src/utils` 26 files/2,699 lines, and the `src` root 18 files/3,820 lines; tests are 177 files/44,744 lines. The actual protocol implementations are concentrated in 10 chat APIs, 1 image API, and 4 classifier APIs.
2. Provider surface: 42 built-in providers (`MODELS`/`IMAGE_MODELS`/`CLASSIFIER_MODELS` in `src/models.generated.ts:47,135,223`, matching `KnownProvider` in `src/types.ts:88-130`), with **1,705 total model entries = 1,608 chat + 59 image + 38 classifier** (measured from the pinned 1.3.0 catalog; see section 8). Many providers are just the same API implementation with a different baseUrl/env key, so the Kotlin side should estimate protocol cost by API count (10), not provider count (42).
3. Protocol cost is very uneven: openai-completions (1,744 lines), openai-codex-responses (1,724), anthropic-messages (1,690), bedrock-converse-stream (1,428), and mistral-conversations (952) are the heavy hitters; openai-responses consists of 459 + 887 shared lines; the two Google APIs share a 517-line conversion layer; pi-messages is 448 lines; azure is 261 + 107 lines of config.
4. Auth and model data are the hidden main projects: 9 OAuth flows (3,189 lines in the OAuth directory), credential locking/refresh semantics (`src/auth/resolve.ts`), and the model catalog generation chain (`scripts/generate-models.ts` 3,598 lines + `scripts/model-data.ts` 336 lines) are all substantial. Model data is gitignored in the source checkout (`packages/ai/src/providers/data/`) and must be obtained from the pi.dev pinned catalog or an installed npm build; see section 8 for the plan and measurements.
5. Suggested Kotlin MUST scope: type model + event streams + model registration/auth resolution + 3 primary APIs (openai-completions, anthropic-messages, openai-responses) + SSE/partial-JSON/retry/abort/usage-cost; estimated at 8,000-11,000 lines of Kotlin. Full parity (42 providers, 9 OAuth, image/classifier, bedrock/codex/mistral) is about 15,000-19,000 lines.

---

## 1. Package responsibilities and boundaries

pi-ai is the unified layer for "one LLM request" (README opening, `packages/ai/README.md:3`): provider catalog, auth resolution, request sending and streaming event normalization, token/cost accounting, and three model operations (chat/image/classifier). It does not include the agent loop, tool execution, session persistence, or UI — those live in `packages/agent` and `packages/coding-agent`.

Three layers of upstream usage:
- New API: `createModels()`/`builtinModels()` → `Models.getModel()` → `Models.stream()/complete()` (`src/models.ts:258-367,1028-1030`, `src/providers/all.ts:184-190`).
- Legacy global API: `stream/complete/streamSimple/completeSimple` from `@earendil-works/pi-ai/compat`, which uses `builtinModels()` and env-key fallback internally to keep legacy callers working (`src/compat.ts:217-302`).
- Using API modules directly: `stream/streamSimple` from `@earendil-works/pi-ai/api/<name>` (`./api/*` in `package.json` exports).

Lower bound: API implementations only accept a normalized `TranscriptContext` (`src/types.ts:791-794`); the system prompt and tools are in the leading system message, and `Models` performs `normalizeContext()` (`src/utils/transcript.ts:30-34`). Upper bound: `remote-catalog-provider.ts` in `src/coding-agent` overlays the pi.dev catalog on top of Models (`packages/coding-agent/src/core/remote-catalog-provider.ts:14,81`), showing that pi-ai itself does not handle remote catalog refresh.

---

## 2. Size, entry points, and dependencies

### 2.1 Directory sizes (measured with `wc -l`)

| Directory | Files | Lines | Main contents |
|---|---|---|---|
| `src` root | 18 | 3,820 | `types.ts` 904, `models.ts` 1,299, `compat.ts` 302, `models.generated.ts` 309, `model-catalog.ts` 81, `models-store.ts` 46, `cli.ts`, `index.ts`, `oauth.ts`, etc. |
| `src/api` | 43 | 13,486 | 10 chat APIs + image/classifier + 15 `.lazy.ts` wrappers |
| `src/providers` | 95 | 3,827 | 42 provider factories + 42 generated `.models.ts` shards + `all.ts`, `faux.ts`, cloudflare/radius/openrouter helpers |
| `src/auth` | 18 | 3,832 | Credentials/resolution/login + 13 OAuth files (3,189 lines) |
| `src/utils` | 26 | 2,699 | Event stream, transcript, retry, JSON, overflow, identity, proxy, etc. |
| `src/compat` | 1 | 45 | Extended OAuth types |
| `test` | 177 | 44,744 | Unit + real-provider e2e |

### 2.2 Entry points and exports (`packages/ai/package.json` exports)

The main entry `dist/index.js` exports only the "core, side-effect-free" parts: types, `Models`, auth base types, event-stream, retry/overflow/json-parse/transcript/validation, `PI_AI_VERSION`, and `providers/faux` (`src/index.ts:4-49`). Sub-entries loaded on demand:
`./models`, `./compat`, `./providers/*` (provider factories and `all.ts`), `./api/*` (API implementations), `./utils/*`, `./oauth` (types only), `./bedrock-provider`, `./bun-oauth`.
`index.ts` does **not** export the generated catalog/API registry/OAuth implementations, for browser/bundle-size reasons; Kotlin need not copy this trimming, but can borrow the boundary of "core types independent, provider and API implementations separate".

### 2.3 Dependencies (`packages/ai/package.json` dependencies)

| Dependency | Version | Purpose | Kotlin replacement |
|---|---|---|---|
| `openai` | 7.19.0 | OpenAI Chat Completions / Responses / Azure client | OkHttp + hand-written JSON (or openai-kotlin, but hand-writing is recommended to control SSE/retries) |
| `@anthropic-ai/sdk` | 0.129.0 | Anthropic Messages + beta headers | OkHttp/CIO + hand-written SSE |
| `@google/genai` | 2.21.0 | Gemini / Vertex client | REST + hand-written SSE (`streamGenerateContent`) |
| `@aws-sdk/client-bedrock-runtime` | 3.1127.0 | Bedrock ConverseStream + SigV4 | AWS SDK for Kotlin (bedrockruntime) or hand-written SigV4 |
| `partial-json` | 0.1.7 | Streaming tool argument parsing | Port the algorithm (see types doc §5.4) |
| `typebox` | 1.3.27 | Tool argument schema + validation + generation | kotlinx.serialization JsonObject + a JSON Schema validation library |
| `http-proxy-agent` / `https-proxy-agent` | 9.1.0 | Proxy | OkHttp `Proxy`/`ProxySelector` |
| `@earendil-works/pi-telemetry` | 1.3.0 | telemetry schema | Can be DEFER |

Node requires `>=22.19.0` (`package.json` engines); the source makes heavy use of `fetch`, `AbortSignal`, `ReadableStream`, `node:http` (OAuth callbacks), `node:crypto` (PKCE/UUID/hash), and `process.env`.

---

## 3. Public API overview

### 3.1 Models (runtime collection)

Interface definition at `src/models.ts:258-367`, implementation `ModelsImpl` (`:400-1026`), factory `createModels()` (`:1028-1030`), and `MutableModels` adding `setProvider/deleteProvider/clearProviders` (`:369-374`).

| Category | Methods (summary) | Key semantics |
|---|---|---|
| Provider reads | `getProviders/getProvider` | Registration order |
| Model reads (sync) | `getModels(provider?)`, `getModel`, `getModelsOfType(type, provider?)`, `getModelOfType`, `getAllModels` | Reads the "last known" list only; a provider whose `getModels()` throws is treated as empty (`:442-484`) |
| Dynamic refresh | `refresh({allowNetwork?,providers?,force?,signal?})` → `{aborted,errors}` | Refreshes providers with `refreshModels` concurrently; a generation counter + AbortController prevent stale results from overwriting newer ones (`:498-625`) |
| Auth | `checkAuth`, `getAvailable/getAvailableOfType/getAllAvailable`, `getAuth(providerId\|model)`, `login/logout` | getAuth refreshes expired OAuth and returns `{auth,env,source}`; error code `ModelsError` (`:664-841`) |
| chat | `stream`, `complete`, `streamSimple`, `completeSimple` | `normalizeContext`, then `applyAuth`, then hand to the provider (`:901-948`) |
| deferred | `streamDeferred`, `fetchDeferred`, `cancelDeferred` | Optional provider capability (`:950-988`) |
| image/classifier | `generateImages`, `classify` | Never reject; errors also return a result with `stopReason:"error"` (`:990-1025`) |

`applyAuth` merge order (`src/models.ts:856-899`): getAuth result → explicit options.apiKey override → `auth.baseUrl` overrides model.baseUrl → headers merge (opencode session header, auth header, request header) → `transformHeaders` last → env merge → identity merge → remaining options passed through as-is. Kotlin must merge in the same order, or multi-provider header behavior will diverge.

### 3.2 Provider (runtime unit)

`Provider` interface `src/models.ts:158-247`: `id/name/baseUrl?/headers?/auth/getModels()/getAllModels?()/refreshModels?(ctx)/filterModels?/filterAllModels?/stream/streamSimple/fetchDeferred?/cancelDeferred?/generateImages?/classify?`.
`createProvider()` (`:1077-1218`):
- `api` can be a single `ProviderStreams` or a map dispatched by `model.api`; a map without a matching implementation returns an error stream (`:1108-1119`).
- `models` is the static baseline, and `fetchModels` provides a dynamic overlay; same-name same-type entries override (`:1092-1105`).
- `refreshModels` internally implements "restore persisted first, then network refresh, persist transactionally via `context.publish()` and update memory" (`:1129-1156`).
- `images`/`classifiers` are also maps dispatched by `model.api`.
- Empty implementations are rejected (`:1088-1090`).

`ModelsStore`: persists `{models, lastModified?, checkedAt?, etag?}` per providerId (`src/models-store.ts:3-26`), defaulting to `InMemoryModelsStore` (`:28-46`). `Models.refresh` uses a generation + publication chain to guarantee transaction order (`src/models.ts:516-563`).

### 3.3 Streaming entry points and events

API modules uniformly export `stream(model, context, options?)` and `streamSimple(model, context, options?)`, returning an `AssistantMessageEventStream` (`src/types.ts:317-330`). `streamSimple` uses `buildBaseOptions` to turn simple options into request options: clamps `maxTokens`, resolves sampling, and maps reasoning to each API's specific fields (`src/api/simple-options.ts:36-66`).
`AssistantMessageEventStream` (`src/utils/event-stream.ts:97-130`) is an AsyncIterable + `result()` Promise, filling `durationMs` on the final message at terminal events. `lazyStream` lets `stream()` return synchronously while doing auth/module loading asynchronously, turning setup failures into `error` events (`src/api/lazy.ts:60-86`).
Event protocol details are in section 4 of `docs/analysis/pi-ai-types.md`.

### 3.4 Other public capabilities

- Images: `generateImages(model, context, options)` goes through the global images API registry (`src/images.ts:19-26`, `src/images-api-registry.ts:38-53`); `Model.generateImages()` dispatches by provider. Only `openrouter-images` is implemented.
- Classifiers: `classify()`, with 4 APIs (TypeSafe System One, Cloudflare Workers AI System One, llama.cpp classify, OpenAI Decisions).
- OAuth project: `Models.login(providerId, type, interaction, options)` (`src/models.ts:775-830`); credential persistence and OAuth refresh are handled by Models/`CredentialStore`; `src/oauth.ts` is only a types-compatibility entry.
- CLI: `pi-ai login [provider]` / `pi-ai list` (`src/cli.ts:85-99`), writing OAuth credentials to `auth.json` in the current directory (`:20-38`).
- Faux provider: a scripted provider for tests (`src/providers/faux.ts`, 727 lines) supporting queued responses, deferred, and token-rate simulation; an important reference for the Kotlin test double.
- Telemetry: `AI_TELEMETRY_SCHEMA` (`src/telemetry.ts:63-120`), span names `pi.ai.request`/`pi.ai.poll`; optional.
- Session resource cleanup: `registerSessionResourceCleanup`/`cleanupSessionResources` (`src/session-resources.ts`), used by codex WebSocket for session-scoped cleanup (`src/api/openai-codex-responses.ts:956-975`).

## 4. Architecture layers and the data flow of one request

Layers (bottom-up):
1. `types.ts` / `providers/model-schema.ts` / `providers/compat-schema.ts`: pure types and schemas.
2. `api/*.ts`: each wire protocol implementation, depending only on types, utils, and the SDK; not on `models.ts` runtime (except `calculateCost`/`clampThinkingLevel`).
3. `providers/*.ts`: provider factories combining baseUrl/auth/models/api implementations.
4. `models.ts`: registry, auth resolution, dynamic refresh, request forwarding.
5. `compat.ts` / `index.ts` / `providers/all.ts`: compatibility layer and aggregate entries.

One `Models.stream(model, context, options)` (`src/models.ts:901-919`):
1. `normalizeContext(context)`: folds `systemPrompt`+`tools` into the leading `SystemMessage` (`src/utils/transcript.ts:10-34`).
2. `requireChatProvider(model)`: looks up the registry; a non-chat model immediately throws `ModelsError("provider")`.
3. `applyAuth()`: `getAuth` → resolve api key/OAuth (lock and refresh if OAuth has expired) → override baseUrl/headers/env/identity (`src/models.ts:856-899`).
4. `provider.stream(requestModel, transcript, requestOptions)` (the `Model` may be a copy with auth.baseUrl).
5. API implementation: `resolveTranscript` (fold according to whether mid-convo system messages are supported) → convert messages/tools → build the request body → `onPayload` → HTTP (SDK or fetch) → `onResponse` → `start` event → normalize each SSE event → `done`/`error`.
6. `Models.complete()` = `stream().result()`; `lazyStream` encodes setup exceptions as error events.

Cross-cutting responsibilities:
- Retries: the **first HTTP request** of every API is wrapped by `retryProviderRequest` (`src/utils/provider-retry.ts:107-128`); the SDK's own retries are fixed at 0 (e.g. `src/api/openai-completions.ts:371-383`) so that backoff can be interrupted by AbortSignal.
- Errors: SDK errors go through `normalizeProviderError`/`formatProviderError` to produce text (`src/utils/error-body.ts:38-135`), and the upper layer recognizes retries/overflow from text patterns (`src/utils/retry.ts:7-121`, `src/utils/overflow.ts:38-84`). Kotlin must **produce the same error text**, otherwise the upper layer's retry/overflow/compaction logic stops working.
- cost: `calculateCost(model, usage)` when the stream ends or usage updates (`src/models.ts:1236-1256`).
- duration: terminal messages of the event stream get `durationMs` (`src/utils/event-stream.ts:126-129`).

---

## 5. Complete provider list

The count columns come from measured counts in the 1.3.0 pinned catalog (section 8 plan B, `/tmp/models.all.json`); `chat/image/classifier` are the provider's catalog entry counts. baseUrl is the actual endpoint appearing in the catalog (when there are several, the first value/description is listed).

| provider | chat | image | classifier | API (as it appears in the catalog) | Auth | baseUrl / endpoint |
|---|---|---|---|---|---|---|
| openrouter | 394 | 59 | 23 | anthropic-messages, openai-completions, openrouter-images, typesafe-system-one | api key `OPENROUTER_API_KEY` + OAuth | `https://openrouter.ai/api` (anthropic)/`/api/v1` |
| vercel-ai-gateway | 297 | 0 | 8 | anthropic-messages, typesafe-system-one | `AI_GATEWAY_API_KEY` | `https://ai-gateway.vercel.sh`, `/typesafe/v1` |
| amazon-bedrock | 193 | 0 | 0 | bedrock-converse-stream | Environment AWS credential chain (profile/access key/bearer/ECS/IRSA), no OAuth | Regional endpoint per model, e.g. `https://bedrock-runtime.us-east-1.amazonaws.com` |
| opencode | 83 | 0 | 2 | anthropic-messages, google-generative-ai, openai-completions, openai-responses, typesafe-system-one | `OPENCODE_API_KEY` | `https://opencode.ai/inference` (+`/v1`) |
| huggingface | 78 | 0 | 0 | openai-completions | `HF_TOKEN` | `https://router.huggingface.co/v1` |
| cloudflare-ai-gateway | 57 | 0 | 0 | anthropic-messages, openai-responses, openai-completions | `CLOUDFLARE_API_KEY`+`CLOUDFLARE_ACCOUNT_ID`+`CLOUDFLARE_GATEWAY_ID` | `https://gateway.ai.cloudflare.com/v1/{account}/{gateway}/{anthropic\|openai\|compat}` |
| azure | 45 | 0 | 0 | openai-completions, azure-openai-responses | `AZURE_OPENAI_API_KEY` + endpoint env/parameter | No catalog baseUrl: resolved from `AZURE_OPENAI_BASE_URL` / `..._RESOURCE_NAME`, path `/openai/v1` |
| openai | 44 | 0 | 1 | openai-responses, openai-decisions | `OPENAI_API_KEY` + ChatGPT OAuth (subscription) | `https://api.openai.com/v1` |
| mistral | 40 | 0 | 0 | mistral-conversations | `MISTRAL_API_KEY` | `https://api.mistral.ai` |
| github-copilot | 36 | 0 | 0 | anthropic-messages, openai-completions, openai-responses | `COPILOT_GITHUB_TOKEN` or OAuth (subscription) | `https://api.individual.githubcopilot.com`; with OAuth, derived dynamically from the token's proxy-ep |
| opencode-go | 31 | 0 | 0 | anthropic-messages, google-generative-ai, openai-completions, openai-responses | `OPENCODE_API_KEY` | `https://opencode.ai/inference/go` (+`/v1`) |
| radius | 29 | 0 | 0 | pi-messages | `RADIUS_API_KEY` + OAuth; catalog refreshed dynamically | `https://radius.pi.dev/v1` (gateway `/v1/config` delivers it) |
| google | 23 | 0 | 0 | google-generative-ai | `GEMINI_API_KEY` | `https://generativelanguage.googleapis.com/v1beta` |
| baseten | 22 | 0 | 0 | openai-completions | `BASETEN_API_KEY` | `https://inference.baseten.co/v1` |
| fireworks | 22 | 0 | 0 | anthropic-messages, openai-completions | `FIREWORKS_API_KEY` | `https://api.fireworks.ai/inference` (+`/v1`) |
| cloudflare-workers-ai | 18 | 0 | 3 | openai-completions, cloudflare-workers-ai-system-one | `CLOUDFLARE_API_KEY`+account | `https://api.cloudflare.com/client/v4/accounts/{id}/ai` (+`/v1`) |
| qwen-token-plan | 20 | 0 | 0 | openai-completions | `QWEN_TOKEN_PLAN_API_KEY` | `https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1` |
| qwen-token-plan-cn | 20 | 0 | 0 | openai-completions | `QWEN_TOKEN_PLAN_CN_API_KEY` | `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` |
| nvidia | 19 | 0 | 0 | openai-completions | `NVIDIA_API_KEY` | `https://integrate.api.nvidia.com/v1` |
| together | 17 | 0 | 0 | openai-completions | `TOGETHER_API_KEY` | `https://api.together.ai/v1` |
| anthropic | 17 | 0 | 0 | anthropic-messages | `ANTHROPIC_API_KEY`/`ANTHROPIC_OAUTH_TOKEN`/`ANTHROPIC_AUTH_TOKEN` (Bearer) + OAuth + workload identity federation | `https://api.anthropic.com` |
| google-vertex | 14 | 0 | 0 | google-vertex | `GOOGLE_CLOUD_API_KEY` or ADC (`GOOGLE_APPLICATION_CREDENTIALS`) + project/location | `https://{location}-aiplatform.googleapis.com` |
| qwen-token-plan-individual | 9 | 0 | 0 | openai-completions | `QWEN_TOKEN_PLAN_API_KEY` | Same as qwen-token-plan |
| openai-codex | 9 | 0 | 0 | openai-codex-responses | OAuth only (ChatGPT Plus/Pro) | `https://chatgpt.com/backend-api` (`/codex/responses`) |
| zai | 7 | 0 | 0 | openai-completions | `ZAI_API_KEY` | `https://api.z.ai/api/coding/paas/v4` |
| groq | 7 | 0 | 0 | openai-completions | `GROQ_API_KEY` | `https://api.groq.com/openai/v1` |
| meta | 6 | 0 | 0 | openai-responses | `META_API_KEY` + OAuth (Muse subscription, device flow + key mint) | `https://api.meta.ai/v1` |
| xiaomi | 6 | 0 | 0 | openai-completions | `XIAOMI_API_KEY` | `https://api.xiaomimimo.com/v1` |
| kimi-coding | 4 | 0 | 0 | openai-completions | `KIMI_API_KEY` + OAuth (subscription) | `https://api.kimi.com/coding/v1` |
| moonshotai | 4 | 0 | 0 | openai-completions | `MOONSHOT_API_KEY` | `https://api.moonshot.ai/v1` |
| moonshotai-cn | 4 | 0 | 0 | openai-completions | `MOONSHOT_API_KEY` | `https://api.moonshot.cn/v1` |
| xai | 4 | 0 | 0 | openai-responses | `XAI_API_KEY` + OAuth (subscription) | `https://api.x.ai/v1` |
| xiaomi-token-plan-cn | 4 | 0 | 0 | openai-completions | `XIAOMI_TOKEN_PLAN_CN_API_KEY` | `https://token-plan-cn.xiaomimimo.com/v1` |
| xiaomi-token-plan-ams | 4 | 0 | 0 | openai-completions | `XIAOMI_TOKEN_PLAN_AMS_API_KEY` | `https://token-plan-ams.xiaomimimo.com/v1` |
| xiaomi-token-plan-sgp | 4 | 0 | 0 | openai-completions | `XIAOMI_TOKEN_PLAN_SGP_API_KEY` | `https://token-plan-sgp.xiaomimimo.com/v1` |
| zai-coding-cn | 4 | 0 | 0 | openai-completions | `ZAI_CODING_CN_API_KEY` | `https://open.bigmodel.cn/api/coding/paas/v4` |
| ant-ling | 3 | 0 | 0 | openai-completions | `ANT_LING_API_KEY` | `https://api.ant-ling.com/v1` |
| minimax | 3 | 0 | 0 | anthropic-messages | `MINIMAX_API_KEY` | `https://api.minimax.io/anthropic` |
| minimax-cn | 3 | 0 | 0 | anthropic-messages | `MINIMAX_CN_API_KEY` | `https://api.minimaxi.com/anthropic` |
| cerebras | 2 | 0 | 0 | openai-completions | `CEREBRAS_API_KEY` | `https://api.cerebras.ai/v1` |
| deepseek | 2 | 0 | 0 | openai-completions | `DEEPSEEK_API_KEY` | `https://api.deepseek.com` |
| typesafe | 0 | 0 | 1 | typesafe-system-one | `TYPESAFE_API_KEY` | `https://api.typesafe.ai/v1/` |

Key observations:
- 28 providers have at least one chat model on `openai-completions` (21 of them use only that); they share one implementation + per-model `compat` overrides, which is the biggest reason for Kotlin to prioritize openai-completions.
- 7 providers have chat models across multiple APIs: openrouter, opencode, opencode-go, github-copilot, cloudflare-ai-gateway, fireworks, azure; `createProvider`'s api map is a required capability.
- 3 providers have dynamic catalogs/personalization: radius (`refreshModels` pulls the gateway `/v1/config`, `src/providers/radius.ts:35-84`), openrouter (`personalizeOpenRouterCatalog` calls `GET /models/user` to filter available models and reprices by account quota, `src/providers/openrouter.ts:28-70`), github-copilot (`filterModels` uses the credential's `availableModelIds`, `src/providers/github-copilot.ts:19-27`).
- 3 providers have placeholders in baseUrl, substituted before sending: cloudflare (`{CLOUDFLARE_ACCOUNT_ID}`/`{CLOUDFLARE_GATEWAY_ID}`, `src/providers/cloudflare-stream.ts:11-22`), google-vertex (`{location}`, `src/api/google-vertex.ts:420-426`), azure (no catalog baseUrl; resolved per request).
- Auth methods: 33 providers use only api key/env or cloud credentials; 9 have OAuth (anthropic, openai, openai-codex, github-copilot, xai, kimi-coding, meta, radius, openrouter), corresponding to the 9 flows under `src/auth/oauth/`; bedrock uses the AWS environment credential chain; vertex uses an API key or ADC.

Provider factory evidence: `src/providers/<id>.ts` is 20-100 lines each; the api key mapping table is `src/env-api-keys.ts:73-127`; OAuth wrappers are `src/auth/helpers.ts:38-81`.

## 6. Detailed API protocol implementations

Common conventions: all APIs return an `AssistantMessageEventStream`; the first event is `start`; errors are uniformly converted to `{type:"error", reason:"error"|"aborted", error}`; `calculateCost` runs after every usage update; if the stream ends while `stopReason==="pending"`, it throws "stream ended without ...".

### 6.1 openai-completions (`src/api/openai-completions.ts`, 1,744 lines)

- Client: the official `openai` SDK, `new OpenAI({apiKey, baseURL: model.baseUrl, defaultHeaders, fetch})` (`:760-804`). Headers: identity UA + model.headers + dynamic copilot headers + session affinity (`x-session-id` or `session_id`/`x-client-request-id`/`x-session-affinity`, `:770-795`).
- Request: `client.chat.completions.create(params, {maxRetries:0,...})` (`:376-383`) → `POST {baseUrl}/chat/completions`. `params` (`:828-1015`): `model, messages, stream:true, stream_options:{include_usage:true}, store:false (when compat supports it), prompt_cache_key (api.openai.com or long retention), prompt_cache_retention:"24h" (long + supported), max_tokens or max_completion_tokens, temperature, tools, tool_choice, samplingParams last-wins`. When tools is empty but history has tool calls, an empty array is sent (needed for Anthropic-via-proxy, `:866-869`).
- Thinking parameters branch on `compat.thinkingFormat` (`:886-983`): `openai`→`reasoning_effort`; `openrouter`→`reasoning:{effort}`; `deepseek`→`thinking:{type:enabled/disabled}`+`reasoning_effort`; `zai`→`thinking:{type,clear_thinking:false}`; `qwen`→`enable_thinking`; `qwen-chat-template`→`chat_template_kwargs`; `chat-template`→configurable kwargs; `baseten`→`chat_template_args`; `together`→`reasoning:{enabled}`; `string-thinking`→`thinking:string`; `ant-ling`→`reasoning:{effort}`. There are also separate top-level `thinking_token_budget`/`thinking_budget`/`thinking_budget_tokens` budgets (`:985-991`).
- Stream parsing: iterate SDK chunks (`:559-686`). Order: `responseId ||= chunk.id`; `responseModel`; usage; `finish_reason`→stop; then process deltas: reasoning fields first (`reasoning_content`/`reasoning`/`reasoning_text`, taking only the first non-empty one to avoid duplication) → `reasoning_details` (structured replay, stored into thinkingSignature) → `content` → `tool_calls`. Text/thinking blocks are created lazily and emit `*_start`; tool calls map to blocks by `index` or `id`, `function.arguments` accumulates incrementally and `parseStreamingJson` updates `arguments`, with `parseToolCallArguments` finalizing at `toolcall_end` (`:500-557,688-709`).
- Tool increments: `partialArgs` accumulates the string, `arguments=parseStreamingJson(partialArgs)` is available live; the end event yields the final `ToolCall`. Grammar tools (OpenAI custom tools) go through `custom.input` + a bracket-completion algorithm (`:411-432`).
- usage: `parseChunkUsage` (`:1529-1570`), see types doc §10.3; usage may appear in `chunk.usage` or in the non-standard `choice.usage` (Moonshot, `:576-580`).
- Errors and wrap-up: `formatProviderError(normalizeProviderError(error))`; OpenRouter's `error.metadata.raw` is appended to the message (`:710-733`).
- compat: `detectCompat` detects defaults for 28 fields by provider/baseUrl (`:1603-1700`); `model.compat` overrides field by field (`:1706-1744`). These are the entire adaptation switches of the openai-compatible ecosystem; Kotlin must support them all, or niche providers will behave differently.
- prompt cache: `resolveCacheRetention` (short by default), `getCompatCacheControl`/`applyAnthropicCacheControl` (OpenRouter's `anthropic/*` models use Anthropic-style `cache_control`, `:1083-1197`).

### 6.2 anthropic-messages (`src/api/anthropic-messages.ts`, 1,690 lines)

- Client: `@anthropic-ai/sdk`; the `PiAnthropic` subclass disables the SDK's default credential chain (`:335-339`). Three modes (`:1015-1114`): GitHub Copilot (Bearer), OAuth token (`sk-ant-oat` prefix, Bearer + Claude Code UA/`x-app:cli`), and API key/header-owned/federation. Federation caches the client's shared token (`:372-377`).
- Request: `client.beta.messages.create(params, {maxRetries:0})` (`:654-662`) → `POST {baseUrl}/v1/messages`. `params` (`:1160-1332`): `model, messages, max_tokens (defaults to model.maxTokens), stream:true, system, temperature (disabled when thinking is on), tools, thinking, output_config, metadata, tool_choice, fallbacks`.
- beta headers: passed as the SDK `betas` array (`:1116-1158`): OAuth must send `claude-code-20250219`,`oauth-2025-04-20`; fine-grained tool streaming; interleaved thinking; server-side fallback; mid-convo output config; inline tools. Users can fully override via the `anthropic-beta` header, with `null` clearing it.
- thinking: `thinkingDisabled`→`{type:"disabled"}`; managed effort→`{type:"adaptive",display,block_binding:{prefix_mismatch_behavior:"drop_block"}}`+`output_config.effort`; adaptive→`{type:"adaptive",display}`; older models→`{type:"enabled",budget_tokens: options||1024, display}` (`:1278-1309`). `mapThinkingLevelToEffort` maps minimal/low→low, medium→medium, high→high, and xhigh/max use `thinkingLevelMap` (`:941-959`).
- SSE decoding: a hand-written parser (`:379-569`) supporting CRLF/LF, event + multi-line data, and comment skipping; it requires `message_start` and `message_stop` to be paired, otherwise it throws "ended before message_stop".
- Event sequence (`:696-892`): `message_start` (id/model/initial usage/input_transformations) → `content_block_start` (text/thinking/redacted_thinking/tool_use/fallback) → `content_block_delta` (`text_delta`/`thinking_delta`/`input_json_delta`/`signature_delta`) → `content_block_stop` (emits `*_end`) → `message_delta` (stop_reason + usage increments) → `message_stop`. Tool arguments: `input_json_delta.partial_json` accumulates + `parseStreamingJson`, with `parseToolCallArguments` finalizing at `content_block_stop`.
- redacted thinking: the `data` of a `redacted_thinking` block is stored in `thinkingSignature`, with `thinking="[Reasoning redacted]"` and `redacted=true` (`:746-755`).
- usage: updated both at `message_start` and `message_delta`; `cacheWrite1h` comes from `cache_creation.ephemeral_1h_input_tokens` (the Vercel gateway also reports it in deltas, `:874-880`); reasoning comes from `output_tokens_details.thinking_tokens`. When `responseModel` differs from the request, cost is switched per `compat.allowedFallbackModels` (`:704-710`).
- strict tools downgrade: when a request is rejected with 400 (grammar too large / too many strict tools / too many union schemas), it disables prefer-strict tools, retries once, and writes a diagnostic (`:664-689`).
- message conversion: `transformMessages` then convert by role (`:1353-1558`); consecutive toolResults merge into one user message (required by the z.ai Anthropic endpoint); images in error toolResults are split out afterwards; `cache_control` is added to the last system block, the last tool, and the last user/system message (`:1529-1555`).
- tool schema: Anthropic strict mode has a blacklist of unsupported keywords/formats (`:1589-1620`); `input_schema` for strict tools emits both the full schema and the legacy `{type,properties,required}` (`:1640-1651`). In OAuth mode, tool names are converted to Claude Code-style case aliases (`:95-133,1654`).
- Stop reason mapping: see types doc §10.4.

### 6.3 openai-responses (`src/api/openai-responses.ts` 459 lines + `openai-responses-shared.ts` 887 lines)

- Request: `client.responses.create(params, {maxRetries:0})` (`:220-227`) → `POST {baseUrl}/responses`. `params` (`:345-429`): `model, input, stream:true, store:false, prompt_cache_key, prompt_cache_retention, prompt_cache_options, max_output_tokens(>=16), temperature, service_tier, tools, tool_choice, reasoning:{effort,summary}, include:["reasoning.encrypted_content"], samplingParams`.
- Input conversion (shared `:191-432`): system→`developer`/`system` text; assistant text→`{type:"message",role:"assistant",content:[{type:"output_text",text}],status:"completed",id,phase?}`; thinking→deserialize `thinkingSignature` into a reasoning item; toolCall→`function_call` (id may be omitted, call_id is required, namespace only replayed for the same model) or `custom_tool_call` (grammar); toolResult→`function_call_output`/`custom_tool_call_output`. Tool changes (`additions`/`changes`) support `tool_search_call/output`, `additional_tools`, `configuration_update` (`:231-273`).
- Event handling (shared `:511-855`): `response.created`→responseId; `response.output_item.added` creates blocks (reasoning/message/function_call/custom_tool_call); `response.reasoning_summary_text.delta`, `response.reasoning_text.delta`, `response.output_text.delta`, `response.refusal.delta`; `response.function_call_arguments.delta/done`; `response.custom_tool_call_input.delta/done`; `response.output_item.done` finalizes blocks (thinking stores the whole item JSON into `thinkingSignature`; text stores `textSignature`=TextSignatureV1; tools finalize arguments); `response.completed/incomplete` are terminal; `error`/`response.failed` throw. A stream ending without a terminal event throws "ended before a terminal response event"; `toolUse` with unfinished tool calls (`partialJson`/`customInput` not deleted) also throws (`:838-854`).
- usage/cost: `input_tokens - cached - cache_write`, `output_tokens`, `total_tokens`, reasoning_tokens (`:630-655`); the service tier multiplier is applied to cost (`:431-459`).
- ChatGPT login tokens disable some fields (prompt_cache_retention/options, max_output_tokens, temperature, etc., `:367-385`) and append a notice to subscription-limit errors (`:263-271`).
- Besides identity, HTTP headers include session affinity (`session_id`/`x-client-request-id`/`x-session-id`, `:320-329`).

### 6.4 azure-openai-responses (`src/api/azure-openai-responses.ts` 261 lines + `azure-openai-config.ts` 107 lines)

- Client: `new AzureOpenAI({apiKey, apiVersion, baseURL})` (`:160-179`).
- endpoint resolution (`azure-openai-config.ts:16-107`): options `azureBaseUrl` first, then env `AZURE_OPENAI_BASE_URL`; otherwise construct `https://<resource>.openai.azure.com/openai/v1` from `AZURE_OPENAI_RESOURCE_NAME` or options; model.baseUrl is the fallback. Azure host paths normalize to `/openai/v1` (`:44-64`).
- deployment: `azureDeploymentName` > env `AZURE_OPENAI_DEPLOYMENT_NAME_MAP` (`model=deployment,...`) > model.id; the `model` field is rewritten via `onPayload` before sending (`src/providers/azure.ts:27-37`). api-version defaults to `v1`.
- Request/stream handling reuses `openai-responses-shared` (`:179+`). The azure provider's `openai-completions` channel does the same baseUrl/deployment resolution (`src/providers/azure.ts:40-55`).
- The catalog has both `azure-openai-responses` and `openai-completions` models for azure; Kotlin must dispatch by model.api.

### 6.5 openai-codex-responses (`src/api/openai-codex-responses.ts`, 1,724 lines)

- OAuth only (ChatGPT Plus/Pro). Request URL: `resolveCodexUrl` normalizes baseUrl to `{base}/codex/responses` (`:663-669`); the WebSocket variant is `wss://.../codex/responses` (`:671-676`).
- Transport: `auto` by default, trying WebSocket and falling back to SSE on failure/Session flags; `transport` can force `sse`/`websocket`/`websocket-cached` (`:290-330`). SSE request bodies can be zstd-compressed (level 3, Node only, `:62-64,204-237`).
- Request body (`:542-622`): `model, store:false, stream:true, instructions (leading system text, defaulting to "You are a helpful assistant."), input, text:{verbosity}, include:["reasoning.encrypted_content"], prompt_cache_key, tool_choice:"auto", parallel_tool_calls:true, tools, reasoning:{effort,summary}`. The system prompt is not placed in input (`includeSystemPrompt:false`).
- Headers (`:1649-1724`): `Authorization: Bearer <access>`, `chatgpt-account-id` (taken from the `chatgpt_account_id` claim under `https://api.openai.com/auth` in the access JWT), `OpenAI-Beta: responses=experimental` (SSE)/`responses=websockets` (WS), `originator: pi`, a session id header; `OpenAI-Beta` can be removed for compatibility.
- Events: SSE events are first mapped by `mapCodexEvents` into Responses-style events, then handed to shared `processResponsesStream` (`:682-702`); WS goes through `processWebSocketStream` (`:1515+`) with a session cache `previous_response_id` and connection-limit error codes such as `websocket_connection_limit_reached` (`:56-78`).
- Retries: its own `DEFAULT_MAX_RETRIES=0`, `getRetryAfterDelayMs`, and connection-error/rate-limit classification (`:123-203`).
- This is DEFER-level for Kotlin: the protocol depends on OpenAI Codex private behavior, WebSocket, and zstd.

### 6.6 google-generative-ai (`src/api/google-generative-ai.ts` 472 + `google-shared.ts` 517)

- Client: `new GoogleGenAI({apiKey, httpOptions:{baseUrl,apiVersion:"",headers}})` (`:348-368`); custom fetch is not supported (`:87-89`).
- Request: `client.models.generateContentStream(params)` (`:100`) → `POST {baseUrl}/models/{id}:streamGenerateContent?alt=sse`. `params` (`:370-430`): `model, contents, config:{temperature,maxOutputTokens,systemInstruction,tools:[{functionDeclarations}],toolConfig:{functionCallingConfig:{mode}},thinkingConfig:{includeThoughts,thinkingLevel|thinkingBudget}}`. For strict tool sampling (Gemini 3+), mode uses `VALIDATED` (`google-shared.ts:406-438`).
- Message conversion (`google-shared.ts:193-345`): user→`{role:"user",parts:[{text}|{inlineData}]}`; assistant→`{role:"model",parts}` (text/thinking/toolCall); toolResult→`{role:"user",parts:[{functionResponse:{name,response:{output|error},parts?,id?}}]}`, merging multiple results from the same turn into one user message; Gemini<3 or Claude uses separate image messages. Thinking is only replayed as `{thought:true,text,thoughtSignature}` for the same provider+model, and downgrades to text across models; thoughtSignature must be base64 and is only kept for the same provider/model (`:148-162`).
- Stream parsing (`:106-252`): process candidate.parts in order; `part.thought===true` means thinking (`google-shared.ts:130-132`), otherwise text; only one currentBlock exists at a time, and switching types emits `*_end` before `*_start`; `thoughtSignature` is retained via `retainThoughtSignature` (last non-empty value); `functionCall` yields complete arguments immediately (no increments), generating/reusing a unique toolCall id and immediately emitting `toolcall_start/delta/end` as a trio (`:175-220`); `finishReason`→stop; `usageMetadata`→usage.
- usage (`:232-251`): `input=promptTokenCount-cachedContentTokenCount`, `output=candidatesTokenCount+thoughtsTokenCount`, `cacheRead=cachedContentTokenCount`, total from `totalTokenCount`.
- Retries: `retryGoogleRequest` fills in `headers=undefined` for SDK ApiErrors that have no headers, then reuses the unified retry (`google-shared.ts:487-517`).
- thinking levels: Gemini 3/4 and Gemma 4 use discrete `thinkingLevel` (MINIMAL/LOW/MEDIUM/HIGH), others use `thinkingBudget`; the 2.5 family has a hard-coded budget table (`src/api/google-generative-ai.ts:432-471`); disabling thinking uses `thinkingBudget:0` or a clamped level (`google-shared.ts:104-113`).

### 6.7 google-vertex (`src/api/google-vertex.ts`, 567 lines)

- Reuses `google-shared` message/tool/stop conversion; the differences are the client and auth: `new GoogleGenAI({vertexai:true, project, location, apiVersion:"v1", googleAuthOptions?})` (`:364-381`).
- API key mode vs ADC mode: the `google-vertex` provider's auth resolver supports api-key / ADC / service-account (`src/providers/google-vertex.ts:14-92`); `GOOGLE_APPLICATION_CREDENTIALS` is used as the keyFilename (`:437-440`).
- project/location are required, from options or `GOOGLE_CLOUD_PROJECT`/`GCLOUD_PROJECT` and `GOOGLE_CLOUD_LOCATION` (`:454-473`).
- A custom baseUrl containing `{location}` is not used as an override; a baseUrl containing a version clears apiVersion (`:397-435`).

### 6.8 mistral-conversations (`src/api/mistral-conversations.ts`, 952 lines)

- Hand-written HTTP: `POST {baseUrl}/v1/chat/completions`, `Accept: text/event-stream`, `Authorization: Bearer`, `x-affinity` (sessionId, when prompt cache is hit, `:298-372`).
- Request bodies are remapped from camelCase to snake_case: `topP→top_p, maxTokens→max_tokens, randomSeed→random_seed, responseFormat→response_format`, etc. (`:386-420`). Images support only `imageUrl` (data URL or http); when unsupported, they degrade to text (`:808-923`).
- SSE: hand-written event-boundary detection (supports `\r\n\r\n`/`\n\n`, etc.), joining `data:` lines, ending at `[DONE]` (`:505-525`).
- Stream parsing (`:576-775`): `delta.content` can be a string or an array (text/thinking chunks); thinking opens a block only on type switches; tool_calls map to blocks by `index`, `function.arguments` accumulates as a string incrementally + partial parsing, finalizing at the end. Tool ids must be 9 alphanumeric characters; `createMistralToolCallIdNormalizer` derives them with a short hash and maintains a bidirectional unique mapping (`:238-268`).
- usage: `prompt_tokens/completion_tokens/total_tokens` + 6 cached-field variants (`:555-574,616-628`).
- Stop reasons: see types doc §10.4.
- `streamSimple`: models with a thinkingLevelMap use `reasoning_effort`, otherwise `promptMode:"reasoning"` (`:187-216`).

### 6.9 bedrock-converse-stream (`src/api/bedrock-converse-stream.ts`, 1,428 lines)

- Client: `@aws-sdk/client-bedrock-runtime`'s `ConverseStreamCommand` (`:288-290`). Auth: SigV4 (AWS profile/access key/ECS/IRSA) or bearer token (`AWS_BEARER_TOKEN_BEDROCK`, `:184-245`); `AWS_BEDROCK_SKIP_AUTH=1` uses dummy credentials (proxy scenarios, `:209-215`); region resolution order ARN-embedded > explicit options > env > default chain (`:193-240`); proxy via NodeHttpHandler + Http(s)ProxyAgent (`:222-234`).
- Request (`:268-288`): `{modelId, messages, system, inferenceConfig:{maxTokens,temperature}, toolConfig, additionalModelRequestFields, requestMetadata}`.
- Events (`:300-333`): `messageStart`→`start`; `contentBlockStart` (only toolUse is emitted explicitly; text is not pre-emitted, `:594-615`); `contentBlockDelta` (text/toolUse.input string increments/reasoningContent.text/signature/redactedContent); `contentBlockStop`→`*_end`+finalize; `messageStop`→stop; `metadata`→usage; in-stream exception items (internalServer/modelStreamError/validation/throttling/serviceUnavailable) throw directly.
- redacted reasoning: `redactedContent` (Uint8Array) is buffered, base64-encoded into `thinkingSignature` at block end, with a placeholder in `thinking` (`:667-704`).
- Thinking: Anthropic Claude models support budget/adaptive; `buildAdditionalModelRequestFields` handles the thinking payload; `mapThinkingLevelToEffort` supports models with native xhigh (`:778-848`). prompt cache: `cachePoint`/`cache_control` inserted by `buildSystemPrompt`/`convertMessages` (`:849-925,926-945`).
- usage: `inputTokens/outputTokens/cacheReadInputTokens/cacheWriteInputTokens/cacheDetails[ttl=1h]` (`:717-734`).
- Custom headers: a Smithy build middleware injects them before SigV4, skipping reserved headers (authorization/host/x-amz-*) (`:468-506`); response headers are captured during deserialization (`:519-541`).
- Errors: `formatBedrockError` preserves prefixes such as "Throttling error:" needed for retry/overflow detection (`:373-412`).

### 6.10 pi-messages (`src/api/pi-messages.ts`, 448 lines)

- This is pi's own gateway protocol (used by radius and tests). `POST {baseUrl}/messages`, Bearer key, body `{model, context, options:{temperature,maxTokens,reasoning,cacheRetention,sessionId,toolChoice}}` (`:356-436`).
- The response is SSE, but the data is already pi's event protocol: `start/text_*/thinking_*/toolcall_*/done/error`, camelCase fields, and `toolcall_end` directly gives a complete `ToolCall` (`PiMessagesEvent`, `:55-96`). `createEventConverter` accumulates these events into an `AssistantMessage` and forwards them (`:181-275`).
- `done`/`error` carry usage/responseId/providerThinkingLevel/rewrite diagnostics (`:196-218`); non-2xx parses `{error:{message,code,details}}` and generates `PiMessagesResponseError` (`:99-158`).
- This is SHOULD/DEFER level for Kotlin: if the target does not connect to the radius gateway, it can be skipped.

### 6.11 Image API

Only `openrouter-images` (`src/api/openrouter-images.ts`, 200 lines): OpenAI chat completions non-streaming, body `{model, messages:[{role:"user",content:[text|image_url]}], stream:false, modalities:["image"] or ["image","text"]}` (`:140-167`); the response takes text from `choices[0].message.content` and data URLs from `message.images[].image_url`, decoding base64 into `ImageContent` (`:92-110`); usage uses OpenAI chat fields and is priced (`:169-199`). The registry + lazy loading live in `src/images-api-registry.ts` and `src/providers/images/register-builtins.ts`.

### 6.12 Classifier APIs

- `typesafe-system-one`: `POST {baseUrl}/systemone`, body `{model, state, questions}`, `bool` questions are called `noul` on the wire, output `{answers, usage}` (`src/api/system-one-shared.ts:100-135`, `src/api/typesafe-system-one.ts:15-22`). The OpenRouter and Vercel gateways also use this API with different base URLs.
- `openai-decisions`: `POST {baseUrl}/decisions`, body `{model, input, questions}`, question type maps to choice/score/predicate, images attached as `input_image`, and 504 is not retried (`src/api/openai-decisions.ts:19-27,161-197`).
- `cloudflare-workers-ai-system-one`: Workers AI REST `run` endpoint (48 lines).
- `llama-cpp-classify`: local llama.cpp classification endpoint (461 lines), DEFER.

### 6.13 Cross-cutting modules

- `src/api/transform-messages.ts` (256 lines): shared message preprocessing/replay rules for all APIs (cross-model thinking drop, orphan tool call backfill, image downgrade, error-image splitting). Kotlin must port this before writing each API's conversion.
- `src/api/constrained-sampling.ts` (298 lines): strict/grammar tool schema handling.
- `src/utils/json-parse.ts` (149 lines): JSON repair + partial parsing.
- `src/utils/provider-retry.ts` (128 lines): first-request retry policy; `src/utils/retry.ts` (271 lines): upper-layer agent-level retry and error classification.
- `src/utils/overflow.ts` (193 lines): context overflow detection; `src/utils/estimate.ts` (117 lines): token estimation (3.5 chars/token, 4800 chars for images).
- `src/utils/transcript.ts` (263 lines): system/tools replay, mid-convo folding, tool-change expression capability (`TranscriptToolSupport = none|additions|changes`).

## 7. Authentication system

### 7.1 Data model and resolution order

Credentials: `ApiKeyCredential {type:"api_key", key?, env?}` or `OAuthCredential {type:"oauth", refresh, access, expires(ms), ...extensions}` (`src/auth/types.ts:7-37`). The storage interface `CredentialStore` requires `read/list/modify/delete`; `modify` is the only write path and must be serialized (`:65-94`); the default implementation is in-memory (`src/auth/credential-store.ts:9-67`), and persistence is injected by the host app (pi's coding-agent uses auth.json).

Resolution order (`src/auth/resolve.ts:46-93`):
1. Explicit `overrides.apiKey` (if the provider has apiKey auth).
2. Read stored credentials: OAuth → if it expires within 5 minutes, refresh inside the `modify` lock (two-level check, `refreshStoredOAuthCredential :119-155`); api_key → go through apiKey.resolve.
3. With no stored credential, use ambient sources (env variables, AWS profile, ADC files).
4. Stored but with a type the provider does not support → undefined (no silent fallback to env).

`AuthResult.source` is used by the UI to show the source (e.g. `"stored credential"`, `"ANTHROPIC_API_KEY"`, `"OAuth"`).
`Models.getAuth(model)` also merges model.headers into auth.headers (`src/models.ts:754-773`).

### 7.2 API key system

- Standard implementation `envApiKeyAuth(name, envVars)`: the stored key wins, otherwise the first non-empty env in order (`src/auth/helpers.ts:9-36`).
- Non-standard implementations: anthropic (auth token/bearer/OAuth token/federation, `src/providers/anthropic.ts:19-72`), bedrock (AWS credential chain), vertex (API key/ADC/service-account, `src/providers/google-vertex.ts:14-92`), cloudflare (key+account+gateway three-part, `src/providers/cloudflare-auth.ts:13-103`), azure (key + endpoint parameters), github-copilot (token + dynamic catalog).
- Full env variable table: `src/env-api-keys.ts:73-127` (including anthropic's three tokens, azure, vertex, and bedrock special cases at `:160-192`). Kotlin's env resolution should be injectable (`AuthContext.env/fileExists`) for desktop/Android/tests.

### 7.3 OAuth architecture

`OAuthAuth = { name, isSubscription?, loginLabel?, login(interaction, options?), refresh(credential, signal), toAuth(credential) }` (`src/auth/types.ts:221-245`).
- `login` completes the interaction and returns an `OAuthCredential`; `Models.login` then writes the credential into `CredentialStore` (`src/models.ts:775-830`).
- `refresh` exchanges a new token at the network layer and is called by `Models` under lock; `toAuth` is a pure function converting a valid credential into `ModelAuth` (possibly with a per-credential baseUrl, as with GitHub Copilot).
- The URLs/client ids/callbacks/scopes of the 9 flows are in types doc §8.6; files live in `src/auth/oauth/`: `anthropic.ts` 307 lines, `openai-chatgpt.ts` 309, `openai-codex.ts` 438, `github-copilot.ts` 507, `kimi-coding.ts` 296, `radius.ts` 321, `xai.ts` 239, `meta.ts` 208, `openrouter.ts` 169; shared pieces `device-code.ts` 98, `callback-server.ts` 183, `pkce.ts` 34, `load.ts` 80.
- The device code flow supports `authorization_pending`, `slow_down` (interval +5s or the server's value), `access_denied`, and `expired_token` (`src/auth/oauth/device-code.ts:1-98`).
- The local callback server supports fixed ports, switching ports when occupied (special Anthropic logic), ignoring stray requests, accepting only the first callback, error pages, and cancellation/timeout (`src/auth/oauth/callback-server.ts:52-183`).
- OAuth login interaction surface: `AuthPrompt`/`AuthEvent`/`AuthInteraction` (`src/auth/types.ts:119-164`); Kotlin can implement it with suspend + callback/Flow.

### 7.4 Client identity, User-Agent, and proxies

- `ClientIdentity`: `userAgent: short|detailed|{custom}` and `attribution` (OpenRouter `HTTP-Referer`/`X-OpenRouter-Title`/`X-OpenRouter-Categories`; NVIDIA `X-BILLING-INVOKE-ORIGIN`; OpenCode `x-opencode-client`) and `version` (`src/utils/identity.ts:11-143`).
- The detailed UA looks like `pi/1.3.0 (linux; node/v24.x; x64)`; in browsers it is `pi/<ver> (browser)` (`:74-82`).
- The OpenCode session header `x-opencode-session` is injected by `getOpenCodeSessionHeaders` in `Models.applyAuth` (`src/utils/identity.ts:126-135`, `src/models.ts:880-885`); the provider wrapper layer also injects it (`src/providers/opencode-headers.ts`).
- Proxy: `src/utils/node-http-proxy.ts` (161 lines) parses `HTTP_PROXY/HTTPS_PROXY/NO_PROXY` for Bedrock's Smithy handler; other SDKs leave it to the host's fetch/agent. Kotlin can use OkHttp `ProxySelector`.

---

## 8. Model catalog data sources and the Kotlin acquisition plan (measured)

### 8.1 Production chain

1. Data fetching (`packages/ai/scripts/generate-models.ts`, 3,598 lines):
   - `GET https://models.dev/api.json` (`:1814-1818`): the main catalog, covering anthropic/openai/google/openai-codex/amazon-bedrock/groq/cerebras/mistral/zai/deepseek/...; for each model it takes `tool_call===true`, `limit.context/output`, `cost`, `modalities`, `reasoning_options`.
   - `GET https://models.dev/models.json?type=decision` (`:2618-2619`): classifier models.
   - `GET https://openrouter.ai/api/v1/models` three times (default, `?output_modalities=image`, `?output_modalities=decisions`) (`:1335-1363`): builds openrouter's chat/image/classifier entries, including price conversion and reasoning-level mapping (`scripts/openrouter-catalog.ts:30-105`).
   - `GET https://ai-gateway.vercel.sh/models` (`AI_GATEWAY_MODELS_URL`, `:1380-1456`): the Vercel catalog; evaluation types become classifiers.
   - `GET https://integrate.api.nvidia.com/v1/models` (NVIDIA model id normalization, `:1300-1333`).
   - Radius gateway `GET {gateway}/v1/config` (`:1365-1378`, `src/providers/radius-config.ts:80-96`).
   - After that come many hard-coded corrections (prices, model aliases, thinking mappings, compat overrides, model retirements, regional pricing, etc.); most of the 3,598 lines are here.
2. Normalization and validation: every model must have the `Model` shape `{id,name,api,provider,baseUrl,reasoning,input,cost,contextWindow,maxTokens,...}`; validation/grouping lives in `scripts/model-data.ts:144-336`.
3. Persisting data: `groupProviderModelData` groups by `type:id` under `<api>` and writes `src/providers/data/<provider>.json` (`scripts/model-data.ts:308-335`; write flow `scripts/generate-models.ts:3436-3550`); it also generates the 42 `providers/<provider>.models.ts` files and `models.generated.ts` (`:3459-3540`).
4. Publishing: `npm run generate-model-catalog` produces `.artifacts/model-catalog/models.all.json` + `models.json` + `providers*.json` (`package.json:60-62`), published by `scripts/publish-model-catalog.mjs` to pi.dev's `models/v1/revisions/<sha256>/...` storage (`scripts/model-catalog-protocol.ts:15-31`). `models.all.json` has the shape `{"<provider>": [model,...]}`.
5. Offline/reproducible builds: `nix/model-catalog.json` pins a single revision (`nix/model-catalog.json:1-3`), and `nix/package.nix:26-30` downloads `https://pi.dev/api/models/revisions/<rev>?types=chat,image,classifier` and verifies sha256, then `node packages/ai/scripts/hydrate-model-catalog.ts <file>` generates `src/providers/data/`, followed by `npm run build:offline` (`nix/package.nix:45-50`). `hydrate-model-catalog.ts:37-86` regroups the array by provider and generates `.manifest.json` (schemaVersion 6, with generatedAt fixed to 1970 for reproducibility).
6. Runtime overlay: coding-agent's `remote-catalog-provider.ts` pulls `GET https://pi.dev/api/models/providers/<provider>?types=chat,image,classifier` for each provider (`:14,81-90`), compares `Last-Modified`/`ETag` against the local `generatedAt`, and persists the overlay; it checks every 4 hours (`:16`).

### 8.2 Shape of the installed npm 1.0.0 build (measured)

pi-coding-agent install directory: `npm package @earendil-works/pi-ai (installed via @earendil-works/pi-coding-agent)`, `package.json` version **1.0.0**.
- `dist/providers/data/` has 42 provider JSON files + `.manifest.json`, 968 KB total; `manifest` is `{"schemaVersion":6,"generatedAt":"2026-10-01T18:57:11.882Z","structureHash":"...","files":{"<provider>.json":"<sha256>",...}}`.
- A single file has the shape `{"<api>":{"<type>:<id>":{...model fields...}}}`, for example:
  `{"anthropic-messages":{"chat:claude-fable-5":{"id":"claude-fable-5","name":"Claude Fable 5","api":"anthropic-messages","provider":"anthropic","baseUrl":"https://api.anthropic.com","reasoning":true,"input":["text","image"],"cost":{"input":10,"output":50,"cacheRead":1,"cacheWrite":12.5},"contextWindow":1000000,"maxTokens":128000,"compat":{...},"type":"chat"}}}`
- Counts: 1.0.0 has 1,604 entries (1,532 chat + 57 image + 15 classifier); the largest providers are openrouter 399/57/9, vercel 252/0/2, bedrock 180. Note that 1.0.0 is older than source 1.3.0: 1.0.0 has `azure-openai-responses.json` (no `azure.json`) and kimi-coding on anthropic-messages, whereas 1.3.0 has switched to the `azure` provider and kimi-coding on openai-completions; it is therefore a "shape reference + usable fallback snapshot", not accurate 1.3.0 data.

### 8.3 Authoritative 1.3.0 data: the pinned catalog (recommended, measured)

The source checkout's `src/providers/data/` is gitignored (`packages/ai/src/providers/data/` in the repo-root `.gitignore`), so **the local source tree has no data files** and model data cannot be read from the source directory. The feasible and most accurate approach is to pull pi.dev's pinned revision:

```bash
REV=sha256-ffa3e98626230f378f80200debb736c4a2238b5f2ba246a92cfc7d8e19604678   # nix/model-catalog.json
curl -sS -o /tmp/models.all.json \
  "https://pi.dev/api/models/revisions/$REV?types=chat,image,classifier"
sha256sum /tmp/models.all.json
# measured: HTTP 200, 931,299 bytes;
# ffa3e986... matches the pin exactly (verified locally on 2026-10-22)
```

Measured content:
- 42 providers, exactly matching the provider set in `src/models.generated.ts` (nothing missing or extra).
- 1,705 entries: 1,608 chat + 59 image + 38 classifier. Field union: `id,name,api,provider,baseUrl,reasoning,input,cost,contextWindow,maxTokens,inputLimits,type,thinkingLevelMap,compat,promptCache,headers,output,lab,enabled,providers`; `lab/enabled/providers` appear only in radius's 29 entries (extra fields passed through the gateway; Kotlin can ignore them).
- Per-provider counts are in the section 5 table.
- The single-provider runtime endpoint works the same way and returns an array:
  `curl "https://pi.dev/api/models/providers/anthropic?types=chat,image,classifier"` → HTTP 200, a 17-entry array.

### 8.4 Regenerating from source (measured; for when the latest/custom providers are needed)

`generate-models.ts` supports `--json-only --json-output <dir>`; in that mode it does **not** write `src/providers/data` (`if (!generatorOptions.jsonOnly)` at `scripts/generate-models.ts:3436`), so it can run against a read-only source tree:

```bash
cd /tmp
node packages/ai/scripts/generate-models.ts \
  --strict --json-only --json-output /tmp/model-catalog-gen
# measured (2026-10-22, Node v24.14 native TS type stripping, no npm install needed):
# EXIT 0; produced models.all.json 932,434 bytes + models.json + providers/;
# 42 providers, 1,714 entries = 1,600 chat + 63 image + 51 classifier
```

Differences from the pinned version: 62 pinned-only entries and 71 live-only entries (the catalog drifts with upstream, which is normal). It needs network access to models.dev / openrouter.ai / ai-gateway.vercel.sh / NVIDIA NIM / radius.pi.dev; `--strict` means any fetch failure is an error (production builds use `--strict`).
Note: `scripts/generate-models.ts` contains many manual corrections, so regenerating accepts upstream data as of that day; if Kotlin is to stay behavior-consistent with pi 1.3.0, use the 8.3 pin rather than the latest data.

### 8.5 Recommended approach for Kotlin

Recommended combination (pragmatic and reproducible):
1. **Build time**: pull `models.all.json` at the 8.3 pinned revision and convert it with a one-off script into the Kotlin resource `src/main/resources/pi-models/models.json` (keeping the provider→array structure and field names unchanged). Record revision + sha256 in `catalog-meta.json` next to the resource (or as code constants). The Gradle task can live in `buildSrc`/a standalone script, defaulting to a locally downloaded file with network optional.
2. **Runtime dynamic refresh (SHOULD)**: for each provider call `GET {PI_CATALOG_BASE}/api/models/providers/<id>?types=chat,image,classifier` with conditional requests using ETag/Last-Modified, cached on disk in the app cache; on failure keep the built-in snapshot. This matches pi's coding-agent behavior (`packages/coding-agent/src/core/remote-catalog-provider.ts:81-90,120-190`), but note that pi-ai itself does not do this layer.
3. **Do not take provider metadata from the catalog**: `provider id/name/auth method/special wrapping (cloudflare placeholders, azure deployment, copilot filtering, radius dynamic, opencode session header)` must live in Kotlin code; the catalog only supplies model entries.
4. **Data validation**: assert at load time that `type` is valid, `id` is unique within a type, `provider/api/baseUrl/cost` are present, chat has `reasoning/contextWindow/maxTokens`, and image has `output` containing image; ignore unknown fields.
5. **Fallback**: allow users to add/override models through a configuration file (corresponding to pi's models.json custom-provider capability); Kotlin can define `CustomProviderConfig` and reuse the same `Model` validation.
6. **Optional**: extract the installed 1.0.0 dist as a transitional snapshot (`dist/providers/data/*.json`, 42 files, 968 KB), but it has version skew and is not recommended as the 1.3.0 baseline.

---

## 9. External dependencies and Node-specific capability replacements

| pi-ai dependency/capability | Usage site | Kotlin replacement | Notes |
|---|---|---|---|
| `openai` SDK (incl. AzureOpenAI) | openai-completions/responses/azure/images/codex | OkHttp + kotlinx.serialization hand-written; or openai-kotlin | Hand-writing is more controllable; SDKs hide SSE/retry details |
| `@anthropic-ai/sdk` | anthropic-messages | OkHttp + hand-written SSE | pi itself hand-writes SSE decoding (`anthropic-messages.ts:379-569`) and can be ported 1:1 |
| `@google/genai` | google-generative-ai/vertex | REST + hand-written SSE | Requires ADC token refresh (vertex ADC mode) or API key use |
| `@aws-sdk/client-bedrock-runtime` | bedrock | AWS SDK for Kotlin (bedrockruntime has ConverseStream) or hand-written SigV4 | SigV4 and event-stream deserialization are heavy; the official SDK is recommended |
| `partial-json` | streaming tool arguments | Port the algorithm | Pure logic; port together with `repairJson` |
| `typebox` | tool schema/validation | JsonObject + a JSON Schema validation library | Kotlin needs no schema builder; the networknt validator can be used in tests |
| `http(s)-proxy-agent` | Bedrock/Node proxy | OkHttp Proxy/ProxySelector | Requires PROXY/NO_PROXY parsing |
| `AbortSignal` | end-to-end cancellation | Coroutine Job + `ensureActive()`/`withTimeout` | Preserve semantics: final message stopReason=aborted on abort |
| `AsyncIterable`/`ReadableStream` | SSE | `Flow<AssistantMessageEvent>` + `Channel` | Watch backpressure |
| `node:http` OAuth callback | local OAuth | JDK `com.sun.net.httpserver.HttpServer` or Ktor server | Needs fixed port/port fallback/one-shot callback |
| `node:crypto` (PKCE, UUIDv7, short hash, base64) | OAuth/utils | JVM `MessageDigest`/`SecureRandom`/`Base64` | UUIDv7 must be implemented (algorithm in `src/utils/uuid.ts:8-45`) |
| `performance.now()` | durationMs | `System.nanoTime()` | |
| `process.env` | provider env | Injectable `Environment` interface | Android/TV and similar platforms have no env; a config file/settings are needed |
| zstd (codex request compression) | openai-codex | `zstd-jni` or skip (send uncompressed) | DEFER |
| Node `fetch` | mistral/pi-messages/classifier/OAuth | OkHttp/CIO | Needs an equivalent injectable fetch (`options.fetch`) |

---

## 10. Kotlin design recommendations

### 10.1 Module/package structure

```
:pi-ai-core        // types, event streams, model catalog data classes, SSE, retry, partial-json, usage/cost
:pi-ai-auth        // CredentialStore, AuthContext, api-key resolution, OAuth flows, callback server
:pi-ai-api-openai     // openai-completions + compat
:pi-ai-api-anthropic
:pi-ai-api-openai-responses // includes azure
:pi-ai-api-google
:pi-ai-api-mistral
:pi-ai-api-bedrock   // optional
:pi-ai-api-gateway   // pi-messages/codex (optional)
:pi-ai-images / :pi-ai-classifiers // optional
:pi-ai-testkit     // faux provider, SSE fixtures, golden JSON
```
Building modules per provider is not recommended; build modules per API implementation, with providers as data/factories.

### 10.2 Type modeling

- Use sealed + `@JsonClassDiscriminator` for `Message`/`Content`/`AssistantMessageEvent`; when deserializing sessions use `ignoreUnknownKeys=true`, with `classDiscriminator` on `role`/`type`.
- Keep camelCase field names in memory types and use `@SerialName` only in wire DTOs; this keeps interoperability with pi session files.
- Use `JsonObject` for `Tool.parameters`/`arguments`/`details`; do not bake them into data classes.
- For `Model.compat`, choose one of two options: a) a sealed per-API type; b) one fully nullable `Compat` data class + dispatch. Given 30+ compat fields and continued growth, b is simpler but requires `encodeDefaults=false`.
- Use `@Serializable` enums plus `@JsonNames`/custom serializers for unknown values (an unknown stop reason becomes error per provider rules rather than crashing).
- Time is uniformly Long ms; cost is Double with enough serialization precision (matching pi's JSON numbers is sufficient).

### 10.3 Streaming API shape

Recommended:
- `fun stream(model, context, options): AssistantStream`, with `AssistantStream` exposing `events: Flow<AssistantMessageEvent>` and `suspend fun result(): AssistantMessage`.
- Internally: `Channel<AssistantMessageEvent>` + `CompletableDeferred<AssistantMessage>`; after the terminal event is written, close the channel and complete the deferred; `result()` waits for termination (on `error` it returns the error message rather than throwing, matching pi).
- Setup exceptions (missing key/module load failure) cannot be thrown before `stream()` returns (Kotlin must return an object), so convert them uniformly into `error` events; if the host needs "throw synchronously" semantics, provide a variant such as `streamOrThrow()`, or let `streamSimple` throw directly when auth is missing.
- Cancellation: `AssistantStream.cancel()` or an injected `CoroutineScope`/`Job`; on cancellation produce a final message with `stopReason=aborted` (note pi's semantics: content already produced is still returned, and `errorMessage` is generally empty).
- `complete()` = `stream().result()`.

### 10.4 HTTP/SSE layer

- A unified `HttpTransport` interface: `execute(request): Response` (supporting custom baseUrl, headers, timeout, proxy, cancellation); OkHttp by default. This makes an `options.fetch` equivalent injectable for tests.
- Implement the SSE parser separately (supporting CRLF/LF, multi-line data, event fields, comments, `[DONE]`, and no trailing blank line) for reuse by anthropic/mistral/pi-messages/codex/google. The OpenAI official SDK's SSE behavior can be handled per chunk and need not be reused.
- Split timeouts: connection/first-byte timeout vs stream idle timeout (pi's `timeoutMs` semantics differ per API: Mistral only covers response headers, `src/api/mistral-conversations.ts:308-312`; the SDK timeout covers the whole request). Kotlin should provide `connectTimeoutMs`/`requestTimeoutMs`/`idleTimeoutMs`, with defaults aligned with pi (SDK default 10 minutes, `src/types.ts:198-202`).

### 10.5 Auth layer

- `CredentialStore`: per-provider `Mutex` + atomic writes; `modify` performs refresh under the lock; the auth.json structure stays in pi's format (section 8.1).
- OAuth: build the generic device-code machine + callback-server machine first, then fill constants and token exchange per flow; centralize client id/redirect/scope in a data table for easy updates.
- `AuthInteraction` uses Kotlin interfaces + suspend (prompt/notify); desktop can implement it as console/GUI, Android as browser + custom scheme.
- env resolution: injectable `AuthContext.env(name): String?` and `fileExists(path)`; desktop uses `System.getenv` + `~` expansion, Android uses config files.

### 10.6 Errors, retries, usage

- Error text must match pi (`normalizeProviderError` logic), otherwise the upper layer's `isRetryableAssistantError`/`isContextOverflow` text recognition fails. Kotlin should turn "error classification" into a structured enum while keeping the original text, so the upper layer can choose; do not keep only the message.
- Retry policy: first request on 408/409/429/5xx, `x-should-retry` takes precedence, `retry-after(-ms)`, exponential backoff + jitter, configurable 60s cap; retries must be cancellable.
- usage/cost: recompute uniformly whenever usage is received (providers may report usage multiple times); do not miss the 2× input price of `cacheWrite1h`; do not miss the OpenAI Responses service-tier multiplier.
- `durationMs` is filled on the terminal message with a monotonic clock; do not overwrite an existing value.

### 10.7 Model catalog

- Ship only a trimmed version of `models.all.json` in resources (radius's `lab/enabled/providers` can be dropped, but keep behavior fields such as compat/thinkingLevelMap/promptCache/inputLimits).
- Build a `providerId -> Map<type, Map<id, Model>>` index at startup; queries such as `getModels` become O(1).
- On catalog version mismatch (a new API not implemented): skip that model and log a warning rather than failing entirely (pi's `withKnownModelTypes` also drops unknown types, `src/models.ts:132-142`).
- Custom providers: allow config files to declare `{id, baseUrl, auth, api, models:[...]}`, going through the same validation/registration path as built-ins (pi's `createProvider` semantics).

### 10.8 Test strategy

- **Pure logic unit tests**: event stream order/termination, JSON repair/partial, cost tiers, overflow regexes, transcript replay, tool id normalization, cross-model thinking drop, strict schema conversion. Port directly from the MUST checklist in types doc §12.
- **Protocol fixture tests**: use MockWebServer (OkHttp) to return pre-recorded SSE text and assert the event sequence and final message. Each API should cover at least: normal text, thinking, tool call increments, usage, errors (4xx/5xx), abort.
- **HTTP layer tests**: proxy, timeouts, retries, retry-after.
- **Catalog tests**: load the pinned JSON in full and verify all 42 providers/1,705 entries pass validation; JSON round-trip.
- **OAuth tests**: device code state machine, callback server one-shot/timeout, PKCE, token refresh (with a local mock server).
- **End-to-end** (optional, needs keys): one smoke test per primary API.

## 11. Scope tiers and workload

### 11.1 MUST (first usable version)

| Item | Description | Estimated Kotlin lines |
|---|---|---|
| Core types + serialization | All types, events, compat, options from sections 3-5 and 7 | 2,000-2,500 |
| Event stream + lazy/setup semantics | Channel/Flow/result/cancel/durationMs | 250-350 |
| transcript/transform | normalizeContext, system replay, tool replay, image downgrade, orphan tool calls | 500-700 |
| SSE + partial JSON + repair | Generic parser + partial-json port | 500-700 |
| retry/abort/error normalization/overflow | provider-retry + retry + error-body + overflow | 600-800 |
| Models/Provider registry | createModels/createProvider, getModels, auth apply, refresh (network optional), getAvailable | 800-1,000 |
| auth (api key + env + credential storage) | auth.json compatibility, CredentialStore, AuthContext | 500-700 |
| openai-completions | All compat fields, cache, thinking branches | 1,500-2,000 |
| anthropic-messages | SSE, thinking, strict tools, cache | 1,500-1,800 |
| openai-responses (incl. shared) | tool/search/additional_tools can be deferred | 1,200-1,600 |
| Model catalog data + load validation | Resources + index + validation + conversion script | 300-500 + about 1 MB of JSON resources |
| usage/cost | calculateCost, per-API usage mapping | 300-500 |
| Tests and test doubles | faux provider, MockWebServer fixtures, unit tests | 2,000-3,000 |
| **Total (excluding tests)** | | **about 8,000-11,000** |

### 11.2 SHOULD (second batch)

`google-generative-ai` (900-1,200), `google-vertex` (reuses Google conversion, 300-500), `mistral-conversations` (600-800), `azure` (200-300), `bedrock-converse-stream` (1,200-1,600, including SigV4/event stream), `openrouter-images` (250-350), classifiers (typesafe/openai-decisions 400-600), OAuth flows (150-400 each; start with anthropic/openai-chatgpt/github-copilot), dynamic catalog refresh + personalization (radius/openrouter/copilot, 300-500), client identity/UA/attribution (150-250).

### 11.3 DEFER

- `openai-codex-responses` WebSocket transport, zstd compression, session cache (the SSE version can come first, about 700 lines; WS adds another 600-900).
- `pi-messages`/radius (if the product does not connect to the radius gateway).
- `llama-cpp-classify`, `cloudflare-workers-ai-system-one`, Cloudflare AI binding.
- deferred-response (`fetchDeferred/cancelDeferred`).
- telemetry, browser/Vite bundling adaptations, CLI.
- Nix hydration, the pi.dev publishing chain (Kotlin only needs to consume the pinned catalog).
- Automatic resizing of image `inputLimits.resize` (can be done with ImageIO in Kotlin, but not core).
- Full support for mid-conversation system messages / tool changes (fold first, matching pi's behavior for unsupported models).

### 11.4 Milestones

| Milestone | Contents | Acceptance |
|---|---|---|
| M0 (1 week) | Catalog data landed + types/serialization + event stream skeleton | Full pinned JSON loads; event stream unit tests green |
| M1 (1-2 weeks) | HTTP/SSE/retry/partial JSON + openai-completions + Models/Provider + api key auth | Full text/thinking/tool/usage/error/abort event sequences against a mock provider; at least one openai-compatible provider working with a real key |
| M2 (1 week) | anthropic-messages (hand-written SSE parsing, thinking, cache, strict tools downgrade) | Fixture event sequences; real Anthropic key smoke test |
| M3 (1 week) | openai-responses + azure; usage/cost parity | Compare usage/cost against pi for the same request; service-tier multiplier unit test |
| M4 (1-2 weeks) | google/mistral; OAuth infrastructure + 2-3 flows; dynamic catalog refresh | Login/refresh tests; protocol fixtures |
| M5 (2 weeks) | bedrock, images/classifiers, codex SSE, remaining OAuth | On demand |

---

## 12. Risks and questions for lead decisions

Risks:
1. **Protocol drift**: 42 providers and 10 APIs change with upstream; the pinned catalog drifts too. Record the catalog revision + generation date in build artifacts, and use fixed fixtures in tests.
2. **Auth/compliance**: OAuth client ids are embedded in open-source code (anthropic/openai/github, etc.); if Kotlin reuses these flows, the terms of use and client identity must be confirmed (User-Agent/attribution may also affect bans).
3. **Error text compatibility**: upper-layer retry/overflow/compaction logic depends on error text regexes; if Kotlin rewrites the text, behavior silently breaks. Keep equivalents of `isRetryableAssistantError`/`isContextOverflow` in Kotlin and test them.
4. **Bedrock/Codex**: SigV4, event-stream deserialization, WebSocket, and zstd are heavy work; use the official AWS SDK for Kotlin and leave Codex for last.
5. **Thinking signature replay**: thinking/signatures must be dropped or downgraded across models/providers per the rules, or "illegal signature replay" 400s appear; this is the easiest trap (`transform-messages.ts` and each API's same-provider+model checks).
6. **Tool id normalization**: Responses' `callId|itemId`, Anthropic's 64-character limit, and Mistral's 9-character limit will break cross-provider handoff if not handled.
7. **Model data size**: the full JSON is about 0.9-1 MB; acceptable for Android resources, but watch parse time (lazy loading + indexing recommended).
8. **Test cost**: real-provider smoke tests need keys/quota; prefer MockWebServer + recorded SSE fixtures.

Questions requiring lead/product decisions:
- Target platform: pure JVM (desktop/server) or Android/KMP? Android has no `System.getenv`, needs WebView/Custom Tabs OAuth, and OkHttp is available on Android.
- Is OAuth needed (subscription providers)? If only API keys are needed, 3,000+ lines can be saved.
- Are image/classifier needed? If not, 1,000+ lines can be saved.
- Is bidirectional compatibility with pi's auth.json/models.json required? If so, credential/model field naming must match exactly.
- Catalog update strategy: a built-in snapshot (offline first) or runtime pulls from pi.dev (network dependent)? Both is recommended, with the snapshot as the default.

---

## 13. Appendix

### 13.1 Key file sizes (top 20)

| File | Lines | Role |
|---|---|---|
| `src/api/openai-completions.ts` | 1,744 | OpenAI-compatible chat |
| `src/api/openai-codex-responses.ts` | 1,724 | Codex Responses (SSE/WS) |
| `src/api/anthropic-messages.ts` | 1,690 | Anthropic Messages |
| `src/api/bedrock-converse-stream.ts` | 1,428 | Bedrock ConverseStream |
| `src/models.ts` | 1,299 | Models runtime |
| `src/api/mistral-conversations.ts` | 952 | Mistral |
| `src/types.ts` | 904 | Core types |
| `src/api/openai-responses-shared.ts` | 887 | Responses shared conversion/stream |
| `src/providers/faux.ts` | 727 | Test provider |
| `src/providers/compat-schema.ts` | 644 | compat schema |
| `src/api/google-vertex.ts` | 567 | Vertex |
| `src/api/google-shared.ts` | 517 | Google shared conversion |
| `src/api/google-generative-ai.ts` | 472 | Gemini |
| `src/api/llama-cpp-classify.ts` | 461 | llama.cpp classification |
| `src/api/openai-responses.ts` | 459 | Responses |
| `src/api/pi-messages.ts` | 448 | pi gateway protocol |
| `src/auth/oauth/github-copilot.ts` | 507 | Copilot OAuth |
| `src/auth/oauth/openai-codex.ts` | 438 | Codex OAuth |
| `src/utils/validation.ts` | 353 | Tool argument validation |
| `src/providers/all.ts` | 190 | 42-provider aggregation |

### 13.2 Data statistics (measured locally)

| Dataset | Providers | Total | chat | image | classifier |
|---|---|---|---|---|---|
| 1.3.0 pinned catalog (`nix/model-catalog.json` revision) | 42 | 1,705 | 1,608 | 59 | 38 |
| 1.0.0 installed npm dist (`dist/providers/data`) | 42 | 1,604 | 1,532 | 57 | 15 |
| 2026-10-22 regeneration from source | 42 | 1,714 | 1,600 | 63 | 51 |

### 13.3 Glossary

- **Models**: the provider collection and request facade (`src/models.ts`).
- **Provider**: the runtime unit of a service provider (id/baseUrl/auth/models/api implementations).
- **API**: a wire protocol implementation (e.g. `anthropic-messages`); one API can be reused by multiple providers.
- **compat**: per-model compatibility switches overriding URL-detected defaults.
- **TranscriptContext**: a normalized context (system/tools in the leading system message).
- **pinned catalog**: the pi.dev model catalog revision pointed to by `nix/model-catalog.json`.
- **faux provider**: a scripted provider used in tests.

