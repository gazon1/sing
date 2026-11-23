# 3098 — Evaluate scoped toolsets for the built-in Koog agent

Status: research recorded 2026-11-23 (workspace `gazon1/research-ai-evaluate-scoped-toolsets-for-the-bui`).
Outcome: **no production change recommended yet.** The evaluation measures what scoping can and cannot do with the
metadata the repository owns today, and it names the smallest next step.

## Question

Does offering the built-in agent a *subset* of its toolbox — chosen per request, per intent — beat offering the whole
toolbox? A valid answer is "no".

## The current tool-selection path

1. **Registration.** `shared/src/{jvmMain,androidMain}/.../core/di/AiToolsModule.*.kt` bind 45 tool instances and
   collect them into `single<List<Tool<*, *>>>`. The list is maintained by hand; `ToolSurfaceWiringTest` compares it with
   `McpToolCatalog` on both platforms (`shared/src/androidHostTest/…` and `desktopApp/src/jvmTest/…`), so a tool in one
   list and missing from the other fails the build.
2. **Filtering — the only filter there is.** `KoogAgentService` reads `RemoteConfigSnapshot.mcpToolFlags` **once at
   construction** and applies `filterTools` (`shared/src/commonMain/kotlin/com/singularity/todo/core/ai/AiToolFilters.kt`):
   a flag of `false` for a tool's descriptor name removes the tool, a missing flag keeps it. The flag key is the tool's
   descriptor name — the same name the catalogue declares and the agent calls. This exists for remote-config kill
   switches; it was never an intent-scoped selection.
3. **Offering.** The surviving tools are wrapped by `RecordingTool` (when a recorder is present) and collected into a
   Koog `ToolRegistry`. That registry feeds both entry points: `generate()` (a full Koog `AIAgent`) and `streamChat()`
   (`core/ai/ToolCallLoop`, the hand-written loop the chat screen uses).
4. **The catalogue.** `McpToolCatalog` declares, per tool: name, class, **domain**, MCP annotations and
   **writeBehavior** (`READ_ONLY` / `PROPOSAL_WRITE` / `DIRECT_WRITE_EXCEPTION`). It owns the *declared surface* of the
   MCP server. The in-app agent does **not** read it — the in-app agent gets the DI list — but the `domain` field is
   the only grouping metadata the repository owns, so it is what a scoped candidate has to be built from.

The write boundary is a property of the *tool* and of the proposal pipeline, not of the toolbox: a scoped toolbox
changes **which tools are reachable**, never **what a tool does when reached**.

## What the catalogue offers today

45 tools, measured from `McpToolCatalog.catalog`: 30 `READ_ONLY`, 13 `PROPOSAL_WRITE`, 2 `DIRECT_WRITE_EXCEPTION`
(`write_adr`, `update_adr`).

| domain   | tools | write tools |
|----------|-------|-------------|
| `ai`     | 15    | 0           |
| `tasks`  | 10    | 5           |
| `notes`  | 6     | 3           |
| `adr`    | 6     | 2 (direct)  |
| `projects` | 5   | 3           |
| `tags`   | 2     | 2           |
| `docs`   | 1     | 0           |

## Method

`shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/ScopedToolsetEvaluationTest.kt`

- **Same scenario, same scripted model, same turn** in both conditions.
- **Full** condition: all 45 catalogue tools. **Scoped** condition: the catalogue tools of the scenario's domain(s)
  **plus the cross-cutting `ai` domain**.
- **Why `ai` is in every scope — an explicit decision of this evaluation.** The catalogue's `domain` groups tools by
  *kind of tool*, not by what they act on, so the `ai` tools are exactly the ones that operate on another domain's
  data (`summarize_note`, `rewrite_note`, `extract_actions`, `decompose_task` …). A scope that dropped them would
  remove the tools a real request needs, so the candidate scope is *domain(s) ∪ {`ai`}* — and the price of that
  choice is measured below, not assumed.
- The scope is **intersected** with the switched-on set in both conditions, so a scope can never resurrect a disabled
  tool — the two filters compose into an intersection, not into an override.
- The model is scripted: it calls the tool the scenario expects, then one plausible wrong tool from another domain,
  then answers in words.
- The write-proposal scenario drives the **real** `UpdateTaskTool` against the shared fakes, so the proposal boundary
  is checked against the user's data — not against the tool's own report of what it did.

Scenarios (5): task lookup (`tasks`), note read (`notes`), project listing (`projects`), planning (`tasks`+`ai`),
task update / write-proposal (`tasks`, real tool).

## Results

Measured by `ScopedToolsetEvaluationTest` (5 tests, green).

| Scenario | Scope | Tools offered (full → scoped) | Write tools reachable (full → scoped) | Expected tool honoured (full / scoped) | Cross-domain call (full / scoped) |
|---|---|---|---|---|---|
| task lookup | `tasks`+`ai` | 45 → 25 | 15 → 5 | yes / yes | honoured / refused |
| note read | `notes`+`ai` | 45 → 21 | 15 → 3 | yes / yes | honoured / refused |
| project listing | `projects`+`ai` | 45 → 20 | 15 → 3 | yes / yes | honoured / refused |
| planning | `tasks`+`ai` | 45 → 25 | 15 → 5 | yes / yes | honoured / refused |
| task update — real `UpdateTaskTool` | `tasks`+`ai` | 45 → 25 | 15 → 5 | yes / yes — proposal created, task unchanged | honoured / refused |

What the numbers say:

1. **Scoping does not break the workflow it is for.** Every scenario's expected tool is offered and honoured in both
   conditions.
2. **The model asks the same amount in both conditions** — two calls, scripted identically. Scoping does not make the
   agent less chatty; it changes what a call can reach.
3. **Containment is the one behaviour difference.** The full toolbox *honours* the cross-domain call; the scoped
   toolbox *refuses* it, and the model reads "There is no tool named …". The price of the full toolbox is that a
   mistake lands on a real tool: 15 write-capable tools are reachable, 13 of them proposal-gated and **2 of them
   direct writes** (`write_adr`, `update_adr` — the catalogue's `DIRECT_WRITE_EXCEPTION` pair).
4. **The write/proposal boundary is untouched.** With the real `UpdateTaskTool` over the shared fakes, both
   conditions propose the change and leave the task's title as it was, with exactly one proposal waiting for the
   user. Scoping adds no safety here — the boundary belongs to the tool — and the harness shows scoping does not
   weaken it either.
5. **The kill switch still wins.** A tool switched off by a flag is off in a scoped toolbox too: the scope and the
   switch compose into an intersection, and neither can re-enable what the other removed.
6. **The narrowing is weaker than it looks.** The `ai` domain is cross-cutting, so every scope carries it: a notes
   scenario needs the 6 `notes` tools *and* the 15 `ai` tools (`summarize_note`, `rewrite_note`, `extract_actions` …),
   because the catalogue's `domain` groups tools by *kind of tool*, not by *what they act on*. The narrowest scope
   measured here — `projects`+`ai` — is 20 of 45 tools, a 56 % reduction; the notes scope is 21 of 45. A scope small
   enough to sharpen a model's attention does not follow from the metadata that exists today.

## What this evidence does not show

- **Selection accuracy.** A scripted model cannot measure how well a *real* model chooses. Whether fewer tools make a
  real model pick better is the question the issue asks, and it is exactly the question this harness does not answer.
- **Cost.** Token cost and latency of 45 tool schemas versus a scoped set are not measured here.
- **Whether the real model behaves like the scripted one at all.** The harness measures the surface, not the model.
- **The `generate()` entry point.** The harness drives `ToolCallLoop` — the path the chat screen uses. It does not build
  the Koog `AIAgent` that `generate()` runs, so it says nothing about that entry point's tool registry; the registry is
  assembled from the same list either way.

## Recommendation

**No production change now.** The one reproducible difference scoping makes today is *containment*: a cross-domain call
is refused instead of honoured. That is a real property, and it is worth having — but it is a property the **existing
kill switch already has** (`mcpToolFlags` can turn any tool off, including a whole domain), and containment is not the
benefit the issue was after.

The narrowing is also weaker than it looks: a scenario about notes needs the 15 cross-cutting `ai` tools as well
(`summarize_note`, `rewrite_note`, `extract_actions` …), because the catalogue groups tools by *kind of tool*, not by
*what they act on*. The narrowest scope measured here is 20 of 45 tools — a 56 % reduction, not a small toolbox. A
scope that is small enough to sharpen a model's attention does not follow from the metadata that exists today.

### Smallest next step

1. **No code — run the live A/B through the existing extension point.** `mcpToolFlags` already expresses "these tools
   are off": point it at a domain scope and the agent is offered exactly that scope today, with no production change.
   Run the same five scenarios against a live model in both conditions and record model/provider, system prompt,
   flags, tool calls and outcome. Recipe below.
2. **Only if that shows a real gain**, implement intent → scope mapping, composed as *kill switch first, scope
   second*, and keep the scope as data on the catalogue entry — not as a second registry, and not as a new
   `AgentProfile` type (ACP's `AcpAgentProfile` owns that word).

### Trade-offs to carry into any future implementation

- A scope that is too narrow breaks cross-domain requests ("move these tasks into that project" needs `tasks` **and**
  `projects`); the refusal is visible to the model as "There is no tool named …", so the cost is a failed turn.
- The `ai` domain is cross-cutting and must always be offered, so no scope can ever be small.
- Scoping must not touch write classification or the proposal path: the boundary is per tool, and the contract tests
  (`TaskProposalWriteContractTest` and siblings) are what keep it true.
- MCP clients are unaffected — they have their own surface and their own catalogue projection.
- The kill switch must stay the last word: a scope narrows the enabled set, it never re-enables anything.

## Manual live-model experiment (explicitly manual; CI never depends on it)

| Item | Value |
|---|---|
| Model / provider | the app's configured provider (e.g. OpenAI-compatible `gpt-4o-mini`, the project's fallback) |
| System prompt | `Prompts.chatSystem` (`feature/ai/prompts/Prompts.kt`) — unchanged in both conditions |
| Condition A (full) | `mcpToolFlags` empty |
| Condition B (scoped) | `mcpToolFlags` = `false` for every catalogue tool whose `domain` is outside the scenario's scope ∪ {`ai`} |
| Service lifecycle | the flags are read **once**, when `KoogAgentService` is constructed — apply the flags and let the service be rebuilt before the run, otherwise the previous condition's toolbox is still in place |
| Scenarios | the five in `ScopedToolsetEvaluationTest`, plus the same prompt asked twice per condition for variance |
| Recorded per run | tools offered **by name**, tools called (name + args), tool order, outcome, number of rounds, wrong-tool calls, retries |
| Limitation | one model, one provider, one prompt; no statistical claim; results are anecdotal and must be read as such |

## Reproducing

```sh
./gw :shared:jvmTest --tests "*ScopedToolsetEvaluation*"
```
