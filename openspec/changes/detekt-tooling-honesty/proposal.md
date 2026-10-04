# detekt-tooling-honesty

Issues: #136, #137, #138 · Follows: `local-gate-repair`, `detekt-rule-has-positive-control`

## What

Three findings, all from one session, all of the same kind: **a build tool reported success while
doing less than it was asked to do**, and the only reason any of them was noticed is that a human
was already suspicious.

1. **A stale `:detekt-rules` classpath makes `:shared:detekt` green with no custom rules
   running** (#136). Observed three times. Once as a hard failure naming a provider that a
   standalone `ServiceLoader` probe over the very same jar could load; twice as a *false pass*.
   `./gw --stop` is the remedy every time; `--no-configuration-cache` is not.

2. **`:shared:detektBaseline` runs without the custom rule set at all** (#137). Regenerating the
   baseline dropped it from 357 entries to 338 and left zero custom-rule entries — after which
   `:shared:detekt` reported a clean tree, because nothing custom was running. The lost entries
   are recoverable; the part that matters is that a baseline regeneration is a way to turn the
   custom rules off and have the gate agree with you.

3. **A Koin definition body stays at 0% covered even when the definition is resolved for real**
   (#138). `CalendarSyncDiModuleKt` is 4/18 with the resolution added and passing. Most likely a
   test task served from cache contributing no fresh coverage data after `just cr`'s wipe — which
   would make it a property of the measurement rather than of the code.

## Why these belong together

They are one failure mode seen from three angles: **a measurement that cannot be distinguished
from its own failure.** #136 makes the lint gate report green when the rules are not loaded. #137
makes a maintenance task silently reduce what the lint gate checks. #138 makes a coverage number
that may not describe any execution.

Each was found by hand-checking a number — the entry count, the report's rule sections, the
per-class counter. None was found by a gate, which is the point: the gates in this repository are
themselves the things that went quiet. That is a strong argument for a guard, and the guard is the
same in all three cases: **plant a violation, assert it is reported, run that before trusting a
green.**

`RuleFiresSmokeTest` is the existing precedent for a rule-level version of this and it is why
`#135` matters more than it first appears. The two rules added alongside this change have 15 and 9
positive tests and would pass #135; they are still invisible to it.

## What is deliberately not here

No fix. All three need a reproduction and a measurement this session could not complete — the
daemon's classloader cache is Gradle-internal, the `detektBaseline` plugin-classpath difference
needs a bisect, and #138 needs a forced `--rerun-tasks` before its other two hypotheses are even
worth testing. Filing the reproductions is worth more than filing a guess.

Also not here: the skill documentation gap for the two new rules (#139). That is documentation, it
has an obvious fix, and folding it into a "the tools lied" change would obscure both.
