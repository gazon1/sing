---
title: "Name length limits count code points, and live in exactly one layer"
date: 2026-10-05
tags: [projects, tags, validation]
status: accepted
---

## Context

Two defects, found together because fixing either alone leaves a broken path.

**UTF-16 counting.** Project and tag name limits were checked with `String.length`, which counts UTF-16 code units. An emoji outside the Basic Multilingual Plane — `🎉`, four units — is charged twice, so a name the user reads as 25 characters measures 50 and is rejected by a limit whose own message says "max 50 characters". ASCII names were unaffected, which is why this survived: nothing in the app's own content was long enough to hit it.

**Duplicated rule.** `ProjectsDomain.validateCreateInput` and `ProjectEditorViewModel.validateName` each held a private copy of the same three lines — blank check, the limit `50`, and the message. Fixing one copy's arithmetic would have left the other still rejecting emoji, and the two could already disagree about what a valid name is.

`TagDomain` turned out to already model the right shape: one `validateName`, composed by `validate`, with both create and update calling it. The project path had simply never adopted it.

## Idea

1. Add `String.visibleLength()` and use it everywhere a limit is read as "characters".
2. Collapse the project rule to a single `ProjectsDomain.validateName`, and have the editor surface that verdict rather than re-derive it.

## Decision

Both. `core/text/TextMetrics.kt` holds `visibleLength() = codePointCount(0, length)` — one definition, no new type. `ProjectsDomain.validateName` becomes the only copy of the project rule; `ProjectEditorViewModel` delegates to it and its private duplicate is deleted. `TagDomain` keeps its shape and only switches the arithmetic.

**`AuthDomain.MAX_EMAIL_LENGTH` is deliberately left on `String.length`.** A valid email address is ASCII per RFC 5321, so code points and UTF-16 units agree for every legal value; changing it would churn working validation to fix nothing. The exemption is recorded in the helper's KDoc so a later reader does not "helpfully" apply it there.

The limit is unchanged numerically — still 50 project characters, 100 tag characters — but is now measured in code points. This makes emoji *cheaper* per glyph in the budget; it does not loosen the practical cap, since 50 emoji is already a very long project name.

## Rationale

The message says "characters", so the code should count characters. Anyone reading `name.length > 50` reasonably believes it counts the same thing the message claims.

The duplication mattered more than the arithmetic. Two copies of one rule means the two layers can disagree about what a valid name is — and the editor is exactly the layer where a disagreement is invisible until a save is rejected. `TagDomain` proved the single-source shape already fits this codebase; matching it removed a class of drift rather than one instance.

## Consequences

- `ProjectEditorViewModel` depends on `ProjectsDomain`. The dependency runs the right way: presentation already imports the domain for `CreateProjectInput`, and the rule is now visible to it rather than hidden.
- The editor's error string is `ProjectsDomain`'s message, so the two cannot drift in wording either.
- A boundary test pairs the editor against the domain for the same over-long name — that is the guard which keeps the duplication from returning.
- `visibleLength()` is a top-level `String` extension in `core/`. Modelling it as a value class was considered and rejected: three limits across three features do not justify a new type yet.

## Links

- `core/text/TextMetrics.kt`
- `feature/projects/domain/ProjectsDomain.kt` — `MAX_NAME_LENGTH`, `validateName`
- `feature/tags/domain/TagDomain.kt`
- `commonTest/.../NameLengthValidationTest.kt` — the boundary, for emoji and ASCII alike