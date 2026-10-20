# Design — agent-toolbox-surface

## The finding that shaped the work

The audit ran before any code changed, and it found more than the change was scoped for.
The table below is the audit's output; the *in this change* column is the scope freeze.

| # | Finding | Evidence | Root cause | In this change? |
|---|---|---|---|---|
| F1 | The catalogue's description differed from the tool class's for 32 of 39 tools | parsed from `McpToolCatalog.kt` and `feature/ai/tools/*.kt` | a fact with two owners | **yes** — the second copy is removed |
| F2 | Five catalogue entries described a *different capability* than the tool | `smart_rewrite`, `list_linked_tasks`, `suggest_tags`, `refine_task`, `search_tasks` | same | **yes** — resolved by the removal |
| F3 | Four `delete_*` tools were documented as permanent deletes; the tools create a proposal | `DeleteTaskTool` and friends | same | **yes** |
| F4 | The catalogue's docstring claimed the DI lists were derived from it; they are hand-written | `AiToolsModule.jvm.kt`, `.android.kt` | a claim nobody checked | **yes** — the docstring now says what is true, and a test checks it |
| F5 | The name sets agreed (39 ↔ 39) with nothing checking it | the same parse | no check | **yes** — a runtime test per platform |
| F6 | A remote-config switch was honoured by the in-app agent and ignored by the MCP server | `KoogAgentService.kt:62-68` vs `ToolRegistrar.kt:51-53` | one switch, two consumers | **yes** — and F16 corrected the premise: neither consumer honoured it |
| F7 | The knowledge was reachable only through a shell | 692 ADRs, 149 skills, 13 specs, no search | no tool | **yes** |
| F8 | No per-tool record existed on any path | `llm_usage` records `chat_generate`; CRUD tools record nothing | no observation | **yes** |
| F9 | The MCP build identity was a literal | `Main.kt:224` | a second version source | **yes** — the canonical `appVersion()` is used |
| F10 | The remote-config cache is a single global row | `RemoteConfigCacheEntity` | by design | **documented**, not changed |
| F11 | "Silent catches" in `ToolFactories.kt` and duplicated `try/catch` in `RefineTaskTool.kt` | *not found* in the current checkout | — | **no** — the hypothesis did not survive contact with the source |
| F12 | The skill `singularity-todo-kmp-platform-specific` named in a review | *does not exist* in `.agents/skills/` | — | **no** — skill paths are read from the checkout |
| F13 | `singularity-todo-cli-tool-surface` teaches `McpToolError.format(isError = true)` from shared code, and `McpToolError` lives in `:mcp-server` | the skill's examples | a document describing an API that does not exist | **yes** — the examples are corrected |
| F14 | `list_adrs` listed the `deferred/` backlog as decisions: `corpus.discover` walks the whole corpus, and the rule that says a backlog entry is not a decision (`isDecision`) was never applied at the read boundary | `AdrStorage.listAdrs` | a rule written down and not applied | **yes** — the rule is applied where the corpus is read |
| F15 | The archive was not excluded: `isArchived` matched a path *inside* `archive/` but not the directory itself, so the walk descended into it. The Kotlin reader was therefore **wider** than the Python one, which globs the top level only | `AdrCorpus.isArchived`, `AdrCorpus.walk` vs `scripts/adr_corpus.py` | a rule written for one shape of path, and two readers of one corpus | **yes** — the directory counts, and the two readers now agree |
| F16 | The kill switch was **inert everywhere**: the tool ID could not be resolved, so no flag ever matched a tool | `AiToolFilters.toolId()` looked for `NAME` on the *companion*; the Kotlin compiler puts the constant on the *class*, so the lookup raised `NoSuchFieldException` and the function returned `null` — and a tool with no ID is never disabled | a fallback that could not run, behind a `catch` that hid it | **yes** — the lookup falls back to the class, the ID is pinned by a test, and the switch is applied where it is claimed |

F13 deserves its own line because it is the same defect as F1, one level down: a
hand-written description of code that nobody diffs against it. The skill's two-tier error
mapping is a real idea — but the API it shows cannot compile from `:shared`, so an agent
following it writes code that does not build. The same file also listed eight tools that
do not exist (`tasks.pin`, `tasks.restore`, `notes.archive`, …) and called the `delete_*`
tools destructive when they create a proposal that needs confirmation.

F14 and F15 are the same class one level down again, and they were found *by* the new
code: the search inherited the inconsistencies, and the fixes belong to the rule's owner —
not to the search.

F16 is the sharpest one, and it was found by the *test* rather than by reading: the new
MCP test asserted that a switched-off tool is not registered, and it failed — because the
switch the whole design leans on never worked. The claim that the in-app agent had
honoured the flags since they existed (F6) was itself wrong, and the change that
implemented the switch on the MCP side would have inherited the same inert rule. The
rule was fixed at its owner, and the test now pins both shapes a tool class can have.

## The shape

```
:shared/commonMain
  core/observability/ToolCallLog.kt     the event, the port, the recorder
  core/ai/RecordingTool.kt              the in-app decorator
  core/ai/AiToolFilters.kt              the kill-switch policy — fixed (F16)
  feature/ai/tools/AdrTools.kt          + search_decisions
  feature/ai/tools/SearchDocsTool.kt    + search_docs
  mcp/McpToolCatalog.kt                 descriptions removed; two entries added

:shared/jvmMain · :shared/androidMain
  core/observability/FileToolCallLog.kt the writer, per platform
  core/di/PlatformModule.*.kt           the bindings
  core/di/AiToolsModule.*.kt            the two new tools

:adr-core/commonMain
  feature/ai/tools/AdrSearch.kt          the engine, the scopes, the envelope
  feature/ai/tools/AdrStorage.kt        + the search engine it publishes

:mcp-server
  mcp/ToolsetSnapshot.kt                the one snapshot, and the identity
  mcp/ToolRegistrar.kt                  the kill switch, the dispatch measurement
  mcp/ToolboxTool.kt                    describe_toolbox
  mcp/Main.kt                           the configuration read, the registry snapshot

:adr-cli
  cli/AdrSurface.kt                     + the search command, + the CLI↔MCP mapping
  cli/AdrCli.kt                         the search command
```

## Why the tool class owns the description

The description is what the MCP server puts into the JSON schema, so it is what the agent
reads before it acts. A second copy of that text is a promise about a promise, and 32 of 39
of them had stopped being true. The class is the place where the text cannot drift from
the behaviour, because the behaviour is defined next to it.

## Why the DI lists are checked rather than derived

Deriving them would need the catalogue to hold constructors, and the catalogue is parsed
as text by the Python renderer that writes the ADR sections. A runtime test proves what a
static derivation could only approximate: that Koin *resolves* the tools the catalogue
declares, on each platform, with the descriptions the classes carry.

## Why the kill switch fails open

The flags exist to take a broken tool out of the loop. That is an operational control, not
a safety boundary: a safety control would need to fail *closed*, and would need to be
distinguishable from "the configuration is stale". The status field carries that
distinction, and the ADR states the exposure risk rather than hiding it.

## Why the search is one engine

The corpus rules have one owner, `AdrCorpusConfig`, and four Python consumers already read
them. A second search engine would be a second set of rules. The two tools differ in what
they *report* — a decision with a status, a document with a path — and share everything
else.

## Why the telemetry is best-effort

A file format cannot promise exactly-once. The guarantee is therefore stated as what it
is: one record per completed attempt, a count of what was lost, and a write failure that
never reaches the caller. The report says "not observed in this window" rather than
"unused", because the difference is the whole point.

## What was deliberately not done

- No registry DSL. The duplication is real, but a shared declaration is a separate change
  with its own risk, and the checks above catch the drift it would prevent.
- No search index. Substring matching is enough at this size; measure before optimising.
- No telemetry database, no event bus, no generic logging framework.
- No CI gate over the telemetry: CI cannot produce it.
- No change to the agent's behaviour beyond discovery, access and observability.
