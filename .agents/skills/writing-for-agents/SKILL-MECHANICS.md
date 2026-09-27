# Skill mechanics

The skill-specific branch of [`writing-for-agents`](SKILL.md): what changes when the document is a skill (frontmatter, the invocation choice, and router skills). Everything else about writing it is the universal reference in `SKILL.md`.

## Invocation

Two choices, trading the two loads:

- A **model-invoked** skill keeps a `description`, so the agent can fire it autonomously, and other skills can reach it. You can still type its name: model-invocation always _includes_ user reach; a description only ever adds agent discovery, never removes the human's. The description is the skill's top-level context pointer, forced to stay loaded at all times: permanent context load in exchange for discoverability. A model-invoked skill whose content is all reference is also one home for shared reference: another skill can invoke it, so reference needed by several skills lives in one place. Mechanics: omit `disable-model-invocation`, and write a model-facing description carrying the trigger branches (the pointer-writing rules in `SKILL.md` apply in full).
- A **user-invoked** skill strips the description from the agent's reach: only the human typing its name can invoke it, and no other skill can. Zero context load, but it spends cognitive load: you are the index that must remember it exists. Mechanics: set `disable-model-invocation: true`; the `description` becomes human-facing: a one-line summary, trigger lists stripped.

Pick model-invocation only when the agent must reach the skill on its own, or another skill must. If it only ever fires by hand, make it user-invoked and pay no context load.

Shared reference that two user-invoked skills both need can live in neither: with no descriptions, neither can fire the other. Push it to a plain file outside the skill system: external reference any skill can point at.

## Splitting by invocation

The invocation cut of splitting (the sequence cut lives in `SKILL.md`): split off a model-invoked skill when you have a distinct leading word that should trigger it on its own (a trigger word you actually use in your prompts), or another skill must reach it. You pay context load for the new always-loaded description, so that independent reach has to be worth it.

## Router skills

When user-invoked skills multiply past what you can remember, that piled-up cognitive load is cured by a **router skill**: one user-invoked skill that names the others and when to reach for each, so the human has one skill to remember instead of many. It can only hint, never fire them: user-invoked skills have no description, so nothing but the human can reach them.

This repo has 89 skills and **no router skill**, deliberately. Every skill here is
model-invoked, so discovery already happens: the `description` fields are the index, and a
mega-router would spend that same budget re-listing what discovery does. The inventory
lives in `docs/SKILLS-CATALOG.md` — a plain file pointed at from `AGENTS.md`, so it costs
no context until someone opens it. Regenerate with `just docs-regen`.

## Size budgets (enforced)

`python3 scripts/check-doc-sizes.py`, run by `just docs-audit`:

| Budget | Limit | Why |
|---|---|---|
| `SKILL.md` body | 500 lines | progressive disclosure: body ~5k tokens is the useful ceiling before the agent skips it |
| `description` | 1024 chars | the description is resident context for every skill in the repo |
| `AGENTS.md` | 250 lines | the "two hundred lines helps you notice growth" guideline |
| `DIGEST.md` | 1500 lines | forces selection in the generator rather than manual pruning |

## Sibling files, not a mega-file

A skill over the budget splits by **topic**, not by arbitrary cut: each leaf is a thing
you would load on its own. `SKILL.md` becomes a router that keeps the decision rule inline
and points at the leaves.

Worked examples here:

- `singularity-todo-shared-ui-components` — 756 → 42-line router + 5 leaves
  (`widget-library`, `decomposition`, `content-slot-api`, `menus-and-dialogs`,
  `document-style-layout`)
- `singularity-todo-ui-event-vs-state` — 679 → 63-line router + 8 leaves

Two properties matter for the split to be worth it:

1. **`SKILL.md` stays the entry point**, so every existing reference to the skill keeps
   resolving. A split that renames the directory breaks inbound links.
2. **The decision rule stays in the router.** The most-repeated content — the category
   table, the threshold, the checklist — is what the agent needs every time; only the
   long tail becomes leaves. A router that is pure navigation costs a file read to get
   nothing.

## Description as an invocation condition

State **which tasks need the skill**, not what it contains. "Use when a `Screen.kt` grows
past ~150 lines" tells the agent when to fire; "covers the shared widget library" does not.
The same advice drives `singularity-todo-*` descriptions in `docs/SKILLS-CATALOG.md` —
read a few and you can see the trigger branches.

## Gotchas section, grown from real failures

A `## Gotchas` list built from mistakes the agent actually made while following the skill
is worth more than any amount of general advice — the failure is what makes the rule
land. In this repo those gotchas became detekt rules (`NoStateIn`, `NoFactoryViewModel`,
`PassThroughUseCase`, the KDoc pair), which is the strongest form: a rule that fails the
build does not need to be remembered.
