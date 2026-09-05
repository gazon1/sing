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