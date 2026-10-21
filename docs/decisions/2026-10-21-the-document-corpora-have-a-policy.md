---
title: The document corpora have a policy
date: 2026-10-21
status: accepted
tags: [corpus, search, tools, policy]
---

# The document corpora have a policy

## Context

The ADR corpus learned to have one owner on 2026-10-10, and it learned it the hard way:
five consumers discovered the same corpus five different ways, and three of them could
not see the backlog. The fix was a policy file — `config/docs/adr-corpus.json` — read by
every consumer, with a test that compares the Python reader with the Kotlin one.
The document corpora never got that treatment. `search_docs` answers "where is the rule
about X?" across the architecture documents, the procedures and the specifications, and
the directories it looked in were string literals in the `SearchScope` enum:

```kotlin
ARCHITECTURE("architecture", "docs/architecture"),
SKILLS("skills", ".agents/skills"),
SPECS("specs", "openspec/specs"),
```

So the Kotlin search engine knew where the documents were and nothing else did. The
Python side could not answer "which files are documents?" at all, and every gate that
read those trees carried its own copy of the path. The depth bound — how far down the
walk goes — was a literal in the same file, `MAX_DEPTH = 6`, with nothing to say why it
was six.

## Decision

**The document corpora get a policy, and the two readers are compared by a test.**

`config/docs/document-corpus.json` declares the roots of every scope, the depth bound
and what a document is. The Kotlin reader is `DocumentCorpusConfig`; the Python reader
is `scripts/document_corpus.py`; `DocumentCorpusReadersAgreeTest` asks both the same
question and compares the answers.

The decisions are *not* in that file. They are records: they have a frontmatter
contract, statuses and a staleness rule, and those live in `config/docs/adr-corpus.json`.
One file with two kinds of rule is a file whose reader has to know which kind it is
reading, and the two kinds have different owners already.

A checkout without the policy file falls back to the same roots the literals had. A
missing policy must change what is *declared*, never what is *found* — otherwise the
fallback would quietly answer a different question than the one the policy asks.

## Rationale

The ADR corpus's lesson was not "Python needs the rules too". It was that a corpus
whose boundaries live in more than one place is a corpus whose boundaries are a guess.
Here the boundaries lived in a Kotlin enum, so the Python side — which is where the
gates, the digest and the reports live — could not state them.

The policy is JSON because both sides can read it with what they already have: Python
with the standard library, Kotlin with kotlinx.serialization. A policy one side cannot
read is a policy that side ignores, and the agreement test then compares two answers
that were never about the same thing.

The test is what makes the policy an owner rather than a comment. Two readers of one
policy can still drift apart — a different depth rule, a different extension filter, a
root added to one side only — and the drift shows up as a set difference in a test
rather than as a search that quietly misses half the corpus.

## Consequences

- The roots of the document corpora are declared once. `SearchScope` keeps the *names*
  an agent may ask for, and the directories come from the policy.
- The depth bound and the extension filter are in the policy, next to the roots they
  bound.
- The Python side can answer "which files are documents?", and the answer is checked
  against the Kotlin one.
- A scope that the policy does not declare is reported as undeclared, not searched
  silently into nothing.
- The ADR corpus keeps its own policy. Two files, one per kind of corpus, each with one
  owner.

## Links

- `config/docs/document-corpus.json` — the policy
- `adr-core/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/DocumentCorpus.kt` —
  the Kotlin reader
- `scripts/document_corpus.py` — the Python reader
- `adr-core/src/jvmTest/kotlin/com/singularity/todo/feature/ai/tools/DocumentCorpusReadersAgreeTest.kt` —
  the two readers, compared
- `scripts/adr_corpus.py` and `config/docs/adr-corpus.json` — the same arrangement, for
  the decisions
