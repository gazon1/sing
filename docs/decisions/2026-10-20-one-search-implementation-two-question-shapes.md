---
title: One search implementation, two question shapes
date: 2026-10-20
status: accepted
tags: [mcp, adr, tools, docs]
---

# One search implementation, two question shapes

## Context

The knowledge of this project is written down in Markdown: 692 decisions, 149 skill files,
13 capability specifications, the architecture documents. None of it was reachable through
the API an agent already used. `list_adrs` returned the *titles* of the decisions, and
reading one needed its exact slug — so an agent asking "what did we decide about the sync
conflict?" had to leave the tool surface and grep the repository. The skill told it to do
that, which is a sign of a missing tool rather than of a working workflow.

The corpus already has rules, and they are owned: `config/docs/adr-corpus.json` says which
files are decisions, which are backlog, which are ignored, and what a status may be. Four
Python consumers and one Kotlin consumer read them. The rules are not the problem.

## Idea

- **A.** One generic search tool with a scope argument and a result type per scope.
- **B.** Two tools — one for decisions, one for documents — sharing the parts that are
  genuinely shared.
- **C.** A search engine of its own, with an index.

## Decision

**B**, and the shared parts are shared *mechanically*, not by convention.

- `CorpusSearch.searchDecisions` answers the structured question: "what did we decide,
  and is it still open?" Every hit carries the status, the tags, the date and a snippet.
- `CorpusSearch.searchDocuments` answers the scattered one: "where is the rule about X?"
  Hits carry a path and a snippet. The decisions are included in its `all` scope, so
  "search everything" is one call.
- The engine is **exposed, not wrapped**. It was first reachable as
  `AdrStorage.searchAdrs` / `AdrStorage.searchDocs` — two pass-throughs that said the
  same thing twice, on a class that had no reason to own a search. `AdrStorage` now
  publishes the engine it built (`storage.search`), and the storage has no reason to own
  a search. The engine's constructor is internal: only the storage knows where the
  decisions live and which rules apply, and a caller that could build its own would be
  able to search a different corpus than the project describes.
- One engine, `CorpusSearch`, does the traversal, the case normalisation, the matching,
  the ordering and the snippet. The two tools differ in *what they report*, not in how
  they look.
- The corpus rules are **reused, not re-implemented**: the decisions come from
  `AdrCorpusConfig.discover` and `deferred`, so the archive stays unreachable, the
  ignored files stay ignored, and the backlog stays marked as backlog.

  Two of those rules were written down and not applied, and the search inherited both:
  `isDecision` was never consulted where the corpus was read, so `list_adrs` answered
  with the backlog in it; and `isArchived` matched a path *inside* `archive/` but not the
  directory itself, so the walk descended into the archive. The Python reader had no such
  problem — it globs the top level — which is how two readers of one corpus ended up
  answering different questions.
- The rule that says *a backlog entry is not a decision* — `AdrCorpusConfig.isDecision` —
  is now applied where the corpus is read. It was declared and never used: `discover`
  walks the whole corpus, including `deferred/`, and `list_adrs` answered with the
  backlog in it, so the two tools that exist to tell them apart overlapped. The search
  inherited the inconsistency, and fixing it in the search alone would have left two
  answers to the same question.
- `singularity-adr search` is a thin adapter over the same storage, and its `--json`
  output is the *same envelope*, so a script and an agent parse one shape.

**The answer distinguishes three things**, because an empty list alone cannot:

- `available = false` — the corpus was not searched at all;
- `available = true` with no hits — the corpus was searched and nothing matched;
- `error` — the call was wrong (a blank query).

## Rationale

Two tools, because the questions are genuinely different: a decision is an object with a
status and a superseded-by edge, and a document is a path. One tool with a scope argument
and a union result would make every consumer handle both shapes.

One engine, because the alternative is a second implementation of the same rules, and the
project has already paid for that lesson — the corpus was discovered five different ways
before `check-adr-config-sync.py` made the rules a single file.

No index, because substring matching over ~850 files is fast enough, and an index would be
a second thing to keep in step with the files it indexes. Measured first; a cache is a
later decision, not a preemptive one.

## Consequences

- An agent can find a decision, and read it, without a shell.
- The CLI and the MCP tool answer the same question the same way.
- A file that the corpus rules exclude is excluded from the search as well, by
  construction.
- The search reads the files it searches on every call. At the current size that is
  acceptable; it would not be at ten times the size.

## Links

- `adr-core/.../AdrSearch.kt` — the engine, the scopes and the envelope
- `shared/.../feature/ai/tools/AdrTools.kt` — `search_decisions`
- `shared/.../feature/ai/tools/SearchDocsTool.kt` — `search_docs`
- `adr-cli/.../AdrCli.kt` — the `search` command, over the same storage
