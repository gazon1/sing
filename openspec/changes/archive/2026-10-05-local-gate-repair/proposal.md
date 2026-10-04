# local-gate-repair

Issues: #122, #123

## What

Make the local gate runnable, and leave it with one authority per concern.

`just cr` invokes `:shared:koverXmlReport`, a task that does not exist. Coverage is aggregated at
settings level, so only the root project registers Kover tasks. Because `coverage-ratchet` is step
**3/4** of the `gate` recipe, `just gate` cannot complete — it dies on a task-name error before
reaching the Maestro flows, regardless of the code under test.

`gate` step 2/4 also inlines its own list of detekt tasks, and that list has already fallen behind
the `lint` alias, which names four modules where the inline list names two.

## Why

CI is unaffected, and that is what makes this worth filing rather than shrugging at.
`.github/workflows/ci.yml` calls the root `koverReport` deliberately, with a comment warning off
exactly the two wrong invocations. So the coverage floors *are* enforced, by a different code
path, and the local gate is a false comfort: it reads as the thing that would catch a regression,
and it cannot reach its own third step.

The second half is the more durable defect. Two lists of the same set of gates is a list that
will drift again — and this one already has, silently, in the direction that lints less.

The root `build.gradle.kts` warns about exactly this for the Kover task list, in a comment, for
the same reason. The warning was written; the second list was written anyway.

## Scope

Recipes and wrapper calls only. No change to what any gate checks, what the coverage floors are,
or what CI does. CI is the authority and is already correct; this change makes the local path
agree with it rather than inventing a second opinion.
