---
title: 'The skill-symbol gate had a blind spot, and it failed silently'
date: 2026-10-04
status: accepted
deciders: engineering
---

# The skill-symbol gate had a blind spot, and it failed silently

## Context

`scripts/check-doc-dead-refs.py --skill-symbols` is the blocking gate that
checks every backticked identifier in a `SKILL.md` against a symbol index built
from production Kotlin. It is one of the gates added during the restore-verifiability
work and it had never run against a change that touched a skill.

While splitting `DesktopNavigation.kt` (see
`2026-10-04-desktop-test-failure-diagnostics.md` §7) I edited
`singularity-todo-desktop-compose-ui-tests/SKILL.md` and ran the gate, which
failed with six dangling symbols — none of which I had written. All six were in a
*different* skill I had not touched.

The first mistake would have been to baseline them. The second, likelier mistake
would have been to attribute the failure to my own edit. Both were wrong.

## What the six actually were

| Symbol | Real home | Visible to the index? |
|---|---|---|
| `ConstructorParameterNaming` | `config/detekt/detekt.yml:180` | no |
| `BackingPropertyNaming` | `config/detekt/detekt.yml:320` | no |
| `ImportOrdering` | `config/detekt/detekt.yml:291` | no |
| `FunctionSignature` | `config/detekt/detekt.yml` (ktlint wrapper) | no |
| `Indentation` | `config/detekt/detekt.yml:353` | no |
| `KDocEnforcementRulesTest` | `detekt-rules/src/test/kotlin/…` | no |

Two independent gaps, and neither is a dangling reference:

1. **The index scanned only six production roots.** `detekt-rules/src` was not
   among them, so a skill that documents a custom rule or its test by name could
   never pass. `singularity-todo-detekt-rules-authoring` documents exactly that.
2. **Detekt rule ids are not Kotlin declarations at all.** They are keys in a
   YAML config. A `.kt` scan is structurally incapable of seeing them, so every
   rule name a skill quotes was reported missing — including the ones the config
   *deliberately* declares `active: false`, which are the ones most worth
   documenting.

So the gate had been reporting a fixed false-positive set since it was written,
and nothing had caught it because nothing ran it against a skill that mentions
these names.

## Decision

Two additions to `_build_kt_symbol_index()`:

- add `detekt-rules/src` to the scanned roots;
- after the Kotlin scan, add every rule key from `config/detekt/detekt.yml` as an
  index entry pointing at the config.

Matching rule keys needs care, and both wrong turns are worth recording:

- A first pattern keyed on the *setting name* (`active:`/`excludes:`/
  `ignoreX:`) missed `ConstructorParameterNaming` and `Indentation`, which are
  declared `active: false`. A rule switched off is still a rule that
  documentation may legitimately quote — the pattern has to accept the value,
  not the setting.
- The corrected pattern used `\s` for indentation. Under `re.M`, `\s` matches
  `\n`, so a match can consume the newline the **next** line's `^` anchor needs,
  and the following key is skipped with no error. That is how `ImportOrdering`
  vanished — it is the first key under `ktlint:`, right after a comment line.
  Indentation is now `[ \t]`.

The lesson generalises past this regex: **a pattern that silently matches less
than intended is indistinguishable from a pattern that has nothing to match.**
Both produce a green run. The only way to tell them apart is to assert on a
specific known-good name, which is why the six were looked up individually rather
than baselined as a block.

## Rationale

Baselining would have converted a bug in the instrument into accepted debt in the
ledger — the same failure mode as the original 428-entry baseline, and it would
have been invisible forever because the baseline is *defined* as the set of
things the gate is allowed to complain about. A gate that can only be silenced
is a gate that has stopped measuring.

## Consequences

- The gate now passes on `singularity-todo-detekt-rules-authoring` and on the
  desktop-test skill. No baselining was needed, and
  `config/docs/skill-symbol-baseline.txt` did not grow.
- Verified in both directions: after the fix the gate is green, and a planted
  `TotallyMadeUpHelperSymbol` in the desktop-test skill still fails it. A fix
  that cannot fail is indistinguishable from a disabled gate.
- A skill quoting a *genuinely* removed symbol will now fail, and that is the
  intended behaviour.

## Links

- `2026-10-04-desktop-test-failure-diagnostics.md` — the split that exposed this
- `2026-10-04-rule-verifiability-inventory.md` — the other half of the same
  lesson: a rule nobody declared is a rule nobody chose
- `deferred-backlog-archive.md#epic-b-readability-now-unblocked-gates-work` — B5
