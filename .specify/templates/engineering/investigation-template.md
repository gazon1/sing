# Investigation — <title>

> Written by `speckit-engineering-explore`. Every claim below needs a citation: `path:line`, or a
> command you actually ran. An uncited claim belongs under `## Hypotheses`.
>
> This document records **what happens today**. It does not decide what should happen — that is
> `spec.md`.

## Request

_<The engineering problem as asked, in your own words. Restate it so a reader six weeks from now
knows what was actually being solved._

## What the code actually does

_<The current behavior, traced through the code._

- `path/to/File.kt:120` — <what actually happens here>
- `path/to/File.kt:145` — <what actually happens here>

_Delete nothing. If a section does not apply, say why._

## Reproduction

- Command: `<exact command>`
- Observed: `<exact output, or the error>`
- Result: reproduced | **not reproduced** — <what you tried instead>

_If you did not reproduce the symptom, write that plainly. Do not describe expected output as if it
were observed output._

## Verified facts

_<Read from the code or produced by a command you ran._

1. <fact> — `path/to/File.kt:120`
2. <fact> — `./gradlew :shared:jvmTest --tests 'SomeTest'` → <what it printed>

## Hypotheses

_<Possibilities that have not been confirmed. Not conclusions._

### H-1 — <the hypothesis>
- **Evidence for:** <what points this way>
- **Evidence against:** <what points elsewhere>
- **Would be confirmed by:** <the specific observation that would settle it>
- **Would be refuted by:** <the specific observation that would kill it>

## Open questions

1. <question that must be answered before the spec can be correct> — _who can answer it?_

## Constraints found

_<Existing patterns the solution must respect. These are what make the plan fit the codebase._

- <constraint> — `AGENTS.md`, `docs/CONTEXT.md`, or `path:line`
- <constraint> — e.g. an existing ADR that already decided part of this

## References

- `docs/decisions/<slug>.md` — <what it decided>
- `openspec/specs/<capability>/spec.md` — <what it already specifies>
- <any existing tests that already constrain this behavior>