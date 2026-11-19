# pi-coding-agent compatibility format spec (Kotlin implementation-ready)

Goal: give Kotlin implementers a precise format checklist that "interoperates with existing pi 1.3.0 by following it". Each item includes `file:line` evidence and real samples.

Compatibility levels:

- **R (read compatible)**: can correctly parse existing files; writing is not required.
- **W (write compatible)**: written files can be parsed correctly by real pi.
- **P (protocol compatible)**: wire/pipe formats match field by field.

Anything marked **R+W** requires bidirectional golden tests (generate with real pi, read and write back with Kotlin, then open with real pi again).

---

## 1. Data roots and path rules

### 1.1 Configuration directories

| Path | Contents | Evidence |
|---|---|---|
| `$PI_CODING_AGENT_DIR` or `~/.pi/agent` | Agent directory (default) | `src/config.ts:605-612`, `src/cli/args.ts:462` |
| `<agent-dir>/settings.json` | User-level settings | `docs/configuration.md:13` |
| `<agent-dir>/auth.json` | API keys and OAuth credentials (0600) | `src/core/auth-storage.ts:64-79` |
| `<agent-dir>/models.json` | Custom providers/models/overrides | `docs/configuration.md:16` |
| `<agent-dir>/models-store.json` (+`.lock/`) | Dynamic model catalog cache | `src/core/models-store.ts:54-60` |
| `<agent-dir>/mcp.json` | MCP servers | `docs/mcp.md:32` |
| `<agent-dir>/mcp-auth.json` | MCP OAuth credentials | `docs/mcp.md:129` |
| `<agent-dir>/mcp-cache.json` / `mcp.log` | MCP cache and logs | Measured locally; `docs/mcp.md:98` |
| `<agent-dir>/trust.json` | Project trust decisions | `src/core/trust-manager.ts:17` |
| `<agent-dir>/keybindings.json` | Keybinding overrides | `docs/keybindings.md:9` |
| `<agent-dir>/AGENTS.md` / `AGENTS.override.md` / `CLAUDE.md` | Global context | `src/core/resource-loader.ts:184-199` |
| `<agent-dir>/SYSTEM.md` / `APPEND_SYSTEM.md` | Replace/append the system prompt | `src/core/resource-loader.ts:1213-1240` |
| `<agent-dir>/extensions/` | User extensions | `docs/configuration.md:21` |
| `<agent-dir>/skills/` | User skills | `docs/configuration.md:22` |
| `<agent-dir>/prompts/` | User prompt templates (the old name `commands/` is migrated) | `src/migrations.ts:137-155` |
| `<agent-dir>/themes/` | User themes | `docs/configuration.md:24` |
| `<agent-dir>/sessions/--<encoded-cwd>--/` | Session JSONL | `src/core/session-manager.ts:589-598` |
| `<agent-dir>/bin/` | Managed binaries (fd, rg) | `src/config.ts:639-643`, `src/utils/tools-manager.ts:11` |
| `<agent-dir>/npm/` | Package install root (`node_modules`, `package.json`) | Measured locally, `docs/packages.md:33` |
| `<cwd>/.pi/...` | Project-level resources of the same names (requires project trust) | `docs/configuration.md:26-38` |
| `~/.agents/skills/`, `<cwd ancestors>/.agents/skills/` | Agent Skills compatibility directories (the latter requires trust) | `src/core/package-manager.ts:2464-2570`, `src/core/trust-manager.ts:181-197` |

### 1.2 Environment variables

Process configuration (`docs/environment-variables.md:77-100`, `src/config.ts:584-586`):

| Variable | Effect |
|---|---|
| `PI_CODING_AGENT_DIR` | Overrides the agent directory |
| `PI_CODING_AGENT_SESSION_DIR` | Overrides the session directory (`--session-dir` takes precedence) |
| `PI_PACKAGE_DIR` | Overrides the package directory (Nix/Guix) |
| `PI_OFFLINE` | `1`/`true`/`yes` disables networking (and sets `PI_SKIP_VERSION_CHECK=1`, `src/main.ts:579-583`) |
| `PI_SKIP_VERSION_CHECK` | Disables the pi.dev version check |
| `PI_TELEMETRY` | Overrides install telemetry |
| `PI_CODEMODE_MAX_WORKERS` | codemode worker limit (default 2) |
| `PI_CACHE_RETENTION` | `long` enables long cache retention |
| `PI_SHARE_VIEWER_URL` / `PI_RADIUS_GATEWAY` | share/bug upload addresses |
| `PI_HARDWARE_CURSOR` / `PI_CLIPBOARD_OSC52` / `PI_HYPERLINKS` / `PI_PROGRAM_STATUS` / `PI_IMAGE_PROTOCOL` / `PI_TRUE_COLOR` / `PI_TUI_ESC_TIMEOUT` | Terminal capability overrides |
| `VISUAL` / `EDITOR` | External editor fallback |
| `HTTP_PROXY` / `HTTPS_PROXY` | Proxy |

Process markers (`src/cli/setup.ts:7-9`, `src/rpc-entry.ts:7-9`): `PI_CODING_AGENT=true`, `AI_AGENT=pi`.

Tool subprocess environment (only for LLM-invoked `bash`/`powershell`, not the user's `!` command; `docs/environment-variables.md:20-49`, `src/core/tools/bash.ts:193-209`):

| Variable | Value |
|---|---|
| `PI_SESSION_ID` | Current session id |
| `PI_SESSION_FILE` | Absolute session path (unset for in-memory sessions) |
| `PI_PROVIDER` | Current model provider |
| `PI_MODEL` | Current model id |
| `PI_REASONING_LEVEL` | `off`/`minimal`/`low`/`medium`/`high`/`xhigh`/`max` |

Note: these 5 inherited variables are deleted before exposure, to prevent a nested pi from leaking the parent session's information (`bash.ts:194-198`).

---

## 2. Session JSONL (R+W, highest priority)

### 2.1 File location and naming

```
<agent-dir>/sessions/--<encoded-cwd>--/<ISO-8601 timestamp, with ":" and "." replaced by "-">_<session-id>.jsonl
```

- Encoding: `--` + (`cwd` with a leading `/` or `\` removed, and all `/`, `\`, `:` replaced by `-`) + `--` (`src/core/session-manager.ts:589-593`).
  Example: `/home/user/workspace` → `--home-user-workspace--`.
- Filename timestamp: `new Date().toISOString().replace(/[:.]/g,"-")`, e.g. `2026-10-22T01-40-58-902Z` (`session-manager.ts:1683-1685`).
- Session id: `uuidv7()` (`session-manager.ts:264-266`). A custom id must match `^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$` (`session-manager.ts:268-274`).
- `--session-dir` / `PI_CODING_AGENT_SESSION_DIR` / `sessionDir` can override the root; precedence `--session-dir` > env > setting (`src/main.ts:691-696`).

Real sample (local):

```
~/.pi/agent/sessions/--home-user-workspace--/2026-10-22T01-40-58-902Z_01a16144-c396-7069-b1e0-072e1af0764b.jsonl
```

### 2.2 File structure

Strict JSONL: one JSON object per line, ending with `\n`. The first line must be the header. Entries are **append-only and never rewritten** (except `createBranchedSession`/`_rewriteFile`, see 2.7).

```
line 1: SessionHeader
line 2..n: SessionEntry (append order, i.e. write order, not guaranteed to match tree-path order)
```

**Deferred file creation rules** (`session-manager.ts:1160-1202`): header/model_change/thinking_level_change/system message entries may exist in memory, but as long as no message entry with `role` `user` or `assistant` has appeared, no file is created; on first appearance all in-memory entries are written at once (`openSync(path,"wx")` + line-by-line writes), after which each entry is appended. **The Kotlin version must replicate this**, otherwise "opening pi without chatting leaves an empty session file" changes `--continue`/`/resume` behavior.

### 2.3 SessionHeader

```json
{"type":"session","version":3,"id":"01a16144-c396-7069-b1e0-072e1af0764b","timestamp":"2026-10-22T01:40:58.902Z","cwd":"/home/user/workspace"}
```

| Field | Type | Notes |
|---|---|---|
| `type` | `"session"` | Fixed |
| `version` | number? | v1 has no such field; currently writes 3 (`session-manager.ts:41-50`) |
| `id` | string | uuidv7 or custom |
| `timestamp` | string | ISO 8601 (`new Date().toISOString()`) |
| `cwd` | string | Session working directory (determines resource discovery and grouping) |
| `parentSession` | string? | Points to the source session file path on fork/clone (`session-manager.ts:1688-1695`) |

### 2.4 Entry base

```json
{"type":"<kind>","id":"<8 hex or full uuid>","parentId":"<id or null>","timestamp":"<ISO8601>", ...}
```

- `id`: `randomUUID().slice(0,8)`, with 100 conflict retries and a full UUID as the ultimate fallback (`session-manager.ts:276-284`).
- `parentId`: root entries are `null`; `resetLeaf()`/`branchWithSummary(null,...)` can produce multiple roots (`docs/session-format.md:209-222`).
- `timestamp`: session entries use ISO strings; **the `timestamp` inside message bodies is Unix milliseconds** (`docs/session-format.md:47`).

### 2.5 All entry types (11)

`SessionEntry` is defined as a union at `session-manager.ts:183-194`.

#### (1) `message`

```json
{"type":"message","id":"a1b2c3d4","parentId":"prev1234","timestamp":"2026-10-22T01:40:59.547Z","message":{ ... AgentMessage ... }}
```

The `role` of `message` can be:

Common (pi-ai): `system`, `user`, `assistant`, `toolResult`.
coding-agent extensions (`src/core/messages.ts:29-77`): `bashExecution`, `custom`, `branchSummary`, `compactionSummary`.

**SystemMessage** (`core/messages.ts` uses pi-ai types; real sample line 4):

```json
{"role":"system","content":"","sections":{"preamble":"You are an expert coding assistant operating inside pi, ...","tools":"<tools>\n- bash: ...\n</tools>","rules":"<rules>...","docs":"<docs>...","project_context":"<project_context>...","skills":"<skills>...","cwd":"<cwd>/home/user/workspace</cwd>","pi_background_shell_policy":"<pi_background_shell_policy>...</pi_background_shell_policy>"},"toolsAdded":[{"name":"bash","description":"...","parameters":{}}],"timestamp":1792633264614}
```

The section key order in real local sessions is: `preamble, tools, rules, docs, project_context, skills, cwd, pi_background_shell_policy` (the last is injected by an extension via a custom section, showing that sections are an open `Record<string,string|null>`). The same session's `toolsAdded` has 37 tools.

| Field | Notes |
|---|---|
| `content` | Empty string for structured prompts; full text when `before_agent_start` forces a whole prompt |
| `sections` | `Record<string, string \| null>`; `null` deletes that section (`src/core/system-prompt.ts:217-228`) |
| `toolsAdded` | `Tool[]` (name/description/parameters) — initial declarations or newly added tools |
| `toolsRemoved` | `{name}[]` — removed tools |
| `timestamp` | Milliseconds |

**UserMessage**:

```json
{"role":"user","content":[{"type":"text","text":"Merge the two homework PDFs"}],"timestamp":1792633264614}
```

`content` can be a string or `(TextContent|ImageContent)[]`.

**AssistantMessage** (real samples, lines 5, 8, 10):

```json
{"role":"assistant","content":[{"type":"thinking","thinking":"...","thinkingSignature":"reasoning_content"},{"type":"toolCall","id":"call_00_...","name":"bash","arguments":{"command":"..."}}],"api":"anthropic-messages","provider":"anthropic","model":"claude-sonnet-4-5","usage":{...},"stopReason":"toolUse","timestamp":1792633265000}
```

Optional fields: `responseModel`, `responseId`, `providerThinkingLevel`, `thinkingLevel`, `diagnostics`, `rawStopReason`, `errorMessage`, `endTurn`, `deferred` (`docs/message-types.md:119-160`). `stopReason` ∈ `pending|stop|length|toolUse|error|aborted|deferred`; `pending` is not persisted (`docs/message-types.md:145`).

**ToolResultMessage**:

```json
{"role":"toolResult","toolCallId":"call_00_e3v0...","toolName":"bash","content":[{"type":"text","text":"total 8864\n..."}],"isError":false,"timestamp":1792633267694}
```

Optional: `details` (tool-defined; used for rendering/compaction file stats), `usage` (nested model calls), `nestedCalls` (bounded metadata). The `nestedCalls` structure is in `docs/message-types.md:177-194`.

**BashExecutionMessage** (`!` command and RPC bash, `core/messages.ts:29-40`):

```json
{"role":"bashExecution","command":"git status","output":"...","exitCode":0,"cancelled":false,"truncated":false,"fullOutputPath":"/tmp/pi-bash-xxx.log","excludeFromContext":false,"timestamp":1792633267000}
```

`exitCode` may be `undefined` (killed/cancelled). `excludeFromContext:true` = the `!!` prefix, not sent to the model.

**CustomMessage** (injected by extensions, participates in context, `core/messages.ts:46-53`):

```json
{"role":"custom","customType":"my-ext","content":"injected text","display":true,"details":{},"timestamp":1792633267000}
```

**BranchSummaryMessage / CompactionSummaryMessage** (not entry types persisted directly, but generated from `branch_summary`/`compaction` entries when building context, `core/messages.ts:100-120`):

```json
{"role":"branchSummary","summary":"...","fromId":"abc123","timestamp":1792633267000}
{"role":"compactionSummary","summary":"...","tokensBefore":50000,"timestamp":1792633267000}
```

**Content blocks** (`docs/message-types.md:13-66`):

```json
{"type":"text","text":"...","textSignature":"<opaque>"}
{"type":"image","data":"<base64>","mimeType":"image/png"}
{"type":"thinking","thinking":"...","thinkingSignature":"<opaque>","redacted":true}
{"type":"toolCall","id":"call_x","name":"bash","arguments":{...},"thoughtSignature":"<opaque>","namespace":"<openai responses namespace>"}
```

**Usage** (`docs/message-types.md:71-90`):

```json
{"input":100,"output":20,"cacheRead":0,"cacheWrite":0,"cacheWrite1h":0,"reasoning":0,"totalTokens":120,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}
```

`reasoning` is already included in `output`; do not add it again. `cacheWrite1h` is the subset of `cacheWrite` written with 1-hour retention.

#### (2) `thinking_level_change`

```json
{"type":"thinking_level_change","id":"9fa7ddd7","parentId":"66035c39","timestamp":"...","thinkingLevel":"max"}
```

#### (3) `model_change`

```json
{"type":"model_change","id":"66035c39","parentId":null,"timestamp":"...","provider":"opencode-go","modelId":"deepseek-v4.1-flash"}
```

#### (4) `usage`

```json
{"type":"usage","id":"f6g7h8i9","parentId":"...","timestamp":"...","kind":"cache_warm","provider":"anthropic","model":"claude-sonnet-4-5","usage":{...},"note":"optional"}
```

Does not participate in LLM context but counts toward session tokens/cost; an unknown `kind` is treated as ordinary usage (`docs/session-format.md:113-121`).

#### (5) `compaction`

```json
{"type":"compaction","id":"f6g7h8i9","parentId":"e5f6g7h8","timestamp":"...","summary":"...","firstKeptEntryId":"c3d4e5f6","tokensBefore":50000,"usage":{...},"details":{"readFiles":[],"modifiedFiles":[]},"fromHook":false,"systemMessage":{"role":"system","content":"","sections":{...},"toolsAdded":[...],"timestamp":1792635000000}}
```

- `firstKeptEntryId` is required. With retain-none it equals its own id (`docs/compaction.md:131`).
- `systemMessage` is the complete prompt/tool snapshot at the compaction boundary; older sessions may not have it.
- `details`: the default implementation is `{readFiles:string[], modifiedFiles:string[]}`; extensions may customize it.

#### (6) `branch_summary`

```json
{"type":"branch_summary","id":"g7h8i9j0","parentId":"a1b2c3d4","timestamp":"...","fromId":"f6g7h8i9","summary":"...","details":{"readFiles":[],"modifiedFiles":[]},"usage":{...},"fromHook":false}
```

`parentId` is the entry the new branch continues from; `fromId` is the abandoned leaf (`docs/session-format.md:149-162`).

#### (7) `custom`

```json
{"type":"custom","id":"h8i9j0k1","parentId":"...","timestamp":"...","customType":"my-extension","data":{"count":42}}
```

Does not participate in context. Virtual model state uses `customType:"pi.virtual-model-state"`, `data:{provider,modelId,state}` (`src/core/virtual-models.ts:29-37`).

#### (8) `custom_message`

```json
{"type":"custom_message","id":"i9j0k1l2","parentId":"...","timestamp":"...","customType":"my-ext","content":"injected context","display":true,"details":{}}
```

Participates in context (converted to a user message). `display` controls whether the TUI shows it.

#### (9) `context_edit`

```json
{"type":"context_edit","id":"g6h7i8j9","parentId":"...","timestamp":"...","targetId":"c3d4e5f6","replacement":null}
```

**Note**: the non-null form is `{"content": <UserMessage.content | AssistantMessage.content | ToolResultMessage.content | CustomMessage.content>}`, not content written directly (`session-manager.ts:174-180`). Semantics:

- `replacement:null` → the target entry is omitted from the model context.
- Non-null → only the content is replaced; the original role and metadata are kept.
- A string replacement for assistant/toolResult is normalized into a single text block (`session-manager.ts:519-541`).
- With multiple edits to the same target, the last one on the current branch is used; edits are branch-relative (`docs/session-format.md:147`).

#### (10) `label`

```json
{"type":"label","id":"j0k1l2m3","parentId":"...","timestamp":"...","targetId":"a1b2c3d4","label":"checkpoint-1"}
```

`label: undefined` means clear (when writing, the key is omitted or null; parsers must tolerate both).

#### (11) `session_info`

```json
{"type":"session_info","id":"k1l2m3n4","parentId":"...","timestamp":"...","name":"Refactor auth module"}
```

A missing `name` means clear. `--name` / `/name` write this entry (`src/main.ts:710-717`, `session-manager.ts:1304-1316`).

### 2.6 Version migration (R)

`migrateToCurrentVersion` (`session-manager.ts:337-352`):

- v1 (no version) → v2: add `id` (8 hex) and `parentId` (the previous entry) in line order, and convert compaction's `firstKeptEntryIndex` to `firstKeptEntryId`.
- v2 → v3: rename `message.role === "hookMessage"` to `"custom"`.

Kotlin must handle v1/v2/v3 on read and always write v3. **Do not** write migration results back immediately (real pi migrates only in memory and writes back as needed; `loadEntriesFromFile` ensures a newline before appending at the end of the file, `session-manager.ts:662-676`).

### 2.7 Tree and branch operations (W)

- `branch(branchFromId)`: move the leaf to an entry (`session-manager.ts:1579-1590`).
- `resetLeaf()`: set the leaf to null; the next entry becomes a new root (1591-1599).
- `branchWithSummary(branchFromId, summary, details?, fromHook?)`: append a `branch_summary` entry (1600-1631).
- `createBranchedSession(leafId)` (the fork/clone implementation, 1632-1754):
  1. Take the leaf→root path;
  2. drop `label` entries and re-chain `parentId` (subtrees on a label must be reattached);
  3. if a compaction's `firstKeptEntryId` points to a removed label, replace it with its successor;
  4. new header (new id, new timestamp, `parentSession`=old file path);
  5. append the labels of targets on the path as new `label` entries at the end;
  6. if the branch already has user/assistant messages, rewrite the file immediately; otherwise defer to the first message.

### 2.8 Context rebuild (R, must be aligned verbatim)

Three steps (`session-manager.ts:439-586`):

**Step 1: `buildContextEntries(entries, leafId)`**
1. Walk from the leaf up `parentId` to the root to get the path (`buildSessionPath`, 390-417).
2. Find the **newest** compaction entry on the path.
3. No compaction → return the whole path.
4. Compaction exists → `[compaction] + entries on the old path from firstKeptEntryId (inclusive) up to the compaction (skipping system messages) + all entries after the compaction`.

**Step 2: `buildSessionProjection`**
1. Take the last `model_change`/`thinking_level_change` from the **full path** (not the trimmed one) as the current model/thinking (`getSessionContextSettings`, 418-437).
2. For the trimmed entry set, collect `context_edit`s (the later one per targetId wins).
3. Convert each entry to messages (`sessionEntryToContextMessages`, 439-474):
   - `message` → the original message (when `content` is null, fill in `""`/`[]`; tolerate corrupt files);
   - `custom_message` → CustomMessage;
   - `branch_summary` (with a summary) → BranchSummaryMessage;
   - `compaction` → `[systemMessage?, compactionSummary]`;
   - others → empty.
4. Apply context_edit (`projectContextEntry`, 519-541).
5. Note: if an older compaction entry remains at index>0 after trimming (because its original id falls in the retained range), its message contribution is empty (the entry itself is kept only for UI rendering, `session-manager.ts:552-575`).

**Step 3: `buildSessionContext`**: `{messages, thinkingLevel, model}`.

### 2.9 Session list and info (R)

`SessionInfo` (`session-manager.ts:229-243`): `path,id,cwd,name?,parentSessionPath?,created,modified,messageCount,firstMessage,allMessagesText`.

- `list(cwd)`: only looks at `<agent-dir>/sessions/--encoded-cwd--/*.jsonl`; `listAll()` scans all project directories.
- Sorting: `modified` descending (`sortSessionInfos`, 920-922).
- Header reads use a bounded scan: at most 1MB, 4KB buffer (604-613); a corrupt file does not block other sessions.
- `findById(cwd,id)`: reads the header first for an exact match (1874+).
- `continueRecent`: takes the session with the newest mtime in that cwd directory whose header.cwd matches (`findMostRecentSession`, 749-769).
- Deletion: real pi uses the system `trash` command (`docs/session-format.md:16-20`); the Kotlin version can do a plain file delete but must say so in the help.

### 2.10 Session invariants (easy to get wrong)

1. Two kinds of `timestamp`: entry timestamps are ISO strings, while timestamps inside message bodies are millisecond numbers. Mixing them causes UI/sorting errors.
2. `parentId` must point to an existing entry id; otherwise `getTree` treats that entry as a root (`docs/session-format.md:211-216`).
3. Repeated writes from the same compaction produce two compaction entries; `buildContextEntries` only considers the newest on the path, but `tokensBefore` must be recomputed from the pre-compaction projection (`docs/compaction.md:83`).
4. System messages can appear in the middle of a session (tool set/prompt changes); do not assume only the first one.
5. `usage`/`custom`/`label`/`session_info`/`context_edit` produce no context messages, but the UI/statistics need them.

---

## 3. settings.json (R+W)

### 3.1 Location and precedence

- User level: `<agent-dir>/settings.json`; project level: `<cwd>/.pi/settings.json` (requires project trust; `sessionDir` is the exception).
- Merge: scalars/objects are **project-overrides-user** (deep merge for objects); resource arrays **merge** (`src/core/settings-manager.ts:143-158`).
- Write-back: take a file lock, then read-modify-write, **overwriting only the modified fields/nested keys**; unmodified and unknown fields are preserved (`persistScopedSettings`, `settings-manager.ts:601-628`).
- If the directory does not exist, create it with `mode 0o700` (`settings-manager.ts:186-200` region).
- A read failure logs an error and emits a startup diagnostic, but does not crash (`settings-diagnostics.ts`).

### 3.2 Complete top-level field set

Based on `src/core/settings-schema.ts:206-470` (`export const SettingsSchema`, `additionalProperties:true`) and `src/core/settings-defaults.ts:4-67`. A default of `—` means there is no default.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `$schema` | string | — | For editor validation; set to `https://pi.dev/schemas/settings.schema.json` |
| `lastChangelogVersion` | string | — | The changelog version the user has seen |
| `defaultProvider` | string | — | Startup provider |
| `defaultModel` | string | — | Startup model id |
| `defaultThinkingLevel` | enum(off,minimal,low,medium,high,xhigh,max) | `medium` (code default; schema declares no default) | Startup thinking level |
| `modelThinkingLevels` | `Record<"provider/modelId", level>` | — | Startup thinking level per model |
| `transport` | `auto\|sse\|websocket\|websocket-cached` | `auto` | AI provider transport preference |
| `sessionShareDestination` | `auto\|gist\|radius` | `auto` | `/share` target |
| `steeringMode` | `all\|one-at-a-time` | `one-at-a-time` | Steering delivery policy |
| `followUpMode` | `all\|one-at-a-time` | `one-at-a-time` | Follow-up delivery policy |
| `theme` | string | — | Theme name; `light/dark` means follow |
| `compaction` | object | see below | |
| `branchSummary` | object | see below | |
| `retry` | object | see below | |
| `hideThinkingBlock` | boolean | `false` | Hide thinking |
| `showCacheMissNotices` | boolean | `false` | Cache-hit notices |
| `externalEditor` | string | — | Ctrl+G external editor, taking precedence over `$VISUAL`/`$EDITOR` |
| `shellPath` | string | — | Custom shell (supports `~`) |
| `quietStartup` | boolean\|`"header"` | `false` | `true` hides everything; `"header"` keeps only the header |
| `defaultProjectTrust` | `ask\|always\|never` | `ask` | **Global-only** |
| `shellCommandPrefix` | string | — | Prefix for every shell command |
| `npmCommand` | string[] | `["npm"]` (no default written) | argv used to invoke npm |
| `collapseChangelog` | boolean | `false` | Condensed changelog |
| `enableInstallTelemetry` | boolean | `true` | Install/update telemetry |
| `enableAnalytics` | boolean | `false` | Analytics data |
| `trackingId` | string | — | Analytics id |
| `packages` | `PackageSource[]` | `[]` | Package declarations |
| `extensions` | string[] | `[]` | Extension paths |
| `deviceId` | string | — | Stable installation UUID (global-only) |
| `skills` | string[] | `[]` | Skill paths (old object format is migrated) |
| `prompts` | string[] | `[]` | Prompt template paths |
| `themes` | string[] | `[]` | Theme paths |
| `enableSkillCommands` | boolean | `true` | Register `/skill:name` |
| `terminal` | object | see below | |
| `images` | object | see below | |
| `enabledModels` | string[] | — | Model cycle scope (same as `--models`) |
| `defaultTools` | string[] | `["read","bash","edit","write"]` (`DEFAULT_TOOL_NAMES`, `settings-manager.ts:85`) | Initial tool selection, supports `+name`/`-name` |
| `doubleEscapeAction` | `tree\|fork\|none` | `tree` | Double Esc in an empty editor |
| `treeFilterMode` | `default\|no-tools\|user-only\|labeled-only\|all` | `default` | Initial `/tree` filter |
| `thinkingBudgets` | object | — | Token budget per thinking level |
| `editorPaddingX` | number(0-3) | `0` | Editor left/right padding |
| `outputPad` | `0\|1` | `1` | Body left/right padding |
| `autocompleteMaxVisible` | number(3-20) | `5` | Visible suggestions |
| `showHardwareCursor` | boolean | `false` | Use the terminal hardware cursor |
| `markdown` | object | see below | |
| `warnings` | object | see below | |
| `codemode` | object | see below | |
| `sessionDir` | string | — | Session directory |
| `httpProxy` | string | — | Proxy (global-only) |
| `httpIdleTimeoutMs` | number\|"disabled" | `300000` | HTTP idle timeout |
| `cacheWarming` | `off\|streaming\|idle` | `streaming` | Cache warming (global-only) |
| `websocketConnectTimeoutMs` | number\|"disabled" | `15000` | WS connection timeout |
| `tuiMode` | `regular\|fullscreen` | `fullscreen` | TUI mode |
| `fullscreenExitOutput` | `transcript\|resume-hint` | `transcript` | Fullscreen exit output |
| `fullscreenScrollbar` | `auto\|always\|hidden` | `auto` | Fullscreen scrollbar |
| `fullscreenCopyOnSelect` | boolean | `true` | Copy on select in fullscreen |
| `fullscreenWheelScrollLines` | number\|`auto` | `auto` | Wheel scroll lines (clamped to 1-100) |
| `queueMode` | legacy field | — | Migrated to `steeringMode` |
| `websockets` | legacy field | — | Migrated to `transport` |

Nested objects:

```jsonc
"compaction": { "enabled": true, "reserveTokens": 16384, "keepRecentTokens": 20000,
                "modelOverrides": { "provider/modelId": { "reserveTokens": 100000, "keepRecentTokens": 30000 } } }
"branchSummary": { "reserveTokens": 16384, "skipPrompt": false }
"retry": { "enabled": true, "maxRetries": 3, "baseDelayMs": 2000, "maxAgentDelayMs": 60000,
           "provider": { "timeoutMs": 300000, "maxRetries": 0, "maxRetryDelayMs": 60000 } }
"terminal": { "showImages": true, "imageWidthCells": 60, "clearOnShrink": false,
              "showTerminalProgress": false, "hyperlinks": "auto", "images": "auto", "trueColor": "auto" }
"images": { "autoResize": true, "blockImages": false }
"markdown": { "codeBlockIndent": "  ", "mermaid": "streaming" }
"warnings": { "anthropicExtraUsage": true }
"codemode": { "mode": "on", "inlineBudget": 3000 }
```

Settings migration (`migrateSettings`, `settings-manager.ts:397-457`, applied on read and write; writes use the migrated form):

- `queueMode` → `steeringMode` (only when `steeringMode` is absent).
- `websockets:true/false` → `transport:"websocket"/"sse"` (only when `transport` is absent).
- Old object-form `skills` `{enableSkillCommands,customDirectories}` → `enableSkillCommands` + `skills:string[]`.
- `retry.maxDelayMs` → `retry.provider.maxRetryDelayMs`.

### 3.3 `defaultTools` merge algorithm (easy to get wrong; must be followed)

Based on `settings-manager.ts:85-142`, precedence: built-in defaults → user settings → project settings → `--tools` / `--no-tools` / `--no-builtin-tools`.

- A list of plain names only → **replaces** the inherited selection.
- A list containing only `+name`/`-name` → adds/removes on top of the inherited selection; the two forms cannot be mixed (mixing errors with `tool names cannot be mixed with +name or -name entries`).
- `+name`/`-name` accept only exact tool names, not `*` (`settings-manager.ts:97-108`).
- An empty array = disable all built-in tools (but does not clear extension/SDK tools).

### 3.4 `packages` entries

`PackageSourceSchema` (`settings-schema.ts:171-190`):

```jsonc
"packages": [
  "npm:pi-web-access@0.42.0",
  "npm:@juicesharp/rpiv-todo",
  { "source": "npm:pi-hud", "skills": ["-skills/release/SKILL.md"], "extensions": ["-extensions/hud.ts"] },
  { "source": "npm:pi-rewind", "autoload": false, "extensions": ["+src/index.ts"] }
]
```

- `source` prefixes: `npm:`, `git:`, `github:`, `http(s)://` (treated as git), `ssh:`, `builtin:`; bare paths/relative paths/`file:` are local.
- Filter syntax (object-form `extensions`/`skills`/`prompts`/`themes`): `[]` means load none of that type; omission means load all; `!pattern` excludes a glob; `+path` includes exactly; `-path` excludes exactly (`docs/packages.md:111-119`).
- `autoload:false`: start from empty, only explicit patterns apply (project entries can act as "user package filter increments").

### 3.5 Real sample (local; must be readable)

```json
{
  "defaultModel": "deepseek-v4.1-flash",
  "defaultProvider": "opencode-go",
  "defaultThinkingLevel": "max",
  "hideThinkingBlock": true,
  "lastChangelogVersion": "1.0.0",
  "packages": ["npm:pi-web-access@0.42.0", "npm:@juicesharp/rpiv-ask-user-question", "...13 entries..."],
  "theme": "dark",
  "compaction": { "enabled": true },
  "extensions": ["+builtin:mcp"],
  "deviceId": "08ddb45b-1f4f-468f-86b9-61b67b088e25"
}
```

## 4. auth.json / models-store.json (R+W)

### 4.1 auth.json

Path `<agent-dir>/auth.json`, `JSON.stringify(data, null, 2)`, permissions **0600** (set only at creation; existing permissions are not forcibly changed, `auth-storage.ts:36-37`), cross-process `proper-lockfile` locking (10 sync retries, async 30s cap + EPERM retry, `auth-storage.ts:81-156`).

Structure: `Record<providerId, Credential>` (`packages/ai/src/auth/types.ts:17-37`):

```jsonc
{
  "deepseek":  { "type": "api_key", "key": "sk-...", "env": { "OPTIONAL": "provider-scoped env" } },
  "opencode-go": { "type": "api_key", "key": "sk-..." },
  "xai":       { "type": "oauth", "access": "...", "refresh": "...", "expires": 1792654542271 },
  "openai":    { "type": "oauth", "access": "...", "refresh": "...", "expires": 1792026121915,
                 "clientId": "oaiapp_...", "scopes": ["chatgpt.tokens.use.direct","email","offline_access","openid","profile"] },
  "radius":    { "type": "oauth", "access": "...", "refresh": "...", "expires": 1792719580653, "scope": "gateway offline_access" }
}
```

- `api_key`: `key` may be omitted (env-only scenarios); `env` holds provider-level environment values (Cloudflare account/gateway, etc.).
- `oauth`: `access`/`refresh`/`expires` (milliseconds); other keys (`clientId`/`scopes`/`scope`/`tokenType`...) are preserved as-is, and `OAuthCredentials` allows `[key:string]:unknown`.
- When the credential file does not exist, create `{}` (`auth-storage.ts:75-79`).
- The old `~/.pi/agent/oauth.json` and `settings.json.apiKeys` are migrated into auth.json, and oauth.json is renamed to `.migrated` (`src/migrations.ts:21-73`).

**Credential resolution precedence** (`docs/models.md:23`): runtime `--api-key` > auth.json > `models.json`'s `apiKey` > provider environment variables/cloud credentials.

### 4.2 models-store.json

`FileModelsStore`: `Record<providerId, ModelsStoreEntry>` (pi-ai types), sharing the same lock implementation as auth (`models-store.ts:44-60`). It caches the dynamic model catalog at runtime; **failure to read it does not affect startup** and it can be ignored and re-fetched entirely.

---

## 5. models.json (R)

Path `<agent-dir>/models.json`. The full field set is in `docs/models-configuration.md` (auto-generated from source). Most common structure:

```jsonc
{
  "$schema": "https://pi.dev/schemas/models.schema.json",
  "providers": {
    "self-host": {
      "baseUrl": "http://47.113.218.61:28080",
      "api": "openai-completions",
      "apiKey": "sk-...",                       // also supports "$ENV" / "${ENV}" / "!command"
      "headers": { "X-Foo": "$FOO" },
      "authHeader": false,                      // true = send the key as Authorization: Bearer
      "models": [
        { "id": "Airis-AI", "name": "Airis-AI", "reasoning": true, "input": ["text"],
          "contextWindow": 262144, "maxTokens": 32768 }
      ]
    },
    "anthropic-proxy": { "baseUrl": "https://proxy.example.com" }   // only overrides baseUrl; models/auth come from the built-in provider
  }
}
```

Key fields (`docs/models-configuration.md:13-104`):

- provider: `name`, `baseUrl`, `apiKey`, `api`, `oauth:"radius"`, `headers`, `compat`, `authHeader`, `models[]`, `modelOverrides`.
- model: `id` (required), `name`, `api`, `baseUrl`, `reasoning`, `thinkingLevelMap`, `input:("text"|"image")[]` (default `["text"]`), `inputLimits`, `cost{input,output,cacheRead,cacheWrite,tiers[]}`, `promptCache{short,long}`, `contextWindow` (default 128000), `maxTokens` (default 16384), `samplingParams`, `samplingParamsByThinkingLevel`, `headers`, `compat`.
- `modelOverrides`: overrides built-in/extension models by model id; object fields (such as `cost`) merge key by key, other fields are replaced wholesale; unknown ids are ignored.
- Value resolution: `apiKey`/`headers` support `$NAME`, `${NAME}`, literals, and `!command`; `$$`/`$!` escape to literal `$`/`!`; commands execute at request time and are not cached (`docs/models.md:65`, `src/core/resolve-config-value.ts`).
- `compat` is a list of "verified differences", grouped by API (openai-completions / openai-responses / anthropic-messages / bedrock / mistral); fields are in `docs/models-configuration.md:105-187`. If the Kotlin version supports only two APIs, it only needs those two groups of fields.

A real sample is the `models.json` in the same directory as §3.5 (2 `openai-completions` providers, 9 models, with `contextWindow`/`maxTokens` given explicitly).

---

## 6. mcp.json / mcp-auth.json / mcp-cache.json (R, can be ignored)

`<agent-dir>/mcp.json` and `<cwd>/.pi/mcp.json` (project entries require trust; project entries may write only `enabled`/`exposure`/`toolExposure` to override user entries), format (`docs/mcp.md:44-83`):

```jsonc
{
  "mcpServers": {
    "context7": { "url": "https://mcp.context7.com/mcp", "headers": { "CONTEXT7_API_KEY": "..." }, "description": "..." },
    "chrome-devtools": { "command": "chrome-devtools-mcp", "args": ["--browserUrl","http://127.0.0.1:9222"], "exposure": "deferred" },
    "radius": { "url": "https://radius.pi.dev/mcp", "auth": { "provider": "radius" } }
  }
}
```

- stdio: `command`, `args`, `env`, `cwd`; HTTP: `url`, `headers`, `oauth`; `type` is optional (`stdio|http|streamable-http`, `sse` is rejected).
- Common: `timeout` (seconds, default 60), `enabled` (default true), `exposure` (default `codemode`), `toolExposure`, `description`.
- Tool names are `mcp__<server>__<tool>`; non-alphanumeric/underscore characters are replaced with `_`, and conflicts get a hash suffix (`docs/mcp.md:77`).
- `env`/`headers` values may use `${NAME}` or a whole-value `!command`.
- `mcp-auth.json`: MCP OAuth tokens, keyed by server name + URL; `mcp-cache.json`: connection/tool cache. Both can be ignored entirely.

If the Kotlin version does not implement MCP: **parse only, do not connect**, and ensure that the presence of `mcp.json` does not error or affect other startup paths.

---

## 7. trust.json (R+W)

Path `<agent-dir>/trust.json`, structure `Record<canonicalAbsPath, boolean|null>` (`src/core/trust-manager.ts:33-43`):

```json
{ "/home/user": true, "/home/user/dev/skills": true, "/home/user/project": true }
```

- Lookup: canonicalize (realpath) `cwd`, then walk upward to the nearest `true`/`false` entry; `null` means "explicitly clear inherited trust at this directory" (`trust-manager.ts:63-80`).
- Project resources requiring trust: `settings.json`, `mcp.json`, `extensions/`, `skills/`, `prompts/`, `themes/`, `SYSTEM.md`, `APPEND_SYSTEM.md` (`trust-manager.ts:43-53`).
- Writes use a file lock (`proper-lockfile`). When coexisting with real pi, **do not rewrite the whole file**; update only your own keys.
- In non-interactive mode, an untrusted project that has the above resources: do not load them by default, and emit a diagnostic (`src/core/project-trust.ts`).

---

## 8. keybindings.json (R, writing optional)

Path `<agent-dir>/keybindings.json`, structure: `Record<actionId, string | string[]>` (an empty array disables the action). Based on `docs/keybindings.md:9-32`.

```jsonc
{
  "$schema": "https://pi.dev/schemas/keybindings.schema.json",
  "app.session.new": "ctrl+shift+n",
  "app.session.tree": ["ctrl+shift+t", "alt+shift+t"],
  "tui.altScreen.pageUp": []
}
```

- Key syntax: `modifier+key`, modifier ∈ `ctrl|shift|alt|super` (combinable); key ∈ `a-z`, `0-9`, `escape|esc|enter|return|tab|space|backspace|delete|insert|clear|home|end|pageUp|pageDown|up|down|left|right`, `f1-f12`, and the symbols `` ` - = [ ] \ ; ' , . / ! @ # $ % ^ & * ( ) _ + | ~ { } : < > ? ``.
- Two groups of action ids: 43 `app.*` (`src/core/keybindings.ts:26-170`, such as `app.interrupt`=escape, `app.clear`=ctrl+c, `app.exit`=ctrl+d, `app.thinking.cycle`=shift+tab, `app.model.cycleForward`=ctrl+p, `app.model.select`=ctrl+l, `app.tools.expand`=ctrl+o, `app.thinking.toggle`=ctrl+t, `app.session.toggleNamedFilter`=ctrl+n, `app.editor.external`=ctrl+g, `app.message.copy`=ctrl+x, `app.message.followUp`=alt+enter, `app.message.dequeue`=alt+up, `app.clipboard.pasteImage`=ctrl+v...) and 94 `tui.*` (from pi-tui, `packages/tui/src/keybindings.ts`).
- Legacy format migration: `migrateKeybindingsConfig` (called at `src/migrations.ts:157-172`). If the Kotlin version does not implement a TUI, it can parse and ignore this; but it **must tolerate the file's presence**.

## 9. themes/*.json (R)

Location: `<agent-dir>/themes/<name>.json`, `.pi/themes/`, settings `themes`, packages. The built-in `dark`/`light` live in `src/modes/interactive/theme/*.json`; default is `system` (`docs/themes.md:22,38`).

```jsonc
{
  "$schema": "https://pi.dev/schemas/theme.schema.json",
  "name": "dark",
  "appearance": "dark",
  "vars": { "text": "okhsl(234 3% 89%)", "blueBg": "okhsl(233 41% 24%)" },
  "colors": { "accent": "violet", "border": "okhsl(231 57% 65%)", "text": "text", "userMessageBg": "blueBg",
              "toolPendingBg": "okhsl(229 5% 24%)", "mdHeading": "yellow", "syntaxKeyword": "blue",
              "thinkingOff": "okhsl(229 8% 49%)", "thinkingMax": "okhsl(20 99% 63%)", "bashMode": "okhsl(159 64% 65%)" },
  "export": { "pageBg": "okhsl(262 14% 16%)", "cardBg": "okhsl(264 13% 19%)", "infoBg": "okhsl(53 51% 24%)" }
}
```

- Required: `name` (unique, no `/`, not `system`) and `colors` (roles required by the schema).
- Six color forms: `#rgb`/`#rrggbb`, `oklch(L% C H)`, `okhsl(H S% L%)`, 256-color index (number), variable reference (a key in `vars`), and `""` (terminal default foreground/background). Variables can be chained; a missing or circular reference makes the theme invalid (`docs/themes.md:83-96`).
- 5 optional colors have fallbacks: `scrollbarTrack→muted`, `scrollbarThumb→text`, `searchMatchBg→selectedBg`, `searchMatchText→text`, `thinkingMax→thinkingXhigh` (`docs/themes.md:120-130`).
- When `export` is omitted it is derived from `userMessageBg`.
- Theme objects are strict: unknown top-level fields/unknown color tokens are rejected (`docs/themes.md:81`).
- If the Kotlin version is headless-only: **theme parsing is unnecessary**; but HTML export needs hex conversion of `colors`/`export`.

---

## 10. skills (R)

### 10.1 Directories and files

```
<skills-dir>/<skill-name>/SKILL.md     ← directory form (portable)
<skills-dir>/<name>.md                ← .md files directly in the root also count as skills (Pi-specific)
```

Discovery rules (`src/core/skills.ts:168-275`):

1. On entering a directory, if it contains `SKILL.md` → that directory is a skill root and is **not descended into** (returns one skill).
2. Otherwise: recurse into subdirectories looking for `SKILL.md`; at the same time, when `includeRootFiles` (true on the initial call), `.md` files directly in that directory count as skills.
3. Skip entries starting with `.` and `node_modules`; follow symlinks (skip broken links).
4. Ignore files: `.gitignore`/`.ignore`/`.fdignore` are layered in during the scan (`skills.ts:16,47-65`).
5. By default scan `<agent-dir>/skills` (source=user) and `<cwd>/.pi/skills` (project), plus `~/.agents/skills` and ancestor `.agents/skills` of trusted projects; then merge settings `skills`, `--skill`, and package resources (`package-manager.ts:2464-2570`).
6. Name conflicts: keep the first found and emit a collision diagnostic (`skills.ts:426-455`). The same file appearing repeatedly through symlinks is silently deduplicated.

### 10.2 SKILL.md format

```markdown
---
name: pdf-tools
description: Extract text and tables from PDF files. Use when reading, converting, or inspecting PDFs.
disable-model-invocation: true
---

# Body
...
```

Frontmatter fields (`src/core/skills.ts:67-72`):

| Field | Rule |
|---|---|
| `name` | Defaults to the parent directory name; must match `^[a-z0-9-]+$`, not start/end with `-`, contain no consecutive `--`, and be ≤64 characters (validation failure only warns and still loads, `skills.ts:92-112`) |
| `description` | **Required** and non-empty, ≤1024 characters (an empty value means the skill is not loaded, `skills.ts:304-332`) |
| `disable-model-invocation` | When `true`, it does not enter the system prompt and can only be invoked via `/skill:<name>` |
| Others | `license`/`compatibility`/`metadata`/`allowed-tools`, etc. are preserved as-is and not interpreted |

Frontmatter parser: `utils/frontmatter.ts` (YAML, supports `---` wrapping).

### 10.3 Format in the system prompt (W, must match verbatim)

`formatSkillsForPrompt` (`skills.ts:358-388`):

```text
The following skills provide specialized instructions for specific tasks.
Use the read tool to load a skill's file when the task matches its description.
When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.

<available_skills>
  <skill>
    <name>pdf-tools</name>
    <description>...</description>
    <location>/abs/path/pdf-tools/SKILL.md</location>
  </skill>
</available_skills>
```

- This block becomes the `skills` section and is wrapped in `<skills>...</skills>` (`system-prompt.ts:174-182`).
- Skills with `disableModelInvocation:true` are filtered out.
- If the `read` tool is hidden (prepareLoadout), the wording becomes `Load a skill's file when...` without naming the tool (`system-prompt.ts:174-178`).
- Message text after the user enters `/skill:name args` is expanded (`parseSkillBlock` at `src/core/agent-session.ts:166-188`):

```text
<skill name="pdf-tools" location="/abs/path/SKILL.md">
<full SKILL.md body>
</skill>

<args as the user request>
```

---

## 11. prompt templates (R)

Location: `<agent-dir>/prompts/*.md`, `<cwd>/.pi/prompts/*.md` (**only direct child files are loaded, no recursion**), settings `prompts`, packages. The filename (without `.md`) is the command name, e.g. `review.md` → `/review` (`src/core/prompt-templates.ts:134`).

```markdown
---
description: Review staged git changes
argument-hint: "[focus]"
---
Review the staged changes. Focus on ${1:-correctness, security, and error handling}.
```

- When `description` is omitted, take the first non-empty body line, truncated to 60 characters + `...` (`prompt-templates.ts:136-145`).
- `argument-hint` convention: `<required>`, `[optional]`.

Argument expansion (`prompt-templates.ts:76-108`, after shell-style tokenization `parseCommandArgs` 25-61):

| Syntax | Result |
|---|---|
| `$1` `$2` … | The Nth positional argument (missing → empty string) |
| `$@` / `$ARGUMENTS` | All arguments joined with spaces |
| `${N:-default}` | The Nth argument, or default when empty/missing |
| `${@:-default}` / `${ARGUMENTS:-default}` | All arguments, or default when empty |
| `${@:N}` | From the Nth onward (1-indexed; 0 is treated as 1) |
| `${@:N:L}` | L arguments starting at the Nth |

Substitution applies only to the template text; arguments/default values are not recursively substituted (`prompt-templates.ts:72-74`). Quotes: `"..."` and `'...'` preserve spaces; empty quotes `""` are a valid empty argument.

---

## 12. Context files / system prompt files (R)

### 12.1 Context files (AGENTS/CLAUDE)

Candidate filename order (`resource-loader.ts:184-199`):

```
AGENTS.override.md, AGENTS.md, AGENTS.MD, CLAUDE.md, CLAUDE.MD
```

The first hit in a directory wins. Load order (`loadProjectContextFiles`, 232-275):

1. The one in `<agent-dir>` (global).
2. One per directory from `cwd` up to the root; ordered **farthest to nearest**.
3. Deduplicate by absolute path; shadow files from nested git worktrees are not loaded twice (`findShadowedContextFile`, 214-231).
4. Project trust is not required (`docs/configuration.md:47`).
5. Disabled by `--no-context-files` / `-nc`.

Format injected into the system prompt (`system-prompt.ts:79-86`):

```text
Project-specific instructions and guidelines:

<project_instructions path="/abs/AGENTS.md">
<file contents>
</project_instructions>
```

Multiple files are separated by blank lines; the whole block is wrapped in the `<project_context>...</project_context>` section.

### 12.2 SYSTEM.md / APPEND_SYSTEM.md

- Location: `<agent-dir>/{SYSTEM,APPEND_SYSTEM}.md` or `<cwd>/.pi/{SYSTEM,APPEND_SYSTEM}.md` (project requires trust).
- Same names do not merge; the project file **wins** (`docs/configuration.md:39`).
- `SYSTEM.md` → replaces `preamble` (`buildSystemPromptOptions.customPrompt`).
- `APPEND_SYSTEM.md` → appended as the `addendum` section (`system-prompt.ts:172`).
- `--system-prompt` / `--append-system-prompt` can also take text or a file path (`cli/args.ts:121-125`; `resource-loader.ts:167-182` decides "text or existing file").

### 12.3 System prompt section generation order (W)

`buildSystemPromptSections` (`system-prompt.ts:128-193`):

```
preamble            (no tag; customPrompt or the built-in sentence)
tools               <tools>...</tools>           only when not customPrompt
rules               <rules>...</rules>           only when not customPrompt
docs                <docs>...</docs>             only when not customPrompt
addendum            <addendum>...</addendum>     APPEND_SYSTEM
project_context     <project_context>...</project_context>
skills              <skills>...</skills>
cwd                 <cwd>...</cwd>               (backslashes converted to /)
<custom section>     <name>...</name>             passed in by extensions
```

The rules section always ends with two fixed entries: `Be concise in your responses`, `Show file paths clearly when working with files` (`system-prompt.ts:122-123`); tool guidelines are deduplicated and concatenated in tool selection order.

## 13. Extension manifest and API (R; compatibility depends on strategy)

### 13.1 Package manifest (the `pi` key in `package.json`)

```jsonc
{
  "name": "my-pi-package",
  "keywords": ["pi-package"],
  "pi": {
    "extensions": ["./src/extension.ts"],
    "skills": ["./resources/skills"],
    "prompts": ["./resources/prompts/*.md"],
    "themes": ["./resources/themes/*.json"],
    "image": "https://.../preview.png",
    "video": "https://.../demo.mp4"
  },
  "peerDependencies": {
    "@earendil-works/pi-ai": "*", "@earendil-works/pi-agent-core": "*",
    "@earendil-works/pi-coding-agent": "*", "@earendil-works/pi-tui": "*", "typebox": "*"
  }
}
```

Convention: without a `pi` manifest, discover by the conventional `extensions/`, `skills/`, `prompts/`, `themes/` directories (`docs/packages.md:44-74`). Host-provided packages must go in `peerDependencies` with `"*"`, not `dependencies` (otherwise a warning, `resource-loader.ts:53-98`).

### 13.2 Extension file shapes

- A single `.ts`/`.js` file or a directory (`index.ts`/`index.js`/`package.json` main).
- Export a default factory: `export default function (pi: ExtensionAPI) {...}`, which may be async (`docs/extensions.md:15-38`).
- Lifecycle: create resources in `session_start`, release idempotently in `session_shutdown`; do not start processes/timers in the factory (`docs/extensions.md:56-60`).
- Loading: jiti transpiles in-process, and host packages are mapped through virtual modules (`core/extensions/virtual-modules.ts`).

### 13.3 ExtensionAPI (summary; full signatures in `core/extensions/types.ts:1592-1930`)

Events (`on`): `project_trust`, `resources_discover`, `session_start`, `session_info_changed`, `session_before_switch`, `session_before_fork`, `session_before_compact`, `session_compact`, `session_compact_failed`, `session_shutdown`, `mcp_servers_change`, `session_before_tree`, `session_tree`, `context`, `context_with_system`, `cache_warming_decision`, `before_provider_request`, `before_provider_headers`, `after_provider_response`, `provider_stream_event`, `before_agent_start`, `agent_start`, `agent_end`, `agent_before_settle`, `agent_settled`, `ui_prompt_start`, `ui_prompt_end`, `turn_start`, `turn_end`, `message_start`, `message_update`, `message_end`, `tool_execution_start`, `tool_execution_update`, `tool_execution_end`, `model_select`, `thinking_level_select`, `tool_call`, `tool_result`, `user_bash`, `input`.

Registration/operations: `registerTool`, `registerCommand`, `registerShortcut`, `registerFlag`, `getFlag`, `registerMessageRenderer`, `registerMarkdownTransformer`, `registerEntryRenderer`, `registerToolRenderer`, `sendMessage`, `sendUserMessage`, `appendEntry`, `setSessionName`/`getSessionName`, `setLabel`, `exec`, `getActiveTools`/`setActiveTools`/`getAllTools`, `getSettings`, `getCommands`, `setModel`, `getThinkingLevel`/`setThinkingLevel`, `registerProvider`, `unregisterProvider`, `registerMcpServer`/`unregisterMcpServer`/`getMcpServers`, `registerVirtualModel`/`unregisterVirtualModel`.

Tool definition (`types.ts:579-670`): `{name,label,description,parameters(JSON Schema),outputSchema?,promptSnippet?,promptGuidelines?,exposure?,namespace?,annotations?,execute(id,args,signal,onUpdate,ctx),renderCall?,renderResult?,prepareArguments?,prepareLoadout?,reachesTools?,constrainedSampling?,renderShell?}`.

Extension UI protocol (under RPC): see §14.5.

### 13.4 If TS compatibility is not implemented

Only guarantee: reading the 13 npm package declarations **does not crash, does not accidentally delete settings, and can list them as "unsupported"**; `.ts` files in the extension directory are not parsed as configuration.

---

## 14. RPC protocol (P, field by field)

### 14.1 Framing

- Strict JSONL: one record = one JSON object + `\n`.
- Splitting: **LF only**; CRLF input tolerates stripping a trailing `\r`; **never** treat U+2028/U+2029 as separators (`docs/rpc.md:50-56`, `src/modes/rpc/jsonl.ts:1-58`).
- The reader must handle "a single read stopping in the middle of a multi-byte character" (stateful UTF-8 decoder).
- stdout carries protocol only; all diagnostics/logs go to stderr (`takeOverStdout`, `src/core/output-guard.ts:51-70`).
- Backpressure must be handled: a client that does not read will block (Pi's `waitForRawStdoutBackpressure`).

### 14.2 Commands (stdin)

All commands accept an optional `id` (string), echoed back in the response (`rpc-types.ts:20-74`):

```jsonc
{"id":"req-1","type":"prompt","message":"hi","images":[{"type":"image","data":"<b64>","mimeType":"image/png"}],"streamingBehavior":"steer"|"followUp"}
{"type":"steer","message":"...","images":[...]}
{"type":"follow_up","message":"...","images":[...]}
{"type":"abort"}
{"type":"clear_queue"}
{"type":"new_session","parentSession":"/path/to/parent.jsonl"}
{"type":"get_state"}
{"type":"set_model","provider":"anthropic","modelId":"..."}
{"type":"cycle_model"}
{"type":"get_available_models"}
{"type":"set_thinking_level","level":"high"}
{"type":"cycle_thinking_level"}
{"type":"get_available_thinking_levels"}
{"type":"set_steering_mode","mode":"all"|"one-at-a-time"}
{"type":"set_follow_up_mode","mode":"all"|"one-at-a-time"}
{"type":"compact","customInstructions":"focus on ..."}
{"type":"set_auto_compaction","enabled":true}
{"type":"set_auto_retry","enabled":true}
{"type":"abort_retry"}
{"type":"bash","command":"ls -la","excludeFromContext":false}
{"type":"abort_bash"}
{"type":"get_session_stats"}
{"type":"export_html","outputPath":"/tmp/x.html"}
{"type":"switch_session","sessionPath":"/path/s.jsonl"}
{"type":"fork","entryId":"abc123"}
{"type":"clone"}
{"type":"get_fork_messages"}
{"type":"get_entries","since":"abc123"}
{"type":"get_tree"}
{"type":"get_last_assistant_text"}
{"type":"set_session_name","name":"my-feature"}
{"type":"get_messages"}
{"type":"get_commands"}
```

`prompt` constraints:
- While streaming, `streamingBehavior` is required, otherwise a failure response.
- `@file` is not supported; use `prompt`'s `images`/body instead.
- Input expansion: `/skill:name` and `/template` are expanded before enqueueing/sending; extension commands (`/cmd`) execute immediately and return `disposition:"handled"` (`docs/rpc-commands.md:31-34`).
- `data.disposition` ∈ `"handled" | "queued" | "started"`, meaning "consumed by an extension/input handler", "queued while running", and "accepted and started a run" respectively (`docs/rpc-commands.md:40`).

### 14.3 Responses (stdout)

Success:

```jsonc
{"id":"req-1","type":"response","command":"prompt","success":true,"data":{"disposition":"started"}}
{"id":"req-2","type":"response","command":"abort","success":true}
```

Failure:

```jsonc
{"id":"req-3","type":"response","command":"set_model","success":false,"error":"Model not found: invalid/model"}
{"type":"response","command":"parse","success":false,"error":"Failed to parse command: ..."}   // no id
```

Data shapes per command are in `rpc-types.ts:116-245`; key ones:

- `get_state`: `{model?, thinkingLevel, isStreaming, isCompacting, steeringMode, followUpMode, sessionFile?, sessionId, sessionName?, autoCompactionEnabled, messageCount, pendingMessageCount}` (`rpc-types.ts:96-109`).
- `get_entries`: `{entries: SessionEntry[], leafId: string|null}`; fails when `since` does not exist.
- `get_tree`: `{tree: [{entry, children, label?, labelTimestamp?}], leafId}`.
- `get_session_stats`: `{sessionFile, sessionId, userMessages, assistantMessages, toolCalls, toolResults, totalMessages, tokens:{input,output,cacheRead,cacheWrite,total}, cost, contextUsage?:{tokens,contextWindow,percent}}` (`docs/rpc-commands.md:534-567`).
- `compact`: `{summary, firstKeptEntryId, tokensBefore, estimatedTokensAfter, usage?, details}`.
- `bash`: `{output, exitCode, cancelled, truncated, fullOutputPath?}`; streaming `bash_execution_update{id?,delta}`.
- `get_commands`: `{commands:[{name, description?, source:"extension"|"prompt"|"skill", sourceInfo:{path,source,scope,origin,baseDir?}}]}`.

### 14.4 Events (stdout)

The same event set as json mode (§15.2), except: RPC does **not** emit a session header (use `get_state`). RPC-only: `bash_execution_update`, `extension_error` (`{extensionPath,event,error}`, `docs/json.md:190-194`).

### 14.5 Extension UI subprotocol

Pi → client (`rpc-types.ts:252-287`):

```jsonc
{"type":"extension_ui_request","id":"uuid","method":"select","title":"...","options":["Allow","Block"],"timeout":10000}
{"type":"extension_ui_request","id":"uuid","method":"confirm","title":"...","message":"...","timeout":5000}
{"type":"extension_ui_request","id":"uuid","method":"input","title":"...","placeholder":"...","timeout":...}
{"type":"extension_ui_request","id":"uuid","method":"editor","title":"...","prefill":"..."}
{"type":"extension_ui_request","id":"uuid","method":"notify","message":"...","notifyType":"info"|"warning"|"error"}
{"type":"extension_ui_request","id":"uuid","method":"setStatus","statusKey":"...","statusText":"..."|undefined}
{"type":"extension_ui_request","id":"uuid","method":"setWidget","widgetKey":"...","widgetLines":[...]|undefined,"widgetPlacement":"aboveEditor"|"belowEditor"}
{"type":"extension_ui_request","id":"uuid","method":"setTitle","title":"..."}
{"type":"extension_ui_request","id":"uuid","method":"set_editor_text","text":"..."}
```

Client → Pi (only dialogs need a reply, `rpc-types.ts:294-297`):

```jsonc
{"type":"extension_ui_response","id":"uuid","value":"Allow"}
{"type":"extension_ui_response","id":"uuid","confirmed":true}
{"type":"extension_ui_response","id":"uuid","cancelled":true}
```

- Dialog timeouts are resolved automatically by Pi with default values; the client does not need its own timer (`docs/rpc-extension-ui.md:10`).
- Fire-and-forget methods (notify/setStatus/setWidget/setTitle/set_editor_text) do not expect a response.
- The degradation list (`custom()` returning undefined, `onTerminalInput` no-op, empty theme-related implementations, etc.) is in `docs/rpc-extension-ui.md:12-25`.
- `ctx.mode === "rpc"` and `hasUI === true`; only `ctx.mode === "tui"` may use `custom()`.

### 14.6 Lifecycle and shutdown

- `prompt`'s `success` only means "accepted", not completed; only `agent_settled` (or `agent_end` plus your own determination) means idle.
- The client should subscribe to events before sending a prompt, to avoid missing a fast completion.
- Shutdown: the client closes stdin → Pi disposes the runtime and exits; an extension can also call `ctx.shutdown()` (executed after a command or after `agent_settled`).
- The client must also handle: subprocess startup failure, unexpected exit, stderr diagnostics, cancellation, and its own timeouts; **do not parse stderr as protocol**.

---

## 15. json mode output (P)

### 15.1 Structure

```
line 1: SessionHeader (exactly as in §2.3, but only a snapshot of this run's session)
line 2..n: events, one per line
```

RPC does not emit a header. Writes work the same as RPC (raw stdout + backpressure), and diagnostics go to stderr (`src/modes/print-mode.ts:108-127`).

### 15.2 Full event set and fields

Base events (`docs/json.md:52-68`):

| Event | Fields |
|---|---|
| `agent_start` | none |
| `agent_end` | `messages`, `willRetry` |
| `agent_settled` | `aborted` |
| `turn_start` | none |
| `turn_end` | `message`, `toolResults` |
| `message_start` | `message` |
| `message_update` | `usage`, `assistantMessageEvent` (see §15.3) |
| `message_end` | `message` (authoritative final value) |
| `tool_execution_start` | `toolCallId`, `toolName`, `args` |
| `tool_execution_update` | `toolCallId`, `toolName`, `args`, `partialResult` |
| `tool_execution_end` | `toolCallId`, `toolName`, `result`, `isError`, `durationMs?` |

Queue/state: `queue_update{steering,followUp}` (the full current queues), `entry_appended{entry}`, `session_info_changed{name?}`, `thinking_level_changed{level}`.

Compaction: `compaction_start{reason:"manual"|"threshold"|"overflow"}`; `compaction_end{reason,result?,aborted,willRetry,errorMessage?}`, with `result={summary,firstKeptEntryId,tokensBefore,estimatedTokensAfter,usage?,details}`.

Retries: `auto_retry_start{attempt,maxAttempts,delayMs,errorMessage}`, `auto_retry_end{success,attempt,finalError?}`; `summarization_retry_scheduled{attempt,maxAttempts,delayMs,errorMessage}`, `summarization_retry_attempt_start{source:"compaction",reason}` or `{source:"branchSummary"}`, `summarization_retry_finished`.

### 15.3 Wire compaction of `message_update` (must be followed)

SDK-internal events carry accumulated snapshots; for JSON/RPC output (`src/modes/json-event.ts:20-61`):

1. Delete the top-level `message` field; keep only `type:"message_update"`, `usage` (the latest cumulative usage for that assistant response), and `assistantMessageEvent`.
2. Delete the `partial` field from every `assistantMessageEvent`.
3. Add `id` and `toolName` to `toolcall_start` (taken from `partial.content[contentIndex]`).
4. `start`/`done`/`error` are usually converted by the agent loop into `message_start`/`message_end`, but are still allowed by type inside `message_update`.

Inner event types (`docs/json.md:74-90`): `start`, `text_start{contentIndex}`, `text_delta{contentIndex,delta}`, `text_end{contentIndex,content}`, `thinking_start`, `thinking_delta`, `thinking_end`, `toolcall_start{contentIndex,id,toolName}`, `toolcall_delta{contentIndex,delta}`, `toolcall_end{contentIndex,toolCall}`, `done{reason,message}`, `error{reason,error}`.

Rebuild rules: deltas are for live display only; content from `text_end`/`thinking_end`/`toolcall_end` overrides; the final authority is `message_end.message`. `usage` may stay 0 throughout streaming (providers that do not report it).

### 15.4 Example sequence

```json
{"type":"agent_start"}
{"type":"turn_start"}
{"type":"message_start","message":{"role":"user","content":"Review this repository","timestamp":1733234401000}}
{"type":"message_end","message":{"role":"user","content":"Review this repository","timestamp":1733234401000}}
{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending","...":"..."}}
{"type":"message_update","usage":{"input":100,"output":1,"cacheRead":0,"cacheWrite":0,"totalTokens":101,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hello "}}
{"type":"message_end","message":{"role":"assistant","...":"..."}}
{"type":"turn_end","message":{"role":"assistant","...":"..."},"toolResults":[]}
{"type":"agent_end","messages":[...],"willRetry":false}
{"type":"agent_settled","aborted":false}
```

---

## 16. print mode output (P)

- stdout: only the text blocks of the last assistant message, **with a `\n` after each text block** (`src/modes/print-mode.ts:149-153`); nothing else.
- stderr: `stopReason==="error"` → `errorMessage` or `Request error`; `aborted` → likewise; `length` → `Response was truncated: the model reached its output token limit.` (print-mode.ts:145-157).
- Exit codes: `length`/`error`/`aborted` → 1, otherwise 0; a caught exception → 1 with the message on stderr (print-mode.ts:163-165).
- Initial message composition (`src/cli/initial-message.ts:22-43`): `stdin content` + `@file text` + `messages[0]` concatenated directly (no separators), with the remaining messages used as subsequent prompts in order. `@` images go to `initialImages`.
- stdout takeover: in non-interactive modes all `console.log` is redirected to stderr (`output-guard.ts:51-70`); the Kotlin equivalent = always write ordinary logs to stderr.

## 17. Internal text protocol (W; enters session files and is read/rendered by real pi)

### 17.1 compaction summary message

`convertToLlm` converts a `compactionSummary` message into user text (`core/messages.ts:11-24,176-183`):

```text
The conversation history before this point was compacted into the following summary:

<summary>
<summary text>
</summary>
```

### 17.2 branch summary message

`core/messages.ts:19-24,170-175`:

```text
The following is a summary of a branch that this conversation came back from:

<summary>
<summary text>
</summary>
```

### 17.3 bash result converted to user text

`core/messages.ts:82-98`:

````text
Ran `<command>`
```
<output>
```
````

No output → `(no output)`; cancelled → append `\n\n(command cancelled)`; non-zero → `\n\nCommand exited with code N`; truncated → `\n\n[Output truncated. Full output: <path>]`.

### 17.4 Compaction summary generation prompts (W, if summaries are shared across implementations)

- First time: `SUMMARIZATION_PROMPT` (`core/compaction/compaction.ts:507-538`), fixed format `## Goal` / `## Constraints & Preferences` / `## Progress(Done/In Progress/Blocked)` / `## Key Decisions` / `## Next Steps` / `## Critical Context`.
- Second compaction: `UPDATE_SUMMARIZATION_PROMPT` (540-583), placing the old summary in `<previous-summary>` and requiring existing information to be preserved.
- The file list is appended by `formatFileOperations` as `<read-files>` / `<modified-files>` blocks (`core/compaction/utils.ts`).
- Serialization: `[User]:`, `[Assistant]:`, `[Assistant tool calls]:`, `[Tool result]:`; thinking is dropped; tool results are truncated to 2000 characters (`docs/compaction.md:276-291`).

### 17.5 The docs section in the system prompt (W)

The content is a fixed text + four absolute paths (`getReadmePath`/`getDocsPath`/`getExamplesPath`/`getPackageDir`), listing "which docs to read when asking pi about itself" (`src/core/system-prompt.ts:162-169`). If the Kotlin version no longer ships docs/examples, **this section must be rewritten or removed**, otherwise the model is pointed at nonexistent paths.

---

## 18. Verification checklist (Kotlin compatibility acceptance)

Execute in order; only when all pass does it count as "format compatible":

1. **Read**: start with the real local `~/.pi/agent/{settings,auth,models,mcp,trust}.json`; no exceptions, sensible diagnostics.
2. **Read sessions**: randomly pick 50 `~/.pi/agent/sessions/**/*.jsonl` (v3 and possibly historical versions), `buildSessionContext` throws no exception, model/thinking are restored correctly, and `messageCount` matches `grep -c '"type":"message"'` (note that compaction trims context but does not change the count).
3. **Write sessions**: after a new session finishes one turn, open it with real pi `pi --session <file>` and continue asking successfully; the `--mode json` header is field-for-field equal to the first line Kotlin writes (except id/timestamps).
4. **Write settings**: after changing `defaultModel`, unmodified fields in the file (such as `packages`, `deviceId`, unknown extension fields) are still there; real pi can read it.
5. **auth compatibility**: both `api_key` and `oauth` parse; after writing, permissions are `600`; `/login` results from real pi are readable by Kotlin and vice versa.
6. **Model resolution**: `--model deepseek`, `--model opencode-go/deepseek-v4.1-flash:max`, and `--models "opencode-go/*"` all resolve to the expected models; models without credentials are excluded.
7. **RPC protocol**: punch through all 33 commands generated/hand-written from `rpc-types.ts`; success/failure response fields and `command` values are correct; the `get_entries?since=` cursor works; the client receives the full delta stream before `agent_settled`.
8. **Event compaction**: with the same mock provider, Kotlin's `--mode json` event sequence matches real pi after removing `timestamp`/`id`/`durationMs`.
9. **Tool output text**: `read` (truncation notice, offset out of range, first line over limit), `edit` (multi-edit/CRLF/BOM/overlap error), `bash` (non-zero exit/timeout/abort), `grep` (limit/context/long-line truncation), `find` (`**/` semantics), `ls` (directory slashes/sorting) match real pi at string level.
10. **Compaction**: construct an over-threshold session by hand, trigger one automatic compaction and one manual `/compact`; `firstKeptEntryId`/`tokensBefore` are sensible; reopening in real pi and continuing does not scramble the order.
11. **skills/prompts**: all 6 local skills are discovered (including symlinks), `/skill:name` expansion text matches §10.3; an empty `prompts/` does not error.
12. **Unsupported resources**: the 13 packages under `~/.pi/agent/npm/`, the 4 servers in `mcp.json`, and the 146KB `models-store.json` are parsed but not executed, and startup time is not noticeably affected.

---

## 19. Appendix: string constants that must align with real pi

It is recommended to create a constants file and copy these verbatim (they are spliced into prompts or used as text output, and model behavior depends on the wording):

| Constant | Location |
|---|---|
| Default preamble | `src/core/system-prompt.ts:155-156` |
| Full docs section text | `system-prompt.ts:162-169` |
| The two default rules endings | `system-prompt.ts:122-123` |
| Skills section lead-in and XML | `src/core/skills.ts:365-387` |
| Each tool's description / promptSnippet / promptGuidelines | `core/tools/*.ts` (read.ts:20-23,96; bash.ts:45-48,255; edit.ts:43-51,151-152; write.ts:16-19,52-53; grep.ts:35-38,78; find.ts:34-37,78; ls.ts:16-19,62) |
| Truncation notice text | `read.ts:182-199`, `bash.ts:338-356`, `grep.ts:286-303`, `find.ts:281-293`, `ls.ts:145-155` |
| Compaction summary prefix/suffix | `core/messages.ts:11-24` |
| bash-to-user text | `core/messages.ts:82-98` |
| Compaction/update summary prompts | `core/compaction/compaction.ts:507-583` |
| Error text for unsupported models/credentials | `core/auth-guidance.ts`, `agent-session.ts:2138-2155` |
| RPC unknown-command/parse-failure text | `modes/rpc/rpc-mode.ts:786-810` |

