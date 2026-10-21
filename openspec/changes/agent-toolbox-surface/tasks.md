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
