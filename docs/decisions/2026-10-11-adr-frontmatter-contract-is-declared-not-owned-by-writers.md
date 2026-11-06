---
title: "The frontmatter contract is declared in one file, not in the three writers that own it"
date: 2026-10-11
status: accepted
tags: [adr, tooling, contracts, python, kotlin]
issue: 518
---

## Context

Three pieces of code wrote ADR frontmatter, each with its own idea of the format.

| Writer | What it believed |
|---|---|
| `AdrStorage.writeAdr()` | `title, date, status: open, tags` — always `open`, no validation |

None of this was theory. `updateAdr` began emitting `review-by:` in #527, the
normalizer's allowlist was never told, and the repository's own tool began producing
files the repository's own gate rejected as `unknown frontmatter key` — eleven more
files were already sitting in the corpus with `issue:`, `gh:` and `backlog:` keys the
allowlist had never heard of.

Two dead flags were living in the same neighbourhood. `--dry-run` was passed by
`.just/tests/mod.just` and read by no script; `--quiet` was passed by both git hooks
to a script that never looked at `sys.argv` at all.

## Idea

Put the contract in a file both languages can read, the way `config/docs/adr-corpus.json`
already serves the corpus discovery rules (#520).

## Decision

**The frontmatter contract lives in `config/docs/adr-corpus.json` under `frontmatter`:**
the key allowlist, the key order, and the status a new decision is born with.

- `AdrCorpusConfig` reads it with kotlinx-serialization and **validates before writing**:
  both `writeAdr` and `updateAdr` refuse a key outside the allowlist instead of writing a
  file the normalizer will later call structurally broken.
- **A new ADR is born `open`** — declared, not hardcoded, and recorded in
  `doc-maintenance.md`.
- Unknown flags are a **usage error (exit 2)** in both scripts. A dead flag is a small
  lie: it reads as if something were being suppressed, so the next person to add a real
  option assumes a parser exists and writes around it.

## Rationale

Parsing Kotlin from Python is a real cost, and it is paid deliberately. `static-gates.sh`
is specified to finish in about a minute without a JVM; shelling out to a built CLI for
the frontmatter contract would put a Gradle build in the path of every gate run. The
alternative — restating the rules — is the thing that produced this defect, so paying a
parsing cost is cheaper than paying a drift cost.

That trade is only safe if the parser never guesses. `normalize-adr-frontmatter.py` still
treats an unknown key as a hard error, and `render-adr-tools-section.py` refuses a
declaration it cannot read instead of skipping it, because a skipped entry drops a command
from a skill while the section still looks complete.

The allowlist also has to be measured against the corpus rather than remembered. The four
keys added here were found by enumerating what 566 files actually carry, not by reading
the old list and adding whatever looked plausible.

## Consequences

- Adding a frontmatter key means editing one JSON file. Both consumers follow.
- `writeAdr` and `updateAdr` can now *fail* on a key that is not declared. That is the
  intended direction: the failure happens at the writer, where the intent is known,
  instead of at the gate, weeks later, against a corpus nobody remembers touching.
- Fourteen allowlist keys are currently unused. They are kept: `created` is migrated by
  rule, `Context`/`Decision`/`Rationale`/`Consequences`/`Links` are read by the section-key
  check, and `review-by` is legal-but-unused by design (#527).

## Links

- Issue #518, part of #515.
- `2026-10-10-adr-file-local-rules-live-in-the-writer` — the same rule one level up: a
  rule that can be bypassed by editing a file belongs in the writer.
- `config/docs/adr-corpus.json` — the one declaration.
- `scripts/tests/test_normalize_adr_frontmatter.py` — the two-sided controls, including
  the `review-by` regression and the argv contract.
