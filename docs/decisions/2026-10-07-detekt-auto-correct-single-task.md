---
title: "detekt --auto-correct silently does nothing with two or more detekt tasks"
date: 2026-10-07
status: accepted
tags: [tooling, detekt, gates, gradle]
---

## Context

`just detekt-fix` exists to clean up detekt and ktlint violations in place. It ran:

```
./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt --auto-correct
```

It reported the violations and fixed some of them, which is the worst possible combination:
the output looks like it worked, and it did not.

I first misread this. A run left 24 `StatementWrapping` / `FinalNewline` /
`NoConsecutiveBlankLines` findings still in place, I concluded the recipe "does not fix
these rules", and fixed them by hand. That conclusion was wrong, and the mistake was
worth recording because the way to tell the truth is the thing this repository keeps
insisting on: plant a violation and observe the tool, do not reason about it.

## Idea

Measure the boundary instead of inferring it. Plant a file with two violations a ktlint rule
owns — a `{ return x }` block and a missing final newline — then read the last byte of the
file after the run.

```
before: last byte 0x7d (`}`)
```

| Invocation | Result |
|---|---|
| `./gw :shared:detekt --auto-correct` | file rewritten, last byte `0x0a` |
| `./gw :shared:detekt :desktopApp:detekt --auto-correct` | file untouched, last byte `0x7d`, "0 files modified" |
| `just detekt-fix` (four tasks) | file untouched, last byte `0x7d` |

One detekt task corrects. Two or more correct nothing, with no warning, and still exit 1
for the findings they did not correct — which is exactly what makes it look like the rules
are simply not auto-correctable.

## Decision

Run **one detekt task per Gradle invocation** in `detekt-fix`, and split the recipe into a
fix pass and a verification pass.

```bash
for module in :shared :desktopApp :detekt-rules :androidApp; do
    ./gw "$module:detekt" --auto-correct --console=plain >/dev/null 2>&1 || true
done
./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt --console=plain
```

The fix pass discards its exit code. detekt reports the findings it just corrected, so a
fully successful auto-correct still exits 1 — the original recipe's exit status was never
evidence about whether it fixed anything. The final pass runs without `--auto-correct`, and
its exit code is the one that means something.

Verified by re-planting the violation and running the new recipe: the file came back with
`0x0a` and the block converted to an expression body.

## Rationale

The recipe's name and its doc both promise in-place fixes. A recipe that silently does
nothing is worse than no recipe, because the failure is indistinguishable from success at
the only moment anyone looks — the green run afterwards.

The cost is real and worth naming: five sequential Gradle invocations instead of one, so
`detekt-fix` got noticeably slower. That is the price of it actually working, and it is
cheaper than the alternative this repository already paid once — concluding the rules were
unfixable and editing formatting by hand while the real bug stayed in the recipe.

## Consequences

- `just detekt-fix` now does what it says. Verified by planted violation, not by reading the
  script.
- It is slower. Roughly five Gradle invocations, run sequentially, each with its own
  configuration cache hit.
- The `|| true` in the fix loop is deliberate and is *not* a silenced gate: the
  verification pass on the next line runs unconditionally and its exit code decides. If
  both loops were merged into one guarded command, the recipe would return green over
  whatever it failed to fix.
- Rules that genuinely have no auto-correct — `FunctionOnlyReturningConstant`,
  `TooManyFunctions` — still surface, and still need a human decision. That is the
  behaviour to keep; the fix is that the *correctable* ones now stop masquerading as them.

## Links

- `.just/tests/mod.just` — `detekt-fix`
- `scripts/check-gate-honesty.py` — the project's existing "plant a violation, assert it is
  reported" gate, and the reason this diagnosis was done by measurement