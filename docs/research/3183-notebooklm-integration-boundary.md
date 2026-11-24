# 3183 — NotebookLM integration boundary: spike report

Status: research recorded 2026-11-25. Workspace
`gazon1/research-ai-decide-and-validate-the-notebooklm-i`. **No source was changed.**
Decision: [2026-11-25-notebooklm-stays-outside-the-app.md](../decisions/2026-11-25-notebooklm-stays-outside-the-app.md).

## Outcome in one paragraph

The current boundary holds: **the external agent drives NotebookLM, Sing stores and shows
the brief.** Neither in-app option is justified today. The optional MCP route is blocked by
the provider itself (the `notebooklm-mcp` entry point does not start in this installation),
and the CLI route would make Sing own a destructive, shared server-side conversation that no
Kotlin adapter can make safe. The live half of the acceptance criteria — create a notebook,
ingest a source, query it with citations — **could not be executed**: the local profile is
authenticated locally but its Google session is expired, and re-authenticating needs a
human-driven browser login. Everything below marks whether it was *observed* or is an
*assumption*.

## Versions and environment

| What | Value | How taken |
|---|---|---|
| `notebooklm-py` | **0.8.6** (build `956400aa`) | `notebooklm --version`; `notebooklm_py-0.8.6.dist-info/METADATA` |
| CLI entry point | `/home/max/.local/share/uv/tools/notebooklm-py/bin/notebooklm` | `readlink -f` |
| Extra entry points | `notebooklm-mcp`, `notebooklm-server` | `entry_points.txt` |
| Python | 3.12.15 (uv-managed) | `notebooklm --version` banner |
| Profile | `default`, `authenticated` | `notebooklm profile list` |
| Local auth material | 23 cookies, `storage_state.json` + `context.json` | `notebooklm doctor` |
| OS | Fedora 44, linux 7.2.10 x86_64 | session environment |
| 10x-chat reference | `main` @ clone 2026-11-25, `src/knowledge/{contract,notebooklm}.ts` | `git clone --depth 1` |
| Sing Koog | **1.3.0** | `gradle/libs.versions.toml:122` |
| Sing MCP SDK | `io.modelcontextprotocol:kotlin-sdk` **0.16.0** | `gradle/libs.versions.toml:173` |

## The blocker, precisely

`notebooklm auth check` passes every *local* check and fails the *online* one:

```
│ Auth             │ ✓ pass │ local auth cookies usable (23 cookies)          │
│ Headless Reauth  │ ✓ pass │ ready (persistent profile + playwright present) │
Authentication source: file (…/storage_state.json) (local checks only)
No local failures detected. Online authentication was not tested.
```

`notebooklm --profile default auth check --test --passive` exits **1** and reports:

```
Token fetch failed: Authentication expired or invalid.
https://notebook.google.com/ served a signed-out page, and its sign-in check redirected
to https://accounts.google.com/<redacted>. Run 'notebooklm login' to re-authenticate.
```

Every data command returns the same envelope, exit code **1**:

```json
{
  "error": true,
  "code": "AUTH_ERROR",
  "message": "Authentication error: Authentication expired or invalid. …"
}
```

Observed for `list`, `ask -n <fake> --json -- "hello"`, `source add <url> -n <fake> --json`,
`source refresh <fake> -n <fake> --json`, `source wait <fake> -n <fake> --json` (the last
three against deliberately non-existent ids, so nothing could be mutated).

**Why this was not worked around.** `notebooklm login` opens a browser and requires the
account owner to complete Google's sign-in. That is a human-only action, and the issue's
boundary forbids importing Google credentials into Sing or driving the account from the
agent. So the acceptance criterion "a disposable notebook can be created/selected and
queried with source citations" is **not met, and the blocker is authentication, not the
tool**. The offline contract below is what remains verifiable, and it is enough to decide
the boundary.

## What is observed about the CLI contract

Read from the installed 0.8.6 package and its `--help`, cross-checked against the JSON
envelope builders (not against upstream `master`):

| Operation | Command the adapter sends | JSON shape it must parse | Matches 0.8.6 |
|---|---|---|---|
| status | `auth check --test --passive --json` | `status: "ok"\|"error"`, `details.error`, `profile` | **yes** — observed, exit 1 in the `error` state |
| notebooks | `list --json` | `{"notebooks":[{id,title,role,created_at}]}` | yes — `cli/notebook_cmd.py:88` `items_key="notebooks"` |
| create | `create --json -- <title>` | `{"notebook":{id,title,role,created_at}}` | yes — `_notebook_mutation_payload` |
| add source | `source add <url> --notebook <id> --json` | `{"source":{id,title,type,url}}` | yes — `_source_render.py:657` |
| list sources | `source list --notebook <id> --json` | `{"sources":[…row…]}` | yes — `source_cmd.py:1230` |
| refresh | `source refresh <id> --notebook <id> --json` | `{action,source_id,notebook_id,title?,status:"refreshed"}` | yes — `_source_refresh_payload`, `_source_render.py:564` |
| ask | `ask --notebook <id> [--source …] --json -- <q>` | flat `answer`, `conversation_id`, `references[]` | yes — `ask_result_view` over `AskResult` |
| readiness | **not called by the adapter** | `source wait` → exit 0/1/2 | yes — `source wait --help`: `0=ready, 1=missing or failed, 2=timeout` |

Error codes: the adapter's `FAILURE_KIND_OF` / `DEFINITE_REFUSALS` sets name `AUTH_ERROR`,
`VALIDATION_ERROR`, `CONFIG_ERROR`, `NOTEBOOK_LIMIT`, `NOT_FOUND`, `RATE_LIMITED`,
`NETWORK_ERROR`, `NOTEBOOKLM_ERROR`, `ARTIFACT_TIMEOUT`, `CANCELLED`,
`UNCONFIRMED_WRITE`. All eleven exist in 0.8.6's error ladder / `_app/errors.py` table.
The `UNCONFIRMED_WRITE` override is emitted at `cli/error_handler.py:317` and replaces the
branch's code — so a script cannot get `RATE_LIMITED`-retry advice on a write whose outcome
is unknown. That is the behaviour the issue requires, and it is real, not aspirational.

So **the 10x-chat adapter is accurate against the version installed here.** Its contract is
reusable as a *specification* of the failure semantics.

## Three findings that decide the boundary

### 1. Asking a question is a mutation of shared server-side state

`notebooklm ask` with no `--new` and no `-c` continues the notebook's current conversation,
and on success the CLI persists the new id:

```python
# cli/chat_cmd.py:418
if result.conversation_id:
    set_current_conversation(result.conversation_id)
```

That writes `conversation_id` into the profile's `context.json` — the same file that holds
`notebook_id`. It exists on this machine:

```json
{ "notebook_id": "edce8651-…", "title": "My Research",
  "conversation_id": "bede80a3-…" }
```

The 10x-chat adapter marks `ask` as `mutating: true` for exactly this reason, but it passes
neither `--new` nor `-c`, so it **inherits whatever conversation is current and then moves
it**. Two callers on one profile interleave into one conversation, and a question is not a
read. `#3189` requires "notebook/profile selection is explicit; no accidental 'current
notebook' behaviour" — the reference adapter does not meet that bar as written.

The obvious repair is worse. `ask --new` is documented as **DESTRUCTIVE**: it "deletes the
notebook's current server-side conversation (turns are not recoverable)". An app driving
this would offer a button that silently destroys another client's research history in a
notebook the user shares with the web UI.

### 2. The MCP surface does not start in this installation

`notebooklm-mcp` exists as a console script and the package ships `mcp/` with 19 tool
modules, but it fails immediately:

```
File "…/notebooklm/mcp/server.py", line 30, in <module>
    from fastmcp import FastMCP
ModuleNotFoundError: No module named 'fastmcp'
```

The `mcp` extra is simply not installed in this uv tool environment. That is fixable
(`uv tool install 'notebooklm-py[mcp]'`), but it is the honest current state, and it means
the "optional remote MCP connection" option would ship against a surface **nobody has
exercised here**. Sing has no production MCP *client* either: `io.modelcontextprotocol` is
imported in `shared/src/jvmMain` for the **server** side only, and the client classes
(`Client`, `StdioClientTransport`, `StreamableHttpClientTransport` — the latter two are in
the 0.16.0 jar) appear only in `mcp-server/src/test`. The SDK is JVM-only by design; the
build file says nothing in `commonMain`/`androidMain` may depend on it. So option 2 is: a
new runtime dependency, a new client host inside the built-in agent, and a provider surface
that is unverified here.

### 3. The JVM subprocess seam exists, but has no timeout, and adding one buys the danger

`shared/src/jvmMain/kotlin/com/singularity/todo/core/process/Subprocess.kt` already runs
children without a shell (`ProcessBuilder(argv)`), folds stderr away from stdout, drains
both, and `ProcessTree` can terminate a whole tree with a grace period. That is most of what
a CLI adapter needs, and it is cheaper than the 10x-chat port assumed.

What is missing is a **deadline**: `runCapturing` has no timeout parameter — it reads to
EOF, then waits — and `spawnDuplex().awaitExit(t)` returns `null` on timeout but does not
kill. So a hung `notebooklm ask` blocks a coroutine until the CLI's own HTTP timeout fires.
Adding a deadline is small work. The problem is what the deadline *means*: killing the CLI
mid-`source add` leaves the outcome unknown, and the fix for that is the `mayHaveRun`
contract — which is a real, non-trivial correctness surface (see
[the 10x-chat adapter](https://github.com/gazon1/10x-chat/blob/main/src/knowledge/notebooklm.ts))
that neither Android nor desktop has any use for when the agent does the research.

## Alternatives evaluated

**WeKnora — rejected as canonical store, not evaluated for deployment.** Taking it as Sing's
knowledge store introduces a second mandatory database beside Notes, which the epic's first
product invariant forbids ("Notes remain canonical. No second mandatory document database.").
It was not deployed, because the decision does not depend on it: a knowledge platform that
owns the data loses on architecture before it wins on features. Recorded as a bounded
rejection, not a benchmark.

**NotebookLM as a Sing-side renderer.** Not viable: the CLI's only export of an answer is
its own conversation history (`history`) and notes (`note`), and the useful artifact for a
brief is a source-attributed synthesis that an agent composes anyway. Nothing is gained by
having the app ask the question instead of the agent.

## Operations a selected boundary must support, and their failure semantics

This is the list `#3189` should be built against **if** it is ever built. It is written
down now so the decision record can be checked against reality later.

| Operation | Mutating | Failure semantics that must be honoured |
|---|---|---|
| status | no | `AUTH_ERROR` with an expired session is *not* `unavailable` — the tool is present and the human must log in |
| list notebooks | no | empty list is not a failure; `AUTH_ERROR` / `NETWORK_ERROR` are |
| create notebook | **yes** | never blind-retry; `mayHaveRun` on timeout or `UNCONFIRMED_WRITE` |
| add source | **yes** | a failed add may leave an orphan row at `preparing` that still costs quota (`source list --status preparing` is the query that finds it) |
| refresh source | **yes** | `status: "refreshed"` is the JSON envelope; a `None` result is *success*, not empty |
| ask | **yes** | mutates the shared conversation id; must be explicit, never implicit |
| readiness | no | `source wait` exit 0/1/2 — 2 is a timeout, and the source may still become ready |
| cancel | — | a killed CLI leaves every mutation in `unknown_state`; there is no resumable handle |

## What Android and JVM Desktop can each support

| | Android | JVM Desktop |
|---|---|---|
| Python/CLI present | **no** — must never be | possible, user-installed |
| MCP client SDK | **no artifact** (JVM-only, by build rule) | available in 0.16.0, unused in production |
| Subprocess seam | **does not exist** (no `commonMain` port) | exists, no deadline |
| Network to Google | irrelevant — the provider is out of process | same |

The asymmetry is the point: any in-app integration is a desktop-only capability that the
shared Knowledge workflow must not require. The epic already says this ("Platform-specific
external provider capability may differ and must be explicit"), and this report confirms it
is not a temporary gap — it is the SDK's shape.

## Rejected alternatives, in one line each

- **Remote MCP connection from the built-in agent**: rejected — would add an MCP client
  host, new dependency, and a shared-conversation hazard, for a surface that does not start
  in this installation and that no Sing code can test in CI without a live Google account.
- **JVM Desktop CLI adapter**: rejected — desktop-only, needs a kill/timeout/`mayHaveRun`
  correctness surface, and it would let Sing destroy a shared conversation with `--new`.
  The subprocess infrastructure exists, but correctness here is not cheap.
- **WeKnora as canonical store**: rejected — second source of truth; no deployment
  attempted, because architecture decides before benchmarks.
- **A provider abstraction in `commonMain`**: rejected — the signal is not in the domain,
  and the accepted ADR forbids naming a provider there.

## Go / no-go for #3189

**No-go.** Keep NotebookLM in the external-agent workflow, which is what
`docs/architecture/knowledge-briefs.md` already documents and what the external agent
already does. No code, no dependency, no boundary change.

Revisit when **all** of these are true:

1. Someone actually needs NotebookLM invoked without an agent in the loop.
2. A live, disposable-account smoke test is available in the loop (the reason this spike
   stopped at the contract).
3. `ask` has a non-destructive, explicit-conversation mode, or the workflow never reuses a
   notebook that the web UI also drives.

## Acceptance criteria, honestly

| Criterion | State |
|---|---|
| Disposable notebook create/select/query with citations | **not met** — expired Google session; blocker documented above |
| CLI/MCP output and auth verified against a pinned version | **partly** — CLI 0.8.6 contract verified offline from the installed package and `--help`; the MCP surface **does not start** (missing `fastmcp`) |
| Unknown mutation outcomes treated as possibly-executed | **yes** — `UNCONFIRMED_WRITE` exists in 0.8.6 and overrides the retry advice; the decision forbids blind retry |
| Decision preserves Notes as canonical store | **yes** |
| Android acquires no Python runtime or shell dependency | **yes** — unchanged |
| WeKnora not adopted as a mandatory database | **yes** — rejected |
| Knowledge Brief doc updated only if the contract changes | **no change needed** — the boundary is unchanged |

## What was not checked

- The live answer/citation shape (`answer`, `references[].source_id`, `citation_number`)
  is read from `_types/chat.py` and `_app/views.py`, **not** from a real answer. The exact
  `[N]` marker placement in a returned answer is unverified.
- Source readiness latency, and whether `source wait` exit 2 still becomes `ready`.
- Whether retrying `source add` after an `UNCONFIRMED_WRITE` duplicates the source. The CLI
  has an idempotency probe that raises the marker, which suggests it may not duplicate, but
  that is an inference from the code, not a measurement.
- WeKnora's actual API/MCP maturity. Its architecture was rejected on the second-store rule
  before it was worth measuring.

## Links

- Issue [#3183](https://github.com/gazon1/sing/issues/3183), parent [#3182](https://github.com/gazon1/sing/issues/3182), consumer [#3189](https://github.com/gazon1/sing/issues/3189)
- [Decision record](../decisions/2026-11-25-notebooklm-stays-outside-the-app.md)
- [The brief contract in force](../architecture/knowledge-briefs.md)
- 10x-chat: [`contract.ts`](https://github.com/gazon1/10x-chat/blob/main/src/knowledge/contract.ts), [`notebooklm.ts`](https://github.com/gazon1/10x-chat/blob/main/src/knowledge/notebooklm.ts), [`knowledge-notebooklm.test.ts`](https://github.com/gazon1/10x-chat/blob/main/tests/knowledge-notebooklm.test.ts)
- `notebooklm-py` 0.8.6: `cli/error_handler.py`, `cli/chat_cmd.py`, `_app/errors.py`, `_app/views.py`
- Sing: `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/Subprocess.kt`, `shared/build.gradle.kts` (MCP SDK is JVM-only), `mcp-server/src/test/…/McpServerEndToEndTest.kt` (the only MCP client)
