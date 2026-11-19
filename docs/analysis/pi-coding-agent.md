# pi-coding-agent source analysis and Kotlin rewrite scope recommendations

Analysis target: `upstream pi checkout: packages/coding-agent` (read-only), version 1.3.0.
Companion doc: `docs/analysis/pi-coding-agent-formats.md` (the exact formats needed for compatibility with existing pi data/protocols).

Every conclusion below cites `file:line` evidence (paths relative to `packages/coding-agent/`). Source size was measured with `find src -name '*.ts' | xargs wc -l`: **76,051 lines of TypeScript**, plus `docs/` with **7,647 lines** of specification docs and 4 JSON Schema files in `schemas/`.

---

## 1. Summary of conclusions

1. **coding-agent is not "an agent" but the product layer above one.** The actual agent loop lives in `pi-agent-core` (2,539 lines), models/providers/streaming protocols in `pi-ai` (27,709 lines), and the terminal framework in `pi-tui` (19,623 lines). coding-agent's own 76k lines = session orchestration + tools + session tree/compaction + resource system (extensions/skills/prompts/themes/packages) + four run modes + settings/auth/model resolution.
2. **Size distribution is extremely uneven**: `modes/interactive` plus its components and theme total **21,882 lines (28.8%)**, all terminal UI; `core/extensions` + `extensions/*` total **12,620 lines (16.6%)**, the in-process TS extension system (jiti loads TS directly). These two are the bulk of the "non-portable cost".
3. **The irreplaceable core is about 20-22k lines** (including 1,007 lines of trimmable renderers): AgentSession (4,449), SessionManager (2,013), SettingsManager (1,443), model runtime/resolution/provider composition (2,465), tools (3,639 + renderers 1,007), system prompt/resource loading (1,516+1,287), compaction (1,654), and the three headless modes RPC/print/json (2,047).
4. **Of the four run modes, three (print/json/rpc) are machine-verifiable protocol contracts, while interactive is a human experience.** RPC mode (`src/modes/rpc/rpc-mode.ts`, 819 lines + `rpc-types.ts` 303 lines) is the only interface with a complete protocol definition, driveable from another language, and an existing minimal Python client example (`docs/rpc.md:97-130`). This is the most cost-effective compatibility boundary for a Kotlin rewrite.
5. **The biggest risk is ecosystem breakage, not technology**: the local `~/.pi/agent/settings.json` currently declares 13 npm extension packages (pi-subagents, pi-memory, pi-chrome, pi-mcp-adapter, pi-intercom, pi-rewind, pi-hud, pi-tool-display, pi-background-tasks, pi-web-access, rpiv-ask-user-question, rpiv-todo, rpiv-btw), `~/.pi/agent/skills` has 6 skill directories, and `mcp.json` configures 4 MCP servers. Any Kotlin version that "does not support TS extensions" is a functional regression when used as a daily replacement. **Position the Kotlin version as a headless kernel first (able to coexist), not as an immediate replacement for interactive pi.**
6. **Recommended path**: do option A first (headless kernel: CLI + print/json/rpc + tools + sessions + settings/auth/models + compaction + system prompt/context files/skills/prompt templates), about **15-20k lines of Kotlin** (excluding the provider layer; about 18-25k including a minimal provider layer); after validation, do option B (+ a minimal interactive TUI, +4-6k with JLine3, +6-9k hand-written). Defer option C (extension compatibility), and first make an explicit tradeoff between "Kotlin SPI (ecosystem-incompatible)" and "Node sidecar (keeps the Node dependency)".

### 1.1 Relationship to the current repository state

At the time of writing, the repository is a Gradle multi-module skeleton (`settings.gradle.kts`: `pi-ai`, `pi-agent`, `pi-coding-agent`); `pi-ai` has types/messages/event streams/model catalog landed, `pi-agent` has not started, and `pi-coding-agent` has only a placeholder `Main.kt`. The two "TBD" items in `docs/ARCHITECTURE.md` (interactive TUI approach, extension mechanism) are exactly what §11 and §14 of this document answer:

| ARCHITECTURE.md TBD item | Conclusion here | Section |
|---|---|---|
| Interactive TUI: build with JLine3 / skip it and do only print/json/rpc | Skip it first (option A); after A passes acceptance, build a minimal set with JLine3 (option B); no fullscreen/mouse/inline images | §11, §14.2 |
| Extension mechanism: redesign an SPI or embed a JS engine | Defer both (option C); first guarantee "reading extension resources does not crash and config is not accidentally deleted"; if daily pi must be replaced, choose a Node sidecar rather than SPI | §7, §14.2 C1/C2 |
| Dependency direction `pi-coding-agent → pi-agent → pi-ai` | Consistent with this analysis: coding-agent's dependency surface on the layers below is thin (§13.1, §13.2); the main work is the product layer | §13 |
| OkHttp / kotlinx.serialization / coroutines choices | Consistent with the JVM replacements recommended here; `java.net.http` is a fallback only when OkHttp is undesirable | §12 |

Therefore the **first deliverable of the `pi-coding-agent` module should be option A** (headless kernel + RPC protocol), not an interface shell or TUI.

---

## 2. Package structure and dependencies

### 2.1 External dependencies

`package.json:40-60` declares the runtime dependencies:

| Dependency | Purpose | Direct references in src |
|---|---|---|
| `@earendil-works/pi-agent-core` | Agent loop, AgentEvent/AgentMessage/ThinkingLevel | `Agent`, `setDefaultStreamFn` (`src/core/sdk.ts:1-3`, `agent-session.ts:33`) |
| `@earendil-works/pi-ai` | Providers/models/message types/streaming/retries/OAuth/cost | 58 imports, see §13 |
| `@earendil-works/pi-tui` | Terminal framework, components, themes, keybindings | 90 imports, almost all in `modes/interactive/` |
| `@earendil-works/pi-mcp` | MCP client | 4 uses in `extensions/mcp/` |
| `@earendil-works/pi-codemode` | QuickJS script sandbox | 4 uses in `extensions/codemode/` |
| `jiti` | Load TS extensions in-process | `core/extensions/loader.ts:49-56`, `jiti-loader.ts` |
| `typebox` | JSON Schema definitions (tool args, settings schema) | 19 uses, `core/tools/*.ts`, `settings-schema.ts` |
| `proper-lockfile` | Cross-process locks for auth.json/settings.json/trust.json | `auth-storage.ts:9`, `settings-manager.ts`, `trust-manager.ts` |
| `undici` | HTTP dispatcher (proxy/timeouts/connection pool) | `core/http-dispatcher.ts` |
| `minimatch` / `ignore` | Package resource globs, .gitignore semantics | `package-manager.ts`, `skills.ts:2` |
| `diff` | edit tool diff/unified patch | `core/tools/edit-diff.ts` |
| `yaml` | frontmatter parsing | `utils/frontmatter.ts` |
| `semver` / `hosted-git-info` | Package versions/git repo resolution | `package-manager.ts`, `utils/git.ts` |
| `quickjs-wasi` | codemode WASM | `bun/runtime-setup.ts` |
| `@silvia-odwyer/photon-node` | Image resize/EXIF | `utils/photon.ts`, `image-resize-core.ts` |
| `chalk` | Terminal coloring (including headless diagnostic output) | `main.ts:11`, `args.ts`, etc. |
| `highlight.js` / `grok-mermaid` | Syntax highlighting / mermaid rendering (TUI only) | `utils/syntax-highlight.ts`, `components/mermaid.ts` |
| `cross-spawn` | Cross-platform subprocesses | `utils/child-process.ts` |

Node built-in module usage frequency (`grep -rhoP 'from "(node:)?[a-z_/]+"'`): `node:path` 38, `node:fs` 34, `node:os` 15, `node:fs/promises` 13, `node:crypto` 9, `node:child_process` 9, `node:url` 5, `node:stream` 3, `node:readline` 3, `node:worker_threads` 2, `node:module` 2, `node:events` 2, `node:zlib` 1, `node:string_decoder` 1.

### 2.2 Directory sizes (src/)

| Directory | Lines | Notes |
|---|---|---|
| `src/core/` | 23,390 | Business core: sessions, tools, settings, models, resources, compaction, extension runtime, package management |
| `src/modes/interactive/components/` | 11,661 | 47 TUI components |
| `src/modes/interactive/` (excluding components/theme) | 7,960 | Interactive mode controller (`interactive-mode.ts` 7,082), renderer, share, bug-report |
| `src/core/extensions/` | 5,049 | Extension types 2,304 + runner 1,565 + loader 879 + index 225 + jiti loaders + virtual modules |
| `src/extensions/mcp/` | 4,258 | Built-in MCP extension (client, OAuth, resources, CLI, TUI) |
| `src/utils/` | 3,814 | Shell/subprocess/path/image/clipboard/git/compression |
| `src/core/tools/` | 3,639 | 8 tools + truncate + diff + queue + accumulator |
| `src/` root | 3,629 | main.ts 1,000 + package-manager-cli.ts 1,142 + config.ts 656 + index.ts 497 + migrations.ts 315 |
| `src/modes/interactive/theme/` | 2,261 | theme.ts 1,086 + system-theme.ts 655 + controller/tokens/schema + dark/light.json |
| `src/modes/rpc/` | 1,797 | rpc-mode 819 + rpc-client 617 + rpc-types 303 + jsonl 58 |
| `src/core/compaction/` | 1,654 | compaction 1,104 + branch-summarization 380 + utils 163 |
| `src/extensions/llama/` | 1,577 | Built-in llama.cpp extension (TUI + downloader) |
| `src/extensions/codemode/` | 1,474 | Built-in codemode extension (QuickJS worker) |
| `src/cli/` | 1,458 | Argument parsing 479 + startup UI + auth commands + session picker |
| `src/core/tools/renderers/` | 1,007 | TUI/HTML renderers for the 8 tools |
| `src/core/export-html/` | 750 | HTML export (template rendering + ANSI→HTML) |
| `src/extensions/tool-search/` | 262 | Built-in tool_search extension |
| `src/bun/` | 61 | Entry point specific to the Bun build |

---

## 3. Startup path

### 3.1 Entry points

```
dist/bundle/cli.js            <- package.json bin
  src/cli.ts:2-4              setupCli(); main(process.argv.slice(2))
     src/cli/setup.ts:5-13    process.title / PI_CODING_AGENT=true / AI_AGENT=pi / configureHttpDispatcher()
     src/main.ts:574          main()
dist/bundle/rpc-entry.js      <- package.json exports "./rpc-entry"
  src/rpc-entry.ts:1-13       main(["--mode","rpc", ...argv])
```

The actual order inside `main()` (`src/main.ts:574-999`) is worth understanding section by section, because it determines the Kotlin CLI skeleton:

| Order | Action | Evidence |
|---|---|---|
| 1 | Set up the theme JSON validator; merge built-in extension factories (`builtin:mcp/llama.cpp/codemode/tool-search`) | `main.ts:577-578`, `extensions/index.ts` |
| 2 | Handle `--offline` / `PI_OFFLINE` (sets `PI_SKIP_VERSION_CHECK`) | `main.ts:579-583` |
| 3 | `auth` subcommands (check / print-api-key / print-bearer-token) | `main.ts:585-587`, `cli/auth-command.ts` |
| 4 | Windows self-update quarantine cleanup, managed-install cleanup | `main.ts:589-592` |
| 5 | Bootstrap `SettingsManager.create(cwd, agentDir, {projectTrusted:false})`; apply httpProxy; configure the HTTP dispatcher | `main.ts:594-598` |
| 6 | Package commands `install\|remove\|uninstall\|update\|list`; `config` command; `mcp` subcommands | `main.ts:600-621` |
| 7 | `parseArgs` and print diagnostics (exit 1 on error) | `main.ts:623-633`, `cli/args.ts:72-271` |
| 8 | Early exit for `--version` / `--export` | `main.ts:635-652` |
| 9 | Decide appMode; call `takeOverStdout()` when not interactive | `main.ts:654-658`, `core/output-guard.ts:51` |
| 10 | RPC rejects `@file`; validate `--fork` / `--session-id` conflicts | `main.ts:660-666` |
| 11 | `runMigrations(cwd)` (auth/old sessions/commands→prompts/keybindings/tool binaries) | `main.ts:669`, `migrations.ts:305-315` |
| 12 | Create the real SettingsManager, collect diagnostics, first-run wizard, `--use-theme` override | `main.ts:672-684` |
| 13 | Compute sessionDir precedence (`--session-dir` > env > settings) and create the SessionManager | `main.ts:691-696`, `config.ts:586` |
| 14 | Sessions with a missing cwd: ask in interactive mode, otherwise error | `main.ts:697-709`, `core/session-cwd.ts` |
| 15 | `--name` writes a `session_info` entry | `main.ts:710-717` |
| 16 | Project trust resolution (project-level settings/resources load only after cwd is known) | `main.ts:720-781`, see §10.4 |
| 17 | `createAgentSessionRuntime(createRuntime, ...)`: rebuild services for the target cwd (ModelRuntime/Settings/ResourceLoader), register extension providers/virtual models, resolve model scope, create the AgentSession | `main.ts:733-871`, `core/agent-session-runtime.ts:74` |
| 18 | Early exit for `--help` / `--list-models` (`--help` prints extension-registered flags) | `main.ts:878-892` |
| 19 | Read piped stdin; if interactive and stdin was read, downgrade to print | `main.ts:894-902` |
| 20 | Compose the initial message (stdin + @file + first message), initialize the theme | `main.ts:904-907`, `cli/initial-message.ts` |
| 21 | Exit on error; in non-interactive mode, exit when no model is available | `main.ts:917-931` |
| 22 | Dispatch `runRpcMode` / `InteractiveMode.run()` / `runPrintMode()` | `main.ts:949-999` |

### 3.2 Mode determination

`resolveAppMode` (`main.ts:112-123`):

```
--mode rpc            -> rpc
--mode json           -> json
--print / stdin not a TTY / stdout not a TTY -> print
otherwise             -> interactive
```

Additional rules:
- `--mode text` does not force a one-shot run (`docs/cli.md:41`).
- When piped stdin is read, interactive downgrades to print (`main.ts:896-900`).
- `--export` is an independent early-exit path and enters no mode (`main.ts:640-652`).

---

## 4. The four run modes

All four modes share the same `AgentSession` and event stream; they differ only in "who subscribes to events, how output is written, and how input is received".

### 4.1 interactive (TUI)

- Entry: `src/modes/interactive/interactive-mode.ts`, class `InteractiveMode` (7,082 lines + 11,661 lines of components + 2,261 lines of theme).
- Constructor parameters: `InteractiveModeOptions` (constructor at `interactive-mode.ts:610`), including startupDiagnostics, initialMessage(s), verbose, tuiMode, theme.
- Responsibilities: full terminal rendering, keybindings, pickers, extension UI, and all interactive commands for session tree/model/settings/login/export/share/bug.
- Depends on `pi-tui` for terminal abstraction, with two TUI modes: `fullscreen` (TuiAltScreen) and `regular` (TuiMainScreen) (`interactive-mode.ts:875-945`, the `tui-mode` setting).
- Key coupling points with headless: tool renderers, the `ExtensionUIContext` implementation (`createExtensionUIContext()`, `interactive-mode.ts:2633`), session entry rendering, and `CustomEntry`/`CustomMessage` rendering.

### 4.2 print (one-shot text)

- Entry: `src/modes/print-mode.ts:33 runPrintMode()`, 173 lines, the smallest of the three headless modes.
- Behavior: `prompt(initialMessage)` → `prompt(messages[i])` one by one → write only the text blocks of the last assistant message to stdout (`print-mode.ts:139-160`).
- Exit codes: 0 on success; 1 when `stopReason` is `error`/`aborted` or `length`, with the reason on stderr (`print-mode.ts:145-157`).
- Signals: SIGTERM/SIGHUP kill child processes first, then dispose (`print-mode.ts:50-66`).

### 4.3 json (event stream)

- Entry same as above, with `mode: "json"`.
- Output: the first line is the session header (`print-mode.ts:122-127`), then each `AgentSessionEvent` is compacted by `toJsonEvent()` and written as one line (`print-mode.ts:108-112`, `src/modes/json-event.ts:46-61`).
- Key compaction rules: `message_update` drops the SDK event's accumulated `message` and each `assistantMessageEvent.partial`, keeping only deltas; `toolcall_start` gets `id`/`toolName` added (`json-event.ts:20-38`, `docs/json.md:70-93`).
- **stdout carries protocol records only; all diagnostics go to stderr**: `takeOverStdout()` redirects `process.stdout.write` to stderr, while protocol writes go through `writeRawStdout()` (`core/output-guard.ts:51-97`).
- Backpressure: await `waitForRawStdoutBackpressure()` before writing each frame (`print-mode.ts:113-118`).

### 4.4 rpc (bidirectional JSONL)

- Entry: `src/modes/rpc/rpc-mode.ts:54 runRpcMode()`, 819 lines; protocol types in `rpc-types.ts` (303 lines).
- Record families: stdin commands, stdout `response`, stdout session events, and bidirectional extension UI records (`docs/rpc.md:24-33`).
- Command set (`rpc-types.ts:20-74`, 33 total): prompt / steer / follow_up / abort / clear_queue / new_session / get_state / set_model / cycle_model / get_available_models / set_thinking_level / cycle_thinking_level / get_available_thinking_levels / set_steering_mode / set_follow_up_mode / compact / set_auto_compaction / set_auto_retry / abort_retry / bash / abort_bash / get_session_stats / export_html / switch_session / fork / clone / get_fork_messages / get_entries / get_tree / get_last_assistant_text / set_session_name / get_messages / get_commands.
- Response correlation: the optional `id` is echoed back; unknown commands return `Unknown command: <type>`; JSON parse failures return a failure response with `command:"parse"` (`rpc-mode.ts:786-810`).
- Framing: strict LF framing; **Node readline cannot be used** (it also splits on U+2028/U+2029), so implement `attachJsonlLineReader` yourself (`modes/rpc/jsonl.ts:16-58`) and serialize with `JSON.stringify(v)+"\n"` (`jsonl.ts:8-10`).
- Shutdown semantics: stdin EOF → `shutdown()` → dispose runtime → exit (`rpc-mode.ts:831-838`); extensions can also request shutdown (`docs/rpc.md:91-95`).
- Extension UI subprotocol: `extension_ui_request` (select/confirm/input/editor/notify/setStatus/setWidget/setTitle/set_editor_text) and `extension_ui_response` (`rpc-types.ts:252-297`, `docs/rpc-extension-ui.md`).

### 4.5 Event overview (shared by all four modes)

`AgentSessionEvent` (`core/agent-session.ts:196-240`) — the event union the Kotlin version must implement 1:1:

- From the underlying agent: `agent_start`, `agent_end{messages,willRetry}`, `turn_start`, `turn_end{message,toolResults}`, `message_start`, `message_update`, `message_end`, `tool_execution_start/update/end` (the last may carry `parentToolCallId`).
- Session-level: `agent_settled{aborted}`, `queue_update{steering,followUp}`, `entry_appended{entry}`, `session_info_changed{name}`, `thinking_level_changed{level}`.
- Compaction/retries: `compaction_start{reason}`, `compaction_end{reason,result,aborted,willRetry,errorMessage?}`, `auto_retry_start/end`, `summarization_retry_scheduled`, `summarization_retry_attempt_start{source,reason?}`, `summarization_retry_finished`.
- Direct bash for RPC/interactive only: `bash_execution_update{id?,delta}`.

Semantics: `agent_end` ends only one underlying run; retries/overflow recovery/compaction retries/queued messages may continue. Only `agent_settled` means "no automatic work left" (`docs/json.md:48`, `agent-session.ts:1079`).

## 5. Core objects

### 5.1 AgentSession (4,449 lines, `core/agent-session.ts`)

This is the heart of coding-agent: it wraps `Agent` (the pi-agent-core loop) into a "product session".

**Configuration surface** (`AgentSessionConfig`, `agent-session.ts:252-301`): `agent`, `sessionManager`, `settingsManager`, `cwd`, `scopedModels`, `resourceLoader`, `customTools`, `modelRuntime`, `cacheWarmer`, `initialActiveToolNames`, `usesDefaultTools`, `defaultToolModifiers`, `allowedToolNames`, `excludedToolNames`, `baseToolsOverride`, `extensionRunnerRef`, `sessionStartEvent`.

**Main responsibility groups** (summarized from the method list, `agent-session.ts:486-4449`):

| Group | Representative methods | Notes |
|---|---|---|
| Initialization/binding | `constructor` 486, `_installAgentToolHooks` 653, `_installAgentRequestProjection` 788, `_installAgentBoundaryHooks` 887, `_installAgentNextTurnRefresh` 899 | Attach extension hooks to Agent's `beforeToolCall`/`afterToolCall`/`prepareRequest`/`finishTurn`/`prepareNextTurn` |
| Events | `subscribe` 1373, `_emit` 1040, `_emitAgentSettled` 1079, `_emitExtensionEvent` 1288 | Session events + extension event dispatch |
| Lifecycle | `dispose` 1397, `abort` 2485, `waitForIdle` 2497, `refreshContext` 1425 | |
| Runs | `prompt` 2079, `_startRun` 1858, `_prepareRun` 1911, `_handlePostAgentRun` 1964, `_runBeforeSettleBoundary` 2004, `_runInputHandlers` 2028 | One run = multiple turns; retries/compaction/queues/settle are handled between runs |
| Queues | `steer` 2262, `followUp` 2278, `clearQueue` 2453, `sendUserMessage` 2416, `sendCustomMessage` 2344, `_flushPendingCustomMessages` 2398 | Steering enters after the current assistant turn's tool calls finish and before the next LLM request; followUp enters after the entire run ends (`docs/how-pi-works.md:13`) |
| Tool registration/loadout | `setActiveToolsByName` 1523, `getActiveToolNames` 1487, `getCallableToolNames` 1492, `getAllTools` 1499, `_getToolExposure` 1559, `_applyToolLoadout` 1580, `_refreshToolRegistry` 3546 | Defines the three states "declared to the model (active) / callable by other tools (callable) / hidden"; see §7 for `exposure` semantics |
| Prompts | `_rebuildSystemPrompt` 1705, `_preparePromptAndToolLoadout` 1743, `_installHiddenDeclarationsProjection` 1772, `_installAgentForcedPromptProjection` 1792, `_restoreToolsFromTranscript` 1815 | Structured sections + transcript replay; tool-set changes are written as system message patches |
| Model/thinking | `setModel` 2528, `cycleModel` 2570, `setThinkingLevel` 2663, `cycleThinkingLevel` 2692, `getAvailableThinkingLevels` 2708, `_clampThinkingLevel` 2734 | |
| Compaction | `compact` 2815, `_checkCompaction` 2998, `_runAutoCompaction` 3148, `abortCompaction` 2965, `_exceedsCompactionThreshold` 768, `_compactBeforeNextAssistantResponse` 777 | Threshold checks run after tools finish and before the next turn, and also before a new prompt (`docs/compaction.md:37`) |
| Extension binding | `bindExtensions` 3309, `_bindExtensionCore` 3411, `reload` 3711, `extendResourcesFromExtensions` 3335 | |
| Retries | `_isRetryableError` 3760, `_prepareRetry` 3813, `abortRetry` 3858, `setAutoRetryEnabled` 3875 | |
| bash (`!` command/RPC bash) | `executeBash` 3892, `recordBashResult` 3930, `abortBash` 3956, `_flushPendingBashMessages` 3976 | Produces a `BashExecutionMessage`, converted to user text on the next prompt (`core/messages.ts:82-98`) |
| Session operations | `navigateTree` 4015, `getUserMessagesForForking` 4212, `setSessionName` 3993 | |
| Statistics | `getSessionStats` 4234, `getContextUsage` 4288 | `SessionStats` structure at `agent-session.ts:343-361` |
| Export | `exportToHtml` 4339, `exportToJsonl` 4365, `summarizeForBugReport` 4373 | |
| Other | `getLastAssistantText` 4398, `createReplacedSessionContext` 4426, `hasExtensionHandlers` 4439, `extensionRunner` 4446 | |

**The real prompt flow** (`agent-session.ts:2079-2166`); the Kotlin implementation can build its state machine directly from this:

1. If `agent_settled` is currently being emitted, queue the prompt in `_deferredSettledActions`.
2. If it starts with `/`, try extension commands first (they execute immediately even while streaming, and the extension owns subsequent LLM interaction).
3. Compacting → throw.
4. Fire the `input` extension event, which may consume/rewrite/intercept (returning null means handled).
5. Expand `/skill:name` and prompt templates.
6. While streaming: `streamingBehavior` (`steer`/`followUp`) is required or it errors; after enqueueing it returns `queued`.
7. Validate the model and auth (actionable errors when there is no model/credential).
8. `_startRun({messageType:"user",prompt,images})`: `_prepareRun` → compact first if needed → `before_agent_start` → assemble the kickoff messages (user + pending nextTurn + extension message + system patch) → `agent.prompt(messages)` → loop `_handlePostAgentRun()` (retries/overflow recovery/post-compaction retry/queues) and `_runBeforeSettleBoundary()` (`agent_before_settle`) → `agent.continue()` → finally `agent_settled`.

### 5.2 AgentSessionRuntime / services (`core/agent-session-runtime.ts` 447 + `agent-session-services.ts` 233)

- `AgentSessionRuntime` holds "the current AgentSession + the services for that cwd" and provides `newSession()`/`switchSession()`/`fork()`/`importFromJsonl()`; every replacement first runs `session_shutdown`, then rebuilds services, then runs `session_start` (`agent-session-runtime.ts:67-160`, `docs/sdk.md:58-60`).
- `createAgentSessionServices()`'s responsibility order (`agent-session-services.ts:135-205`): ModelRuntime (auth.json + models.json) → SettingsManager → DefaultResourceLoader.reload (including the project-trust callback) → register extension providers/virtual models → `modelRuntime.refresh({allowNetwork:false})` → apply extension CLI flag values → return diagnostics. **This "rebuild everything per cwd" structure must be kept in the Kotlin version**, otherwise `--session` across projects and `/resume` switching projects will go wrong.

### 5.3 SessionManager (2,013 lines, `core/session-manager.ts`)

Responsibility: **append-only writes + tree index + context rebuild + list/resume/fork** for JSONL file or in-memory sessions. Core facts:

- The current version is `CURRENT_SESSION_VERSION = 3` (`session-manager.ts:41`); v1→v2 adds id/parentId, and v2→v3 renames the `hookMessage` role to `custom` (`session-manager.ts:287-335`).
- There are 11 entry types (`session-manager.ts:183-194`): message, thinking_level_change, model_change, usage, compaction, branch_summary, custom, custom_message, context_edit, label, session_info. Field-by-field formats are in formats doc §2.
- **Deferred file creation**: a JSONL is created only once a user or assistant message appears; sessions with only model/thinking/system entries leave no file; the first `_persist` writes all in-memory entries at once, after which entries are appended one by one (`session-manager.ts:1166-1202`). This directly affects the compatibility semantics of "an empty session leaves no junk file".
- Session path: `<agentDir>/sessions/--<escaped cwd>--/<ISO timestamp with : . replaced by ->_<uuidv7>.jsonl` (`session-manager.ts:589-598`, `1632-1700`). Escaping rules: strip the leading `/`, replace `/`, `\`, `:` with `-`, and wrap both ends with `--`.
- id: session ids use uuidv7; entry ids use 8 hex characters (`randomUUID().slice(0,8)`, falling back to a full UUID after 100 conflict retries) (`session-manager.ts:264-284`).
- Context rebuild: `buildContextEntries()` (compaction-aware) → `buildSessionProjection()` (applies `context_edit`) → `buildSessionContext()` (converts to messages + restores model/thinking) (`session-manager.ts:476-586`).
- Tree operations: `branch`/`resetLeaf`/`branchWithSummary`/`getTree`/`createBranchedSession` (the implementation behind fork/clone, `session-manager.ts:1579-1754`).
- Static entry points: `create`/`open`/`continueRecent`/`inMemory`/`forkFrom`/`findById`/`list`/`listAll` (`session-manager.ts:1755-1900`).
- List concurrency: session discovery runs at most 64 concurrent, metadata loading at most 10 concurrent, and listings publish progress in batches (`session-manager.ts:891-946`). This is why `/resume` stays responsive with hundreds of sessions; the Kotlin version can implement it simply but must avoid reading large files serially.

### 5.4 SettingsManager (1,443 lines) + schema/defaults

- Two levels: global (`<agentDir>/settings.json`) + project (`<cwd>/.pi/settings.json`); project overrides global, and resource arrays merge (`core/settings-manager.ts:143-160`, `docs/settings.md:3`).
- Defaults are centralized in `core/settings-defaults.ts:4-67`; the full schema is in `core/settings-schema.ts` (`export const SettingsSchema`), with all fields in formats doc §3.
- Write-back: `FileSettingsStorage.withLock` takes a proper-lockfile lock and does read-modify-write (`settings-manager.ts:186-255`); `enqueueWrite` serializes writes (576); `persistScopedSettings` (598-628) re-reads the current file under the lock and overwrites only "fields/nested keys modified this time", **leaving unmodified and unknown fields untouched** (the schema declares `additionalProperties:true`, `settings-schema.ts:453`). The Kotlin version must implement the same "write only dirty fields", otherwise it erases keys written by the user or other extensions. Reading is tolerant of unknown fields (no validation, no error) and only acts on known fields when writing back.
- The `+name`/`-name` semantics of `defaultTools` live in `applyToolModifiers`/`resolveDefaultTools`/`mergeDefaultTools` (`settings-manager.ts:85-142`); this is the core rule of the CLI/settings/project three-level layering, and easy to get wrong.

### 5.5 Model system

Four objects with distinct roles:

| Object | File/lines | Responsibility |
|---|---|---|
| `ModelRuntime` | `core/model-runtime.ts` 1,049 | Single source of truth: composes providers, caches model snapshots, resolves auth, refreshes availability, registers extension providers/native providers/virtual models, streaming request entry points (`stream`/`complete`/`streamSimple`/`completeSimple`/`classify`/`generateImages`), login/logout (`model-runtime.ts:174-1045`) |
| `ProviderComposer` | `core/provider-composer.ts` 732 | Combines built-in providers + `models.json` + extension provider configs into usable providers: model override merging, authHeader, OAuth adaptation, headers/apiKey resolution (`provider-composer.ts:91-400`) |
| `ModelResolver` | `core/model-resolver.ts` 684 | `--model` text → model: exact id, `provider/id`, fuzzy matching, `:thinking` suffix, `--models` scope (glob/fuzzy), default-model fallback (`model-resolver.ts:120-531`) |
| `ModelRegistry` | `core/model-registry.ts` 244 | Synchronous facade for extensions (`getAll`/`getAvailable`/`find`/`findOfType`) |
| `VirtualModels` | `core/virtual-models.ts` 238 | Virtual models: `api = "pi-virtual"`, routing the four request classes `user`/`continuation`/`retry`/`direct` to physical models per request, with routing state stored in the `custom` entry `pi.virtual-model-state` |
| `ModelsStore`/`RemoteCatalog` | `models-store.ts` 147 + `remote-catalog-provider.ts` 240 | Cache/remote catalog overlay at `~/.pi/agent/models-store.json` |

Auth: `core/auth-storage.ts` 507 lines; `auth.json` has two credential kinds `{type:"api_key",key,env?}` / `{type:"oauth",access,refresh,expires,...}`, 0600 permissions, proper-lockfile cross-process locking, async lock with a 30s cap + EPERM retry (`auth-storage.ts:36-156`). Credential precedence: runtime `--api-key` > `auth.json` > `models.json`'s `apiKey` > provider environment variables (`docs/models.md:23`).

### 5.6 System prompt (`core/system-prompt.ts` 229 lines)

**This is the part of pi's behavior that is hardest to make "look the same", yet simple to implement**:

- The prompt is a set of **named, independently replaceable sections** (`SystemPromptSections`, `system-prompt.ts:51-56`): `preamble` (no tag), `tools`, `rules`, `docs`, `addendum`, `project_context`, `skills`, `cwd`, plus extension-defined sections. Every section except `preamble` is wrapped as `<name>\n...\n</name>` (`system-prompt.ts:188-192`).
- The default preamble is a fixed sentence (`system-prompt.ts:155-156`); the `tools` section comes from each tool's `promptSnippet` (157-160); the `rules` section comes from tool `promptGuidelines` + global rules (`buildRules`, 88-125); the `docs` section feeds readme/docs/examples paths to the model (162-169).
- Context files are wrapped in `<project_instructions path="...">` (79-86); skills are listed as name/description/location in `<available_skills>` XML (`core/skills.ts:358-388`).
- **Incremental update mechanism**: before each request, `diffSystemPromptSections(previous, current)` yields a patch, appended as a `role:"system", content:"", sections:patch` message; `null` deletes a section (`system-prompt.ts:217-228`, `agent-session.ts:1743-1771`). The first request writes the complete sections + `toolsAdded` (real-session evidence at line 4 of `~/.pi/agent/sessions/--home-user-workspace--/*.jsonl`). Tool-set changes are likewise expressed via a system message's `toolsAdded`/`toolsRemoved`, and replay yields the current state. **The Kotlin version must implement "replay system messages to obtain the current prompt and tool set"**, otherwise resuming sessions goes wrong.

## 6. Tool system

8 built-in tools (`core/tools/index.ts:95-105`), all registered through `ToolDefinition` (extension type `core/extensions/types.ts:579`), with `parameters` (TypeBox schema), `description`, `promptSnippet`, `promptGuidelines`, `execute`, and optional `outputSchema`/`constrainedSampling`/`render*`.

| Tool | File | Parameter schema | Behavior | Output limit |
|---|---|---|---|---|
| `read` | read.ts 224 | `path`, `offset?` (1-indexed), `limit?` | Reads text as UTF-8 with line/byte truncation; images go through `processImage` (auto-resize + EXIF + text hint downgrade for non-vision models); out-of-range offset errors; when the first line exceeds the limit, provides a bash fallback command | 2000 lines / 50KB (`truncate.ts:11-12`) |
| `bash` | bash.ts 442 | `command`, `timeout?` (seconds) | Builds shell config (`getShellConfig`), a `detached` process group, streaming stdout+stderr, timeout/abort kills the whole process tree, output accumulated to a temp file; `PI_*` session env vars injected (194-208); non-zero exit → `isError:true` with `Command exited with code N` appended | Tail 2000 lines / 50KB; full output written to `pi-bash-*.log`; `structuredContent.output` capped at 1MB |
| `powershell` | powershell.ts 67 | Same as bash | Windows only; reuses `createShellToolDefinition` | Same as bash |
| `edit` | edit.ts 220 | `path`, `edits[{oldText,newText}]` | Unique non-overlapping match in the original file; `prepareArguments` tolerates "edits as a JSON string/single object/legacy oldText+newText" (103-134); BOM and CRLF are detected, normalized for matching, then restored (190-197); `withFileMutationQueue` serializes writes to the same file; returns diff + unified patch + firstChangedLine | No output truncation (returns a summary) |
| `write` | write.ts 97 | `path`, `content` | Creates parent directories recursively; serializes same-file writes | None |
| `grep` | grep.ts 323 | `pattern`, `path?`, `glob?`, `ignoreCase?`, `literal?`, `context?`, `limit?` (default 100) | **Depends on the external `rg` binary** (`ensureTool("rg")`, downloaded from GitHub when missing, with an offline-mode error fallback); `--json --line-number --color=never --hidden`; `--fixed-strings`/`--ignore-case`/`--glob`; matching lines truncated to 500 chars; actively kills on reaching limit | 100 matches / 50KB |
| `find` | find.ts 318 | `pattern`, `path?`, `limit?` (default 1000) | **Depends on the external `fd` binary**; decides whether to add `--no-require-git` based on being in a git repo; patterns containing `/` use `--full-path` with a `**/` prefix; results relativized | 1000 entries / 50KB |
| `ls` | ls.ts 175 | `path?`, `limit?` (default 500) | Alphabetical (case-insensitive), directories suffixed with `/`, hidden files included, stat failures skipped | 500 entries / 50KB |

Shared infrastructure:

- `truncate.ts` 314: `truncateHead`/`truncateTail`/`truncateLine`/`truncateMiddle`; dual limits (lines/bytes) apply whichever comes first and never return a half line (tail truncation is the sole exception); `Buffer.byteLength` counts UTF-8 bytes.
- `output-accumulator.ts` 257: bash streaming accumulation + temp-file spill when over limit + throttled callback (`BASH_UPDATE_THROTTLE_MS`).
- `file-mutation-queue.ts` 61: serializes same-file writes by realpath, different files in parallel.
- `path-utils.ts` 118: `~` expansion, `@` prefix stripping, Unicode space normalization, macOS NFD/curly-quote/narrow-space fallbacks.
- `bash-executor.ts` 146: used by the `!` command and RPC bash; ANSI/binary cleaning (`splitIncompleteAnsiSuffix` + `sanitizeBinaryOutput`), rolling buffer 2×50KB, spilled to disk when exceeded.
- External tool management: `utils/tools-manager.ts` (401 lines) finds/downloads fd and rg under `~/.pi/agent/bin/` (GitHub release, skipping the api.github.com anonymous quota). **Local state: `/usr/bin/rg` exists; `fd` only at `~/.pi/agent/bin/fd`.**

TUI coupling: each tool's `renderers/*.ts` (1,007 lines) is used only by interactive mode and HTML export; `ToolDefinition` itself carries no rendering logic, so **renderers can be skipped entirely for the headless build** (`extensions/types.ts:459-690`).

## 7. Extension mechanism

### 7.1 Loading

- `core/extensions/loader.ts` (879): `jiti` (`jiti-loader.ts` / `jiti-static-loader.ts`) loads TS/JS directly in the current process, injecting virtual module aliases (`virtual-modules.ts`) and mapping `@earendil-works/pi-*`, `typebox`, etc. to the host-provided instances to avoid duplicate classes and registries (`loader.ts:49-130`, `docs/packages.md:78-92`).
- Discovery rules: direct files under `extensions/` + subdirectories with `index.ts`/`index.js`/`package.json` main (`loader.ts:739-790`); `~/.pi/agent/extensions/`, `.pi/extensions/`, settings `extensions`, packages, `-e/--extension`, `builtin:<name>` (`resource-loader.ts:680-800`).
- `builtin:` extensions are provided by factories in `extensions/index.ts`: `mcp`, `llama.cpp`, `codemode`, `tool-search`; they can be "replaced" by an extension with the same name (`resource-loader.ts:108-152`, `docs/settings.md:163`).
- Load result `LoadExtensionsResult{extensions, errors, warnings, runtime}`; a single extension failure only produces a diagnostic and does not block the others (`main.ts:803-811`).

### 7.2 Runtime API

`ExtensionAPI` (`core/extensions/types.ts:1592`) — about 50 event subscriptions + registration methods:

- Event subscriptions: `on(...)`; event names are in §7.3.
- Registration: `registerTool`, `registerCommand`, `registerShortcut`, `registerFlag`, `registerMessageRenderer`, `registerMarkdownTransformer`, `registerEntryRenderer`, `registerToolRenderer`, `registerProvider`/`registerNativeProvider`, `registerVirtualModel`, `registerMcpServer`.
- Session: `sendMessage`, `sendUserMessage`, `appendEntry`, `setSessionName`, `getSessionName`, `setLabel`, `exec`, `getActiveTools`/`setActiveTools`/`getAllTools`, `getSettings`, `getCommands`, `setModel`, `getThinkingLevel`/`setThinkingLevel`.
- Context `ExtensionContext` (`types.ts:325`): ui/mode/hasUI/cwd/sessionManager/modelRegistry/model/scopedModels/thinkingLevel/isIdle/isProjectTrusted/signal/abort/hasPendingMessages/shutdown/getContextUsage/compact/getSystemPrompt.
- Command context `ExtensionCommandContext` (`types.ts:401`): `waitForIdle`, `newSession`, `fork`, `navigateTree`, `switchSession`, `reload`, `withSession`.
- Tool context `ExtensionToolContext` (`types.ts:383`): `executeTool(name,args,{signal,onUpdate})` for nested calls, with events carrying `parentToolCallId`, `toolCallId` as `<parent>/<n>`, and only bounded `nestedCalls` metadata kept in the session (`agent-session.ts:731-767`, `docs/extensions.md:261`).

### 7.3 Events and hook order

Per `docs/extensions.md:94-125` and the emit groups in `runner.ts`:

- Input/startup: `input`, `before_agent_start` (`emitBeforeAgentStart`, `runner.ts:1427`).
- Run: `agent_start`, `agent_end`, `agent_before_settle`, `agent_settled`.
- Turn: `turn_start`, `context`, `context_with_system`, `turn_end`.
- Provider: `before_provider_headers`, `before_provider_request`, `after_provider_response`, `provider_stream_event`; plus `cache_warming_decision`.
- Messages: `message_start`, `message_update`, `message_end`.
- Tools: `tool_execution_start`, `tool_call`, `tool_execution_update`, `tool_result`, `tool_execution_end`.
- Session: `project_trust`, `resources_discover`, `session_start`, `session_info_changed`, `session_before_switch`, `session_before_fork`, `session_before_compact`, `session_compact`, `session_compact_failed`, `session_shutdown`, `session_before_tree`, `session_tree`, `mcp_servers_change`.
- UI: `ui_prompt_start`, `ui_prompt_end`.
- Model: `model_select`, `thinking_level_select`; bash: `user_bash`.

Composition semantics (`docs/extensions.md:203-241`): notification-type handlers ignore return values; in-place mutation type (headers, systemPromptOptions, tool_call.input); chain-transform type (input, context, context_with_system, message_end, tool_result, before_provider_request); gating type (input handled / tool_call block / session cancel); the last `cache_warming_decision` wins. A throwing `tool_call` handler = fail-closed tool blocking (`docs/extensions.md:375`).

### 7.4 Tool exposure model (exposure)

`ToolExposure = "direct" | "model-only" | "codemode" | "deferred" | "hidden"` (`types.ts:516`):

- `direct`: declared to the model + callable via `executeTool` (activated on registration).
- `model-only`: declared to the model only; other tools cannot call it.
- `codemode`: callable on registration but not declared to the model; called by codemode scripts, with the namespace listed in the codemode description.
- `deferred`: same as codemode but not listed by codemode; only findable via `tool_search`.
- `hidden`: registered but unreachable (used for revocation).

`prepareLoadout(loadout)` can rewrite declarations (descriptions/hidden declarations), and `reachesTools(input)` declares which tools a call may reach (the MCP extension uses this to defer connection, `docs/extensions.md:293-295`).

### 7.5 Extension UI

`ExtensionUIContext` (`types.ts:149-306`) has about 35 methods: dialogs (select/confirm/input/editor/custom), notify, setStatus/setWidget/setFooter/setHeader/setTitle/setWorkingMessage/setWorkingVisible/setWorkingIndicator/setHiddenThinkingLabel, editor operations (pasteToEditor/setEditorText/getEditorText/setEditorComponent/addAutocompleteProvider), themes (getAllThemes/getTheme/setTheme), terminal input, and tools expanded.

- interactive: all implemented (`interactive-mode.ts:2633-3005`).
- RPC: dialogs go through `extension_ui_request/response`; custom/terminal input/footer and similar degrade to no-ops (`docs/rpc-extension-ui.md:12-25`).
- json/print: `mode` is `"json"`/`"print"`, `hasUI=false`, and dialogs return default values directly (`noOpUIContext` in `runner.ts:331`).

### 7.6 Resource system: skills / prompt templates / themes / packages

| Resource | Loader | Size | Key rules |
|---|---|---|---|
| skills | `core/skills.ts` 514 + `package-manager.ts:456-530` | | A directory containing `SKILL.md` is a skill root and is not descended into; otherwise `SKILL.md` is searched recursively; `.md` files directly in the root also count as skills; `name` defaults to the parent directory name; name validation is 64 characters/lowercase/hyphens; description is required and ≤1024; on conflicts the first found wins; `disable-model-invocation` is supported; additionally discovers `~/.agents/skills` and (for trusted projects) ancestor `.agents/skills` (`package-manager.ts:2464-2570`) |
| prompt templates | `core/prompt-templates.ts` 325 | | `<agentDir>/prompts/`, `.pi/prompts/`, settings `prompts`, packages; loads only direct `.md` child files (recursion only via explicit paths in settings/packages); frontmatter `description`/`argument-hint`; argument substitution `$1`/`$@`/`$ARGUMENTS`/`${1:-d}`/`${@:N}`/`${@:N:L}`; shell-style quote parsing (`prompt-templates.ts:25-108`) |
| themes | `core/resource-loader.ts:1043-1135` + `modes/interactive/theme/theme.ts` 1,086 | | JSON: `name` (unique, must not contain `/`, must not be `system`)/`appearance`/`vars`/`colors`/`export`; six color forms (#hex, oklch, okhsl, 256-color index, variable reference, `""` terminal default); 5 optional colors have inheritance (`docs/themes.md:120-130`); built-in `dark`/`light`/`system`; the `system` theme probes terminal colors |
| context files | `resource-loader.ts:184-275` | | Searches `AGENTS.override.md`, `AGENTS.md`, `AGENTS.MD`, `CLAUDE.md`, `CLAUDE.MD` in that order; one in agentDir + one per directory from cwd up to the root (far to near); deduplicates worktree shadow files; no project trust needed |
| SYSTEM.md/APPEND_SYSTEM.md | `resource-loader.ts:1213-1240` | | agentDir or `.pi/` (must be trusted); same names do not merge; project overrides user |
| packages | `core/package-manager.ts` 2,766 + `package-manager-cli.ts` 1,142 | | npm:/git:/local paths; globally installed into `~/.pi/agent/npm`, `--local` writes project settings; `pi` manifest explicit resources + conventional directories; resource filter syntax `!pattern` / `+path` / `-path`; package identity deduplicated by npm name/git URL/absolute path (`docs/packages.md:29-127`) |

## 8. Context management

### 8.1 compaction (`core/compaction/compaction.ts` 1,104 + branch-summarization.ts 380)

- Trigger: `contextTokens > contextWindow - reserveTokens` (defaults reserve 16,384 / keepRecent 20,000, `docs/compaction.md:29-35`). Checkpoints: after tools finish and before the next turn, before a new prompt, and overflow recovery after the underlying run ends (`docs/compaction.md:37`).
- Algorithm: on projected entries, accumulate tokens from newest to oldest and cut at a **valid cut point** once keepRecentTokens is reached; valid cut points are user/assistant/bashExecution/custom_message/branch_summary, and it **never cuts at a toolResult** (`compaction.ts:351-365`, `findCutPoint` 446-506). If a single user span already exceeds the budget, it cuts at the assistant (split turn, `isSplitTurn`) and generates two summaries that are merged (`findTurnStartIndex` 412).
- Summary: a structured fixed format (Goal/Constraints/Progress/Key Decisions/Next Steps/Critical Context), with `<read-files>`/`<modified-files>` appended at the end; a second compaction uses an UPDATE instruction to preserve the previous summary content (`compaction.ts:507-583`).
- Serialization: `serializeConversation` turns messages into `[User]:` / `[Assistant]:` / `[Assistant tool calls]:` / `[Tool result]:` text, drops thinking, and truncates tool results to 2000 characters (`docs/compaction.md:276-291`).
- Persistence: `CompactionEntry{summary, firstKeptEntryId, tokensBefore, usage?, details?, fromHook?, systemMessage?}`; `systemMessage` is the complete prompt/tool snapshot at the compaction boundary and becomes the leading system message of the post-compaction context (`session-manager.ts:91-104`, `docs/session-format.md:123-137`).
- The summary start point for repeated compaction is the previous `firstKeptEntryId`, not the compaction entry itself (`docs/compaction.md:83`). With retain-none, `firstKeptEntryId = its own id`.
- Overflow/length recovery order: persist the final assistant message → `turn_end` → `agent_end` → append a `context_edit` omission → append the compaction if compaction succeeds → restart the run (`docs/compaction.md:89-98`).
- Branch summaries: when `/tree` switches branches, entries from the abandoned branch are collected by common ancestor, taken newest-to-oldest within a token budget, summarized, and written as `BranchSummaryEntry{fromId, summary, usage?, details?, fromHook?}` (`branch-summarization.ts`, `docs/compaction.md:169-232`).
- Extension hooks: `session_before_compact` can cancel or provide a custom summary; `session_compact_failed`; `session_before_tree` (`docs/compaction.md:293-415`).

### 8.2 context_edit

Append-only modification of "an earlier entry's contribution to context", without changing the original entry (`session-manager.ts:174-180`, `docs/session-format.md:139-147`). `replacement` is `{content} | null` (not content directly; note the elision in the docs). With multiple edits to the same target, the latest on the branch wins; a non-null replacement changes only content and keeps the original role and metadata.

### 8.3 Token estimation

`estimateTokens` (`compaction.ts:298-349`) estimates per role; images are converted at a fixed `ESTIMATED_IMAGE_CHARS = 4800` characters (`compaction.ts:276`). Context tokens prefer the most recent assistant's provider usage and then estimate subsequent messages (`estimateContextTokens` 196). The Kotlin version must replicate "prefer real usage + estimate the increment", otherwise the compaction trigger point drifts noticeably.

## 9. Peripheral capabilities (very trimmable)

| Capability | File | Lines | Description | Recommendation |
|---|---|---|---|---|
| HTML export | `core/export-html/index.ts` 318 + ansi-to-html 258 + tool-renderer 174 + template/vendor | 750 | Inline templates + CSS/JS rendering a session into a single HTML file; extension tools can register HTML renderers | DEFER |
| JSONL export | `core/session-export.ts` | 45 | Exports the current branch, reordering parentId | Do it together with RPC `export_html`/`/export`; low cost |
| /share | `modes/interactive/session-share.ts` + `core/radius.ts` | 224+11 | Radius artifact or GitHub gist | DROP |
| bug report | `core/bug-report.ts` 375 + upload 37 + crash-log 170 + `modes/interactive/bug-report.ts` 298 | 880 | Builds a zip and uploads to radius.pi.dev | DROP |
| telemetry / version check | `core/telemetry.ts` 13, `utils/version-check.ts` 109 | 122 | Anonymous install/update reporting | DROP |
| cache warmer / cache stats | `core/cache-warmer.ts` 453 + cache-stats 174 | 627 | Replays requests to keep the cache warm when idle/after streaming, based on model `promptCache` lifetime and estimated savings (≥$0.05) | DROP (pure cost optimization; does not affect correctness) |
| Virtual models | `core/virtual-models.ts` 238 | 238 | Request-level routing (classifier/load balancing) | DEFER |
| Extension MCP | `extensions/mcp/*` | 4,258 | MCP client (stdio/streamable HTTP/OAuth/resources/exposure policy/TUI) | DEFER, high risk |
| codemode | `extensions/codemode/*` + pi-codemode | 1,474+3,037 | Runs JS in a QuickJS worker to call tools | DROP (no equivalent lightweight option on the JVM) |
| tool_search | `extensions/tool-search/tool.ts` | 262 | Finds undeclared tools and activates them | Only meaningful after MCP/codemode are implemented |
| llama.cpp extension | `extensions/llama/*` | 1,577 | Local GGUF router management, TUI, HuggingFace downloads | DROP |
| Image processing | `utils/image-*.ts`, `photon.ts`, `exif-orientation.ts`, `mime.ts` | ~800 | Resize (Lanczos3), EXIF orientation, JPEG/PNG conversion, worker threads | MUST-lite (needed for the read tool to send images; ImageIO can substitute) |
| Clipboard/terminal capabilities | `utils/clipboard*.ts`, terminal-setup related | ~500 | OSC52/command-line clipboard, inline image protocol detection (in pi-tui) | Decided together with the TUI |
| Package management CLI | `package-manager-cli.ts` 1,142 + `package-manager.ts` 2,766 | 3,908 | install/remove/update/list/config, npm/git, locks, concurrency | DEFER/DROP |

## 10. Compatibility with existing user data

Measured locally (`~/.pi/agent`):

| File/directory | Current state | Compatibility requirement |
|---|---|---|
| `settings.json` | 1,075 bytes, 13 npm package declarations, `deviceId`, compaction, theme, `extensions:["+builtin:mcp"]` | **Must be readable**; write-back must preserve unmodified fields and unknown keys (write only dirty fields), matching real pi |
| `auth.json` | 5 providers: 2 `api_key`, 3 `oauth` (with clientId/scopes/scope) | Must read both credential types; writes must be 0600 and locked |
| `models.json` | 2 custom providers (`self-host`, `Milous-Plan`, both `openai-completions`), 9 models | Must read `providers.*.{baseUrl,api,apiKey,models[]}` |
| `models-store.json` | 146KB catalog cache + a `.lock` directory | Can be ignored (re-fetched), but its presence must be tolerated |
| `sessions/` | 45 project directories; single-session JSONL starts with a v3 header | **Bidirectional compatibility is a hard requirement**: sessions written by Kotlin must open in real pi, and vice versa |
| `mcp.json` | 4 servers (1 URL+headers, 2 stdio+exposure:deferred, 1 OAuth) | If MCP is not implemented, this file must be ignored rather than error |
| `trust.json` | `{"/abs/path": true, ...}` (false/null also allowed) | Read: the nearest ancestor match decides; write: do not overwrite each other when coexisting with pi |
| `skills/` | Symlinked to `/home/user/agent-config/skills` (6 skills) | Skill loading must follow symlinks |
| `prompts/` | Empty | — |
| `bin/` | `fd`, `chrome-debug`, `qqmail` | fd can be reused directly; rg uses the system `/usr/bin/rg` |
| `keybindings.json` | Not present | Just implement the default keybindings |
| `themes/` | No `~/.pi/agent/themes` (theme is `dark`) | Built-in dark/light suffice |
| `extensions/` | Empty directory (all extensions come from npm packages) | Before the extension strategy is decided, just report a diagnostic |

Full formats are in `docs/analysis/pi-coding-agent-formats.md`.

## 11. TUI coupling analysis

### 11.1 Size breakdown

| Part | Lines | Contents |
|---|---|---|
| `interactive-mode.ts` | 7,082 | Controller: TUI mount (875-945), event rendering (3363-3764), extension UI (2633-3005), keybindings/submission (3024-3357), slash commands (4878-7063), login/OAuth (5761-6403), export/share/bug (6403-6770), session tree/picker (5290-5760) |
| components/ | 11,661 | 47 components; largest: tree-selector 1,428, easter-egg-3d 1,292, session-selector 1,045, settings-selector 1,009, config-selector 951, model-selector 421, tool-execution 408, armin 384 |
| theme/ | 2,261 | theme.ts 1,086 (color parsing/OKHSL/gamut mapping), system-theme 655 (terminal color sampling + contrast algorithms), controller 253, tokens 121, schema 146 |
| `pi-tui` (external) | 19,623 | Core 13,776 (alt-screen 1,797, tui 1,543, keys 1,455, utils 1,402, autocomplete 858, terminal-image 769, main-screen 656, terminal 630, stdin-buffer 480...) + components 5,847 (editor 2,473, markdown 1,047, input 501...) |

### 11.2 Minimum viable set

To build a "good enough" interactive interface in Kotlin, the minimum set to implement (in dependency order):

1. Terminal control: raw mode, cursor/line clearing, window size, SIGWINCH, wide-character width (CJK/emoji double-width), ANSI styles and reset.
2. Line editor: insert/delete/move, history, multi-line (Shift+Enter), pasting large text, `@` file completion, `/` command completion, Tab completion; CJK IME cursor positioning (pi uses the `CURSOR_MARKER` mechanism, `docs/tui.md:48`).
3. Streaming transcript: user messages, assistant text (incremental), thinking (collapsible), tool call/result blocks (collapsible), diff rendering, error blocks, compaction/branch summary blocks; inline Markdown (headings/lists/code blocks/links).
4. Status area: footer (cwd/session/model/context usage/cumulative usage and cost), editor border color expressing thinking level, working indicator.
5. Pickers: model, thinking, session (resume, with search/rename/delete), settings, trust, login method.
6. Queues and interrupt: Enter sends (steering while streaming), Alt+Enter=followUp, Esc=abort and put queue contents back into the editor, Alt+Up=recall queued messages, Ctrl+O expand tool output, Ctrl+T toggle thinking.
7. Minimal extension UI: select/confirm/input/editor/notify/setStatus/setWidget (about 8 methods).

**Outside this set and droppable entirely**: fullscreen/alt-screen mode and mouse events, inline image rendering (kitty/iterm2 protocols), OSC 8 hyperlinks, syntax highlighting, mermaid, LaTeX, clipboard image paste, external editor, /share, /bug, /changelog, /hotkeys help page, first-time-setup wizard, startup animation/3D/easter egg, theme `system` auto color sampling (fixed dark/light is fine), custom theme hot reload, tmux/termux adaptations, Windows keybinding differences.

### 11.3 Workload estimate (Kotlin)

| Approach | Estimate | Risk |
|---|---|---|
| Fully hand-written (aligned with pi-tui behavior) | 12-18k lines | High: wide characters/IME/terminal differences are long-term grunt work; the first bugs will cluster around Chinese input and narrow terminals |
| Hand-written trimmed version (only the "minimum viable set", regular mode, no mouse/images) | 6-9k lines | Medium: clearly fewer features than pi, but usable day to day |
| JLine3-based (LineReader + completion + terminal capabilities) + hand-written transcript/pickers | 4-6k lines | Medium-low: the editor is nearly free; Markdown/tables/folding still need writing |
| Lanterna / Mordant-based | 4-7k lines | Medium: Lanterna's fullscreen model differs from pi's "scrolling transcript" mindset; substantial adaptation |

**Conclusion: the interactive TUI is not a module to "knock out along the way".** Even with JLine3 it is 4-6k lines, and its correctness can only be validated by human experience and pty scripts, unlike RPC which can be automatically accepted.

## 12. Node/Bun capability list and JVM replacements

| Node/Bun capability | Usage site | JVM replacement | Risk |
|---|---|---|---|
| `child_process.spawn` + detached + process tree kill | `core/tools/bash.ts:112-163`, `utils/shell.ts:killProcessTree`, `utils/child-process.ts` (cross-spawn) | `ProcessBuilder` + `ProcessHandle.descendants()` + `destroyForcibly()`; process-group kill needs `setsid` or JNA `killpg` | Medium: the `bash` tool's "abort kills npx/subshell descendants" is a hard requirement; the JVM must implement it explicitly |
| `node:fs` sync/async | Everywhere | `java.nio.file` (`Files.*`), `FileChannel` append writes | Low |
| File locks (proper-lockfile, cross-process) | auth/settings/trust/models-store/package-manager | `FileChannel.tryLock()` (works in-JVM/across processes, weaker NFS semantics) or mkdir atomic locks | Medium: must coexist with real pi without corrupting each other; different lock protocols can "seem to work but still collide"; in practice two processes writing the same JSON concurrently is unlikely |
| `node:readline` (explicitly banned for JSONL because it splits U+2028/U+2029) | Loading session lists, parsing grep output | Hand-written UTF-8 stream reader splitting on `\n`; `BufferedReader.readLine` also breaks on lone `\r`, so strict JSONL should be hand-written | Low |
| `undici` (HTTP dispatch/proxy/timeouts/connection pool) | `core/http-dispatcher.ts` | `java.net.http.HttpClient` (HTTP/2, async, BodyHandlers) | Low-medium: SSE streaming needs `ofInputStream` + self-parsing |
| `node:worker_threads` | Image resize worker (`utils/image-resize.ts`) | Not needed (handle synchronously or use `Executors`) | Low |
| `jiti` (in-process TS/ESM loading with virtual module aliases) | Extension loading | **No equivalent.** Options: Kotlin SPI, GraalJS, or a Node sidecar | High: this is the fundamental obstacle to extension compatibility |
| `quickjs-wasi` | codemode | GraalJS (heavy) or none | High (recommend giving up) |
| `@silvia-odwyer/photon-node` (WASM image processing) | Image resize/EXIF | `BufferedImage` + `ImageIO` + TwelveMonkeys; EXIF via metadata-extractor | Low |
| `minimatch` / `brace-expansion` | Package resource globs, model scope globs | Hand-written glob matching (watch `**`, `{a,b}`, `!` semantics); `FileSystems.getDefault().getPathMatcher("glob:...")` has different semantics and cannot be used directly | Medium |
| `ignore` (.gitignore semantics) | skills/packages scanning | Hand-written gitignore parser (watch `!`, `/` prefix, directory suffix) | Medium: scan results directly determine which skills/packages are discovered |
| `diff` (jsdiff) | edit tool unified patch, TUI diff | `java-diff-utils` | Low |
| `yaml` | frontmatter parsing | SnakeYAML / kaml | Low |
| `semver` / `hosted-git-info` | Package versions and git URLs | Hand-written | Low |
| `chalk` | Diagnostic coloring | Hand-written ANSI or Mordant/Jansi | Low |
| `typebox` (JSON Schema definitions + validation) | Tool argument schema, settings schema | A custom schema DSL + kotlinx.serialization `JsonElement` validation; tool schemas must be emitted as valid JSON Schema for providers | Medium: schema shape affects model behavior; do not change it casually |
| `grok-mermaid` / `highlight.js` | TUI only | Drop | None |
| `uuidv7` (provided by pi-ai) | Session id | Hand-written (timestamp + random) | Low |
| `process.stdout.write` takeover + backpressure | `core/output-guard.ts` | `System.out`/`FileDescriptor.out` + hand-written backpressure queue | Low |

Recommended JVM stack: keep the repository's settled Kotlin 2.4.x + JDK 21 + kotlinx-coroutines (event streams) + kotlinx-serialization-json (strict JSONL) + OkHttp 5 (provider/HTTP, see `docs/ARCHITECTURE.md`); add JLine3 (TUI line editing), Clikt or picocli (CLI), and GraalVM native-image or jpackage (distribution). `java.net.http` is the fallback when OkHttp is not desired.

## 13. Dependency points on pi-agent / pi-ai

coding-agent's dependencies on the two lower-level packages are **clear and thin** (apart from the TUI), which is good news for the Kotlin version.

### 13.1 pi-agent-core (2,539 lines)

Import surface: `Agent`, `setDefaultStreamFn`, `AgentMessage`, `ThinkingLevel` (`sdk.ts:1-3`, `agent-session.ts:33`).

Dependency semantics (`packages/agent/src/agent.ts`):

- `Agent` provides one run's loop: `prompt()`/`continue()`/`abort()`/`waitForIdle()`, `state.messages/tools/model/thinkingLevel`, `subscribe()`, steering/followUp queues (`set steeringMode`/`steer`/`followUp`/`clear*`).
- Replaceable hooks (coding-agent uses all of them): `beforeToolCall`, `afterToolCall`, `prepareRequest`, `finishTurn`, `prepareNextTurn`, `transformContext`, `streamFunction`, `toolExecution` (`agent-session.ts:653-940`).
- `AgentEvent` is the base union of session events (`turn_start/turn_end/message_*/tool_execution_*`).

**Conclusion: the Kotlin equivalent of pi-agent-core is about 2.5-4k lines** (if the pi-agent workflow is already implemented, coding-agent only needs to attach hooks on top).

### 13.2 pi-ai (27,709 lines, the heavy part)

The subset coding-agent actually uses:

- Types: `Model`/`Api`/`Message`/`AssistantMessage`/`ToolResultMessage`/`SystemMessage`/`ImageContent`/`TextContent`/`ThinkingContent`/`ToolCall`/`Usage`/`Provider`/`ProviderHeaders`/`AuthResult`/`Credential`/`CredentialStore`/`Transport`/`RetryPolicy`/`ClientIdentity`.
- Streaming entry points: `streamSimple`/`completeSimple` (`sdk.ts:5`, `compaction.ts:25`), `StreamFn`, `lazyStream`, `AssistantMessageEventStream`, `AssistantMessageEvent` (JSON mode forwards them directly).
- Session-related helpers: `getCurrentSystemMessage`, `getSystemMessageText`, `getCurrentTools`, `normalizeContext`, `contentText`, `combineUsage`.
- Retries/errors: `retryAssistantCall`, `retryDelayMs`, `isRetryableAssistantError`, `DEFAULT_MAX_AGENT_RETRY_DELAY_MS`.
- Other: `calculateCost`, `modelsAreEqual`, `uuidv7`, `PI_ATTRIBUTION`, `formatUserAgent`, `clampThinkingLevel`, `getSupportedThinkingLevels`, `ModelThinkingLevelSchema`, `isModelType`, `getProviderEnvValue`.
- Provider catalog/config: `providers/all`, `providers/model-schema`, `providers/radius-config`, `providers/openrouter`, `providers/compat-schema`, `utils/model-operations`, `utils/oauth-page`, `utils/retry`, `utils/provider-env`, `bun-oauth`, `bedrock-provider`, `auth` (OAuth types).
- Model catalog: built-in provider list + pricing + contextWindow/maxTokens/thinking capability + `promptCache` lifetime + `inputLimits`.

**Conclusion: the Kotlin version cannot 1:1 replicate 27.7k lines of the full provider matrix.** But given current actual usage, **only 2 API protocols** (`openai-completions` + `anthropic-messages`) are needed to cover `opencode-go` (the local default provider, as proven by `packages/ai/src/api/openai-completions.ts:614` going the OpenAI-compatible route), `deepseek`, `self-host`, and `Milous-Plan`; add SSE parsing, usage/cost, and thinking parameter mapping and that is enough. OAuth (openai/xai/radius) can be reduced to API keys only.

### 13.3 pi-tui (19,623 lines)

90 imports, all concentrated in `modes/interactive/` (the only exception is `src/core/resource-loader.ts:3` using `detectCapabilities`/`getTerminalColorMode` for color capability detection, negligible in headless scenarios). It can be replaced wholesale, but behavioral differences show up directly in the interactive experience.

### 13.4 pi-mcp / pi-codemode

`extensions/mcp/*` uses `@earendil-works/pi-mcp`'s client/oauth/config; `extensions/codemode/*` uses `@earendil-works/pi-codemode`'s worker/source/declarations. Each forms its own subsystem; **when not implemented, it is enough to ensure they do not load and do not error**.

## 14. Scope recommendations

### 14.1 MUST / SHOULD / DEFER tiers

Estimation basis: Kotlin line counts include equivalent logic, data classes, serialization, and necessary test scaffolding; UI visual polish is excluded. Prompt text is carried over as-is.

| Tier | Subsystem | Basis | Kotlin estimate | Risk |
|---|---|---|---|---|
| **MUST** | CLI skeleton (semantics of `cli.ts`/`main.ts`/`args.ts`, exit codes, stdout/stderr contract, `@file`, stdin, `--offline`) | `main.ts:574-999` | 0.8-1.2k | Low |
| MUST | SessionManager (JSONL v1-v3 read/write, tree, projection, context_edit, fork/clone/continue/list/findById, deferred file creation, cwd escaping) | `session-manager.ts` | 2.0-2.6k | Medium: many format details; bidirectional compatibility must be tested |
| MUST | AgentSession core (run/turn loop, prompt flow, queues, abort, events, model/thinking, tool registration and loadout, system prompt patch replay, bash recording, stats) | `agent-session.ts` | 3.5-4.5k | High: complex state machine; boundary cases are easy to miss (settle/retry/compaction interleaving) |
| MUST | Tools read/bash/edit/write + truncate + file mutation queue + bash executor + process-tree kill | `core/tools/*`, `bash-executor.ts` | 1.6-2.2k | Medium |
| MUST | Tools grep/find/ls (calling rg/fd) | `grep.ts`, `find.ts`, `ls.ts` | 0.9-1.3k | Medium: external binaries + output format/truncation notices must match |
| MUST | System prompt sections + context files + skills injection + prompt template expansion | `system-prompt.ts`, `resource-loader.ts`, `skills.ts`, `prompt-templates.ts` | 1.2-1.6k | Medium: text details affect model behavior |
| MUST | SettingsManager (two-level merge, defaults, `+/-` tool modifiers, write-back) | `settings-manager.ts`, `settings-defaults.ts`, `settings-schema.ts` | 0.9-1.3k | Medium: merge rules are easy to get wrong |
| MUST | auth.json + models.json + model resolution (`--model` rules, scope, thinking suffix, default fallback) + provider composition + (minimal) provider layer | `auth-storage.ts`, `model-runtime.ts`, `model-resolver.ts`, `provider-composer.ts` + provider | 2.0-2.6k (+provider 3-5k) | High: provider streaming protocols/tool calls/thinking/usage are deep water |
| MUST | print + json modes (strict JSONL, event compaction, header, backpressure, stdout takeover) | `print-mode.ts`, `json-event.ts`, `output-guard.ts`, `jsonl.ts` | 0.6-0.9k | Low-medium |
| MUST | compaction (threshold, cut points, summary format, entries, overflow recovery) + branch summary | `compaction/*` | 1.3-1.8k | Medium: many details in cut points and repeated-compaction rules |
| MUST | Basic settings/diagnostic output (warnings/errors to stderr; fail when non-interactive with no model) | `main.ts:878-931` | 0.2k | Low |
| **SHOULD** (first batch of option A) | Full RPC mode commands + extension UI subprotocol + shutdown semantics | `rpc-mode.ts`, `rpc-types.ts` | 1.2-1.6k | Medium: broad protocol surface, but automatically verifiable |
| SHOULD | Minimal interactive TUI (see §11.2) | `interactive-mode.ts` + components | 4-9k (approach-dependent) | High |
| SHOULD | Remaining resource system: themes loading, package declaration parsing (read-only, no install), trust.json, `SYSTEM.md`/`APPEND_SYSTEM.md` | `resource-loader.ts`, `trust-manager.ts` | 0.8-1.2k | Low-medium |
| SHOULD | HTML export + `/export` jsonl | `export-html/*`, `session-export.ts` | 0.8-1.2k | Medium: templates/ANSI→HTML need polishing |
| **DEFER** | Extension system (TS compatibility or Kotlin SPI) | `core/extensions/*` + `extensions/*` | SPI 2.5-4k / Node sidecar 4-6k | Very high: ecosystem breakage |
| DEFER | MCP | `extensions/mcp/*` | 3-4k | High: protocol + OAuth + exposure policy |
| DEFER | package manager (npm/git installs, update, locks) | `package-manager*.ts` | 2.5-4k | High, and less valuable than the extension runtime itself |
| DEFER | Virtual models, classifier/image model calls, cache warmer, remote catalog refresh | `virtual-models.ts`, parts of `model-runtime.ts` | 1-2k | Medium |
| **DROP** | codemode, tool_search, llama.cpp extension, share/bug/telemetry/version-check, easter egg/armin/3D, mermaid/LaTeX/syntax highlighting, fullscreen+mouse, inline images, self-update, Windows self-update, first-time-setup, changelog display | See §9, §11.2 | 0 | None (but explicitly document the absence) |

### 14.2 Three boundary options for "a part"

#### Option A: headless kernel (recommended first)

Scope: all of the MUST items above; among SHOULD items, RPC mode + the remaining resource system (themes read-only, trust, SYSTEM.md); no TUI, no extensions, no MCP/codemode/package management/HTML export.

- Estimate: **15-20k lines of Kotlin** (excluding the provider layer); counting a minimal provider layer (OpenAI-compatible + Anthropic protocols), **18-25k**. One full-time person: 10-14 weeks; two people: 6-8 weeks; at an "evenings + agent assistance" pace: 3-5 months.
- Risk: medium.
  - Session format bidirectional compatibility (generate samples with real pi for golden tests).
  - Event stream JSON shape (byte-diff against `pi --mode json` output, ignoring timestamps/ids).
  - Incremental tool-call parsing in provider streaming (Anthropic's input_json_delta, OpenAI's tool_calls fragments) is a known hard part.
- Verifiable completion criteria (recommended as CI-level acceptance):
  1. `pi -p "list the src directory"` works on a real repository, exit code 0, stdout contains only the final answer.
  2. `pi --mode json` output has a valid session header as its first line, and subsequent events parse with the existing `jq` examples; with a fixed mock provider, the event sequence differs from real pi only in timestamps/random ids.
  3. RPC works with `examples/rpc-client.ts` or a hand-written Python client: prompt→streaming text→`agent_settled`; `get_state`/`get_entries`/`get_tree`/`compact`/`set_model`/`bash` all return structured data.
  4. Session interoperability: a `~/.pi/agent/sessions/...jsonl` produced by Kotlin opens in real pi via `--session` and continues correctly; a session produced by real pi opens in Kotlin via `--continue` with model/thinking restored correctly.
  5. compaction: build an over-threshold session, trigger automatic compaction, and confirm `buildSessionContext` replaces old messages with the summary and starts the retained region at `firstKeptEntryId`; compacting again does not lose the previous retained region.
  6. Tool behavior: `read` truncation notices, `edit` multi-edit/CRLF/BOM, `bash` abort killing the process tree, and `grep/find/ls` limits and notices match real pi output (string-level assertions).
  7. Settings: reading the real local `settings.json`/`models.json`/`auth.json` does not error, and the default model resolves to `opencode-go/deepseek-v4.1-flash`.

#### Option B: A + minimal interactive TUI

Scope: A + the 7-item minimum set from §11.2 (JLine3 recommended), regular mode (using the terminal's own scrollback), no mouse/images/fullscreen.

- Estimate: A + **4-9k lines** (JLine3 approach 4-6k; hand-written 6-9k). Adds 4-8 weeks.
- Risk: high. Differences in terminal behavior, wide characters, IME, narrow terminals, and Mac/Linux/SSH variations all become long-term bug sources; these bugs are hard to regress automatically.
- Verifiable completion criteria:
  1. A pty script (`expect`/`pexpect` or a tmux pipe) completes: start → enter a prompt → see streaming text and tool blocks → answer finishes → `/quit` with exit code 0.
  2. Chinese input (paste Chinese + single-line editing + cursor movement) does not garble or misalign at 80/120/200 columns.
  3. Esc interrupt: pressing Esc during a run delivers `agent_settled` and returns queued messages to the editor.
  4. `/model`, `/thinking`, `/session`, `/compact`, `/new`, `/resume` all work; `/resume` lists sessions across 45 real project directories and opens them successfully.
  5. Window size switching/resize does not crash or leave half lines.

#### Option C: A + B + extension compatibility

Two sub-options; one must be chosen first:

- **C1 Kotlin SPI**: define JVM plugin interfaces (`registerTool`, `registerCommand`, and 8-12 hooks such as `input`/`context`/`tool_call`/`tool_result`/`before_agent_start`, plus a `ctx.ui` subset), loaded via ServiceLoader/JAR.
  - Estimate +2.5-4k lines, 2-4 weeks.
  - Risk: **completely incompatible with the existing 13 npm extensions, 6 skill packages, and builtin:mcp**; the user's pi-subagents/pi-memory/pi-chrome and so on all stop working.
- **C2 Node sidecar**: keep a small Node/Bun process, load TS extensions with pi's extension loader, and communicate with the Kotlin main process over JSONL (essentially RPC in reverse).
  - Estimate +4-6k lines (protocol, lifecycle, error recovery, UI bridge), 4-8 weeks.
  - Risk: **keeps a Node runtime dependency**, conflicting with the "Kotlin rewrite" goal; two-process debugging is costly; the extension's synchronous APIs (`pi.getActiveTools()`, etc.) must be redesigned across processes.
- Verifiable completion criteria (shared): using the real local `settings.json`, load at least 3 of the 13 packages (for example pi-tool-display, pi-rewind, pi-web-access representing "renderer/session rewrite/tool"), and have their registered commands and tools work in the Kotlin version; an extension throwing must not bring down the main process.

### 14.3 Recommendation

**Recommended: do option A first, then option B if warranted; defer option C, but leave extension points in A's design.**

Reasons (weighted):

1. **A is the only machine-verifiable part**. RPC/JSON protocols have external client examples and type definitions (`rpc-types.ts`), session formats have `session-format.md` and real samples, and tool behavior has deterministic string output. This makes "is the rewrite correct" independent of manual trial, which suits the current multi-person/multi-agent porting project especially well.
2. **A can coexist with existing pi**, without requiring users to switch their daily tool immediately. The user's current pi ecosystem (13 packages + MCP + skills) stays usable while the Kotlin version matures as a "kernel/scripting/future GUI foundation".
3. **A has the highest return per unit of work**: about 15-20k lines in MUST covers all of pi's substantive coding-agent capability (models, tools, sessions, context, compaction). The remaining 57k lines are terminal experience and ecosystem, with diminishing returns and sharply rising risk.
4. **B is worth doing, but must come after A**. TUI correctness standards are subjective, and if the TUI comes first, all protocol/format problems get masked by the UI and rework costs more.
5. **C has the worst benefit/cost ratio and is a strategic rather than engineering choice**. Neither route is "clean": C1 discards the user's existing ecosystem outright, while C2 admits that Node must come along. **Do not touch C until the user explicitly answers "should the Kotlin version replace daily pi".** If the answer is yes, the realistic recommendation is C2 (Node sidecar) rather than C1, because ecosystem value far outweighs process purity.

**One-sentence conclusion**: split coding-agent into "kernel (MUST, worth a full port) / interaction (SHOULD, minimal set) / ecosystem (DEFER, pending a strategic decision)"; deliver A first and use real pi for golden comparisons — the lowest-risk path to verifiable results fastest.

### 14.4 Questions requiring the user's decision

1. Kotlin version positioning: replace daily pi (then extension compatibility must be solved), or a kernel/experiment/future GUI foundation (then long-term coexistence is fine)?
2. Provider scope: only OpenAI-compatible + Anthropic APIs (covering all currently used models), or are OAuth/Bedrock/Google required?
3. Session format: require "real pi can open Kotlin sessions" (bidirectional, +10-15% cost) or only "Kotlin can read real pi sessions" (one-way, lower cost)?
4. Interactive UI: is a minimal TUI with regular mode (terminal's own scrollback) + no mouse/inline images acceptable?
5. Extension strategy: accept "the Kotlin version has no extension ecosystem", or is Node bridging mandatory?
6. Distribution form: a single-file executable (GraalVM native / jpackage) or is `java -jar` acceptable? Startup-time requirements (JVM cold start 100-300ms, native 20-50ms)?

