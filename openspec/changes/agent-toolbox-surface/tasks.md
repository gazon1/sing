# Tasks — agent-toolbox-surface

**status:** accepted

---

## Slice 1 — a trustworthy surface

- [x] `proposal.md`, `design.md`, `specs/agent-toolbox-surface/spec.md` — created
- [x] The catalogue no longer carries a description — the tool class owns it
- [x] The catalogue's docstring says what is true about the DI lists
- [x] `McpToolCatalog.ToolEntry` — the `description` field removed
- [x] The renderer reads the descriptions from the tool classes
- [x] The renderer reads the ADR domain by *domain*, not by a slice of the file
- [x] The drift gate checks catalogue ↔ tool classes ↔ CLI mapping
- [x] The gate renamed to *the toolbox surface is declared once*
- [x] Positive controls, one per mismatch type
- [x] The runtime wiring test — JVM (`desktopApp`) and Android (`shared`)
- [x] The kill switch applied before registration, from one startup snapshot
- [x] `describe_toolbox` — the five identity values, bounded and without secrets
- [x] The MCP server reports the real version (`-Dsingularity.version`)
- [x] `AGENTS.md` names the command that produces the digest
- [x] The MCP resources — the toolbox, the decision index, one decision
- [x] **F16** — the kill-switch rule fixed at its owner: the tool ID resolves for both
      shapes of tool class, and `AiToolFiltersTest` pins it

## Slice 2 — searchable knowledge

- [x] `AdrSearch.kt` — the engine, the scopes, the envelope
- [x] `AdrStorage.search` — the engine, exposed rather than wrapped
- [x] `search_decisions` — the structured question
- [x] `search_docs` — the scoped question
- [x] Both tools registered on both platforms and in the catalogue
- [x] `singularity-adr search` — a thin adapter over the same storage
- [x] The generated ADR sections re-rendered
- [x] The rule that says a backlog entry is not a decision is applied where the corpus
      is read — `list_adrs` no longer answers with the backlog in it, and
      `list_open_deferred` reads the backlog from its own directory

## Slice 3 — observable usage

- [x] `ToolCallEvent`, `ToolCallLog`, `ToolCallRecorder` — in `commonMain`
- [x] `HostEnvironmentPort.applicationDataDirectory()`
- [x] The JVM writer — append-only, owner-only, per process per day
- [x] The Android writer — app-private storage
- [x] The MCP dispatch boundary measures the call
- [x] The in-app agent measures through the decorator
- [x] The registry snapshot written at startup
- [x] `scripts/mcp-toolbox-usage.py` — the local report
- [x] The report shows the registered-but-not-observed tools even when there are no
      calls at all — that is the case the question is asked in

## Ceremony

- [x] The three ADRs
- [x] The ADR for the kill switch that was never applied
- [x] The ADR for the gate that reads the surface the compiler saw
- [x] The ADR for the server that takes its dependencies as arguments
- [x] The ADR for the surface that is read from the compiler down
- [x] This change
- [x] The issue registry — `scripts/openspec-import-all.py`
- [x] The digest refreshed
- [x] `openspec/specs/MODULE-INDEX.md` — the module row points at this change
- [x] `singularity-todo-cli-tool-surface` — the examples that cannot compile, the
      annotation keys, and the eight tools it listed that do not exist
- [x] `singularity-todo-mcp-dogfooding` — how an external agent uses the new surface

## The follow-up wave

Seven gaps, closed after the change itself was merged:

- [x] The contract the agent reads — the `describe_toolbox` payload, the three MCP
      resources and the registry snapshot — pinned by tests
- [x] The local report reads the shape the server writes, and names the flags that
      matched no tool
- [x] The skill that teaches an agent mentions `unmatchedFlags`
- [x] The local gate compiles the Android source set
- [x] The corpus has one declared policy, and the two readers are checked against
      each other
- [x] The flag key space is declared, and the documentation is checked against it
- [x] The catalogue holds the tool *class*, so the export and the documentation read
      the same declaration — the tool **list** stays hand-written, because the Koin
      compiler plugin checks definition requests by static type and a heterogeneous
      list has none (ADR `2026-10-21-the-toolbox-surface-from-the-compiler-down`)

## The third wave

- [x] The tool list is written once: the two platform modules' identical blocks moved
      to `commonMain` as `Module.aiToolBindings()`
- [x] The local report knows the record's schema version and refuses to count what it
      cannot read
- [x] The resource URIs are pinned by a test, and the payload's compatibility rule is
      written down where the payload is built
- [x] `ToolAnnotations` sits with the catalogue it belongs to
- [x] The flag key space is published in the architecture document as a generated,
      gate-checked block

ADR: `2026-10-21-the-toolbox-once-more`.

## The fourth wave

- [x] The cost of the loop is measured: `sgate run-all` times every gate, `check.sh`
      times every Gradle step, and nothing is run twice to be timed
- [x] `config/docs/gate-benchmarks.json` — the recorded baseline, with the machine it
      was measured on
- [x] `docs/testing/gate-costs.md` — the published cost, as a generated block
- [x] The gate *the published gate costs are current* — it compares two committed files,
      which is a question a CI runner can answer honestly
- [x] The local report names what got slower and refuses to compare across machines
- [x] `sgate check-benchmarks` — removed; it could not pass, and a command that cannot
      pass teaches its users to ignore it
- [x] `describe_toolbox` carries `toolset.surfaceContractVersion`, owned by the catalogue
- [x] The registry snapshot carries `schemaVersion`; the report reads the shape its
      version declares and counts what it cannot read
- [x] `ToolboxRuntimeContractTest` — the production writers and the production report,
      compared in one test
- [x] The report names the records the time window excluded, and reports the error rate
      per tool as a share of the calls
- [x] `config/docs/document-corpus.json` — the document corpora have a policy: the roots
      of every scope, the depth bound and what a document is
- [x] `DocumentCorpusConfig` (Kotlin) and `scripts/document_corpus.py` (Python) — two
      readers of that policy, compared by `DocumentCorpusReadersAgreeTest`
- [x] The docs suite names the commands that exist — four of the seven it named had
      never existed, and one pointed at a script deleted in July
- [x] The chain test — describe the toolbox, call a tool, read what the server wrote,
      read it with the report

ADR: `2026-10-21-a-payload-says-which-version-it-is`,
`2026-10-21-the-document-corpora-have-a-policy`,
`2026-10-21-the-cost-of-the-loop-is-measured`.

## The fifth wave

- [x] The record's vocabulary is declared once — `config/docs/tool-call-records.json`:
      the schema version, the outcomes, the sources, the retention
- [x] The writer is checked against the policy (`ToolCallRecordPolicyTest`), and the
      report reads the same file
- [x] An outcome the report does not know is counted as **unknown** and named, never as
      an error — the rule "anything that is not ok is an error" was a rule about
      everything, and it was wrong about everything it did not recognise
- [x] The report's default window is the retention, and the report names the span it
      looked at
- [x] `SearchEnvelope` carries `schemaVersion` — the search's answer says which
      version of the answer it is
- [x] The surface contract version is in the `initialize` response, so a client learns
      it before the first call
- [x] The CI loop is priced: the published page carries the last CI run's jobs
- [x] `script-conventions` was declared twice in the sabotage controls, and
      `GATE_CONTROLS` declared the same key twice — both gone, and a test says a
      control may not be written twice
- [x] The JSON-contract gate probes its gates concurrently (58.9 s → 33.6 s on the
      same machine), and it is invoked three times per loop
- [x] `just tests::affected` — the checks a change makes worth running, computed from
      the module graph the build files declare

ADR: `2026-10-21-the-answers-declare-what-they-are`,
`2026-10-21-the-loop-is-priced-on-both-sides`.

## Verification

- [x] `:shared:jvmTest` — the full suite; the nine failures it reported were all from
      one row of the seam registry (a column-count typo in a row this change added),
      fixed and re-verified by the targeted run
- [x] `:shared:jvmTest` — the targeted classes: the corpus search, the ADR storage, the
      tool-call records, the platform seam guard
- [x] `:adr-cli:test`
- [x] `:desktopApp:test --tests "*ToolSurfaceWiringTest*"`
- [x] `scripts/check-skill-drift.py` and its positive controls
- [x] `scripts/mcp-toolbox-usage.py` — its own tests
- [x] `bash scripts/ci/static-gates.sh` — 30 of 30 gates pass. The one that did not
      when the change was written (`gate registries agree`) was fixed upstream while it
      was in flight
- [x] `:mcp-server:test` and the detekt runs — every module, not only `shared` and
      `desktopApp` (see the note below: two modules were never linted locally, and CI
      found nine findings in them)

### Known pre-existing failures, not caused by this change

- **`test_check_gate_wiring` / `test_gate_wiring_advisory` cannot be imported** under
  Python 3.14: `dataclasses` looks the module up in `sys.modules` while `importlib`
  loads it without registering it. Neither file is touched by this change.
