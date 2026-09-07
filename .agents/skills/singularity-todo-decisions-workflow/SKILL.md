---
name: singularity-todo-decisions-workflow
description: Lightweight decision-log workflow for this project. Instead of a skill per refactor, write short structured entries to `docs/decisions/<date>-<slug>.md` capturing Idea / Decision / Rationale / Consequences, and keep an auto-generated `docs/decisions/DIGEST.md` as the single source of truth for the agent. Use when finishing a non-trivial refactor, choosing between two viable approaches, discovering a non-obvious workaround, or migrating a contract. Skip for trivial fixes (typos, single-line tweaks).
---

# Singularity TODO — Decision Log Workflow

This project captures architectural knowledge as **short dated entries** rather than as a growing pile of narrow "how-to" skills. The agent reads the consolidated `DIGEST.md` at session start; the human reads individual entries when they want the reasoning.

#### File layout

```
docs/decisions/
├── DIGEST.md                       # auto-generated, single source of truth for the agent
├── 2026-09-05-koog-both-platforms.md
├── 2026-09-05-koin-suspend-bridge.md
├── 2026-09-05-ai-provider-settings.md
└── ...
```

File name: `YYYY-MM-DD-<short-slug>.md`. One slug per decision. Multiple decisions from the same day → multiple files, all with the same date prefix.

#### Entry format

```. Runkotlin
---
title: "Short, declarative title — what we chose"
date: 2026-09-05
tags: [koin, refactor]
supersedes: 2026-08-12-koin-runblocking  # optional: which older entry this replaces
---

## Context

One paragraph: what was the situation? What was broken or suboptimal? What
constraints did we have? Include the trigger (a failing test, a code-review
comment, a user request).

## Idea

What could we have done? List 1–3 alternatives briefly. The point isn't to
exhaustively survey — it's to record the realistic options at the moment
of the decision.

## Decision

What we actually did. One paragraph, declarative, present tense.

## Rationale

Why this over the others. Call out the trade-offs accepted, the constraints
that forced our hand, and the future flexibility we kept.

## Consequences

What we now know / do / avoid. Bulleted list. These bullets become the
candidates for the next DIGEST.md refresh.

## Links

- Commit(s), PR(s), issue(s)
- New / changed files
- Tests added
- Related decisions (by file name)
```

Anything not in **Consequences** is "what we did"; **Consequences** is "what we promise to honour from now on". Keep them concrete: rule X, forbid Y, always do Z. Vague consequences like "improved maintainability" don't make it into the digest.

#### When to write a decision

Write when **at least one** of the following is true:

- The choice wasn't obvious — at least one other approach was reasonable.
- The non-obvious choice has rules attached ("always do X", "never Y") that future agents must follow.
- A non-obvious workaround was needed (e.g. JVM-test classpath NPE workaround).
- A contract between modules / subsystems changed (new port, new expectation, removed layer).
- A user explicitly asked for the reasoning to be recorded.

Skip for:

- Pure typo / formatting fixes.
- Renames that don't change behaviour or contracts.
- Adding a new use case / tool that follows an existing pattern (the existing skill covers it).

If unsure, write a one-paragraph entry — small is fine. Missing a decision is worse than over-documenting a small one.

#### Refresh workflow

1. **Add entries** to `docs/decisions/` as you make decisions (this skill). Commit each entry or group as you go.
2. **Refresh the digest** before any non-trivial agent task, or after a batch of new entries:
   ```bash
   ./scripts/refresh-decisions-digest.sh
   ```
3. **Commit the refreshed `DIGEST.md`** in the same commit as the entries it consolidates, when reasonable.

The script is idempotent — running it on an already-fresh tree is a no-op that exits 0.

#### Tools (optional, documented — not installed)

- **`mdq`** — Markdown Query. CLI tool for grepping the decision corpus. Available at [github.com/megabreezy/mdq](https://github.com/megabreezy/mdq) (`brew install mdq` / `pip install mdq`). Not installed in this workspace; documented only.
  ```bash
  # Query the digest
  mdq "koin" docs/decisions/DIGEST.md
  # Search across all entries
  mdq "OpenAIModels" docs/decisions/*.md
  ```
  If you prefer not to use it, `grep` and `awk` work fine against the raw files.
  If you run `mdq` ad-hoc, the digest is pre-filtered for critical rules — start there.

## ADR Tools — Automated Writing and Reading via AI

The AI agent can create, list, and read ADR files directly through MCP tools. This automates the workflow: instead of manually creating `.md` files, the agent calls `write_adr`, `list_adrs`, and `read_adr` tools.

### Template

ADR files are created from this template (same as the human workflow):

```kotlin
// shared/src/commonMain/.../core/adr/AdrTemplate.kt
object AdrTemplate {
    private const val FRONTMATTER = """
        |---
        |title: "%s"
        |date: %s
        |tags: [%s]
        |---
        |
        |## Context
        |
        |%s
        |
        |## Decision
        |
        |%s
        |
        |## Rationale
        |
        |%s
        |
        |## Consequences
        |
        |%s
    """.trimMargin()

    fun render(
        title: String,
        date: String,          // "YYYY-MM-DD"
        tags: List<String>,
        context: String,
        decision: String,
        rationale: String,
        consequences: String,
    ): String = FRONTMATTER.format(
        title,
        date,
        tags.joinToString(", "),
        context,
        decision,
        rationale,
        consequences,
    )
}
```

### WriteAdrTool

```kotlin
// shared/src/commonMain/.../feature/ai/tools/WriteAdrTool.kt
class WriteAdrTool(
    private val notesRepo: NotesRepository,
    private val markdownHtmlPort: MarkdownHtmlPort,
    private val currentUser: CurrentUser,
) : SimpleTool<WriteAdrInput>(...) {

    override suspend fun execute(args: WriteAdrInput): String {
        val content = AdrTemplate.render(
            title = args.title,
            date = LocalDate.now().toString(),
            tags = listOf("adr"),
            context = args.context,
            decision = args.decision,
            rationale = args.rationale,
            consequences = args.consequences ?: "—",
        )

        // Write to docs/decisions/<date>-<slug>.md
        val fileName = "${LocalDate.now()}-${args.slug}.md"
        val filePath = docsDir.resolve(fileName)
        filePath.writeText(content)

        // Optionally create a linked note
        val noteId = if (args.createNote) {
            val html = markdownHtmlPort.toHtml(content)
            notesRepo.createWithContent(
                userId = currentUser.userId,
                id = NoteId.fromString(UUID.randomUUID().toString()),
                title = "ADR: ${args.title}",
                bodyMarkdown = content,
                bodyHtml = html,
            ).getOrNull()?.value
        } else null

        return WriteAdrOutput(filePath = filePath.absolutePath, noteId = noteId).toJson()
    }

    companion object {
        const val NAME = "adr.write"
        const val DESCRIPTION = "Write a new ADR file to docs/decisions/. Optionally creates a linked note."
    }
}
```

### ListAdrsTool and ReadAdrTool

```kotlin
// shared/src/commonMain/.../feature/ai/tools/ListAdrsTool.kt
class ListAdrsTool(private val docsDir: Path) : SimpleTool<ListAdrsInput>(...) {
    override suspend fun execute(args: ListAdrsInput): String {
        val files = docsDir.listDirectoryEntries("*.md")
            .sortedDescending()
            .map { it.nameWithoutExtension }
        return ListAdrsOutput(slugs = files).toJson()
    }
    companion object { const val NAME = "adr.list" }
}

// shared/src/commonMain/.../feature/ai/tools/ReadAdrTool.kt
class ReadAdrTool(private val docsDir: Path) : SimpleTool<ReadAdrInput>(...) {
    override suspend fun execute(args: ReadAdrInput): String {
        val file = docsDir.resolve("${args.slug}.md")
        if (!file.exists()) {
            return McpToolError.NotFound("ADR", args.slug).format(isError = true)
        }
        return ReadAdrOutput(content = file.readText(), slug = args.slug).toJson()
    }
    companion object { const val NAME = "adr.read" }
}
```

### MCP Tool Annotations

| Tool | Annotation |
|---|---|
| `adr.write` | `openWorldHint = true` (creates external file) |
| `adr.list` | `readOnlyHint = true` |
| `adr.read` | `readOnlyHint = true` |

### Workflow Example

An AI agent can now:
1. Call `adr.list` → see all existing ADRs
2. Call `adr.read("2026-09-05-koog-both-platforms")` → read the full content
3. Call `adr.write(context="...", decision="...", ...)` → create a new ADR file

This closes the loop: the agent decides architecturally AND records the decision automatically, in the same format humans use.

See `singularity-todo-cli-tool-surface` for the tool contract (error mapping, authorization).

#### Why this beats per-skill docs

Per-skill docs accumulate. After six months you have 20 narrow skills, each 100 lines, each describing a different facet of the same system. The agent picks one based on the description, misses the others, and gets a partial picture. The digest is **one file**, fetched at session start, that consolidates rules. Per-decision entries are human-facing, the digest is agent-facing.

When a decision is superseded, mark it `supersedes: <older-slug>` in the new entry's frontmatter and remove its consequences from the digest on the next refresh. Old entries stay on disk for history but are no longer surfaced.

#### Anti-patterns

- **Skill per refactor.** Each one reads in isolation, and the agent has to know which to load. We tried that — produced 7 overlapping files in one session. Replaced.
- **Living only in `DIGEST.md`.** The digest is a summary. Without the dated entries there's no "why" — only "what". Future-you will wonder "did we consider X?" and find no record.
- **Bloated entries.** If your Context section is more than a paragraph, the decision is too big; split it.
- **Vague consequences.** "Improved code quality" never makes it into the digest. "Always use `koinBridge { ... }`, never raw `runBlocking` in DI" does.
- **Renaming the digest.** The name is fixed (`DIGEST.md`) so the script and any hooks can find it.

#### What replaced this

This skill replaced 7 narrow skills created in a single session:

- `singularity-todo-koin-suspend-bridge`
- `singularity-todo-ai-provider-settings`
- `singularity-todo-secret-migration`
- `singularity-todo-koog-test-workarounds`
- `singularity-todo-koog-both-platforms`
- the updated `singularity-todo-koog-agent`
- the updated `singularity-todo-koin-di`

Each of those was a one-shot skill for one refactor. The information is preserved — as dated entries in `docs/decisions/`. The digest is the new entry point.

#### Related

- `AGENTS.md` — the workflow says "refresh the digest before starting work". The session start hook (or a manual `./scripts/refresh-decisions-digest.sh`) ensures the agent sees the latest rules.
- `docs/decisions/DIGEST.md` — the consolidated rules the agent reads.
- Individual dated entries — the human-facing reasoning.