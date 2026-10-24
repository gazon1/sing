---
title: "A declared name is a name the reader knows"
date: 2026-10-23
status: accepted
tags: [proposals, domain, values, tests]
---

# A declared name is a name the reader knows

## Context

Each of the four proposal dictionaries — task, note, project, tag — says the
name of its field twice. Once in the variant:

```kotlin
data class Title(val value: String) : TaskValue {
    override val fieldName: String = "Title"
```

and once in the reader:

```kotlin
fun read(field: String, text: String): ReadResult<TaskValue> = when (field.trim()) {
    "Title" -> ReadResult.Ok(Title(text))
    "Description" -> ReadResult.Ok(Description(text))
    ...
    else -> ReadResult.Rejected("\"${field.trim()}\" is not a task field.")
}
```

Nothing kept the two lists together. A field added to a variant and not to the
reader is a field that does not read: the value comes back as "not a task
field", the change becomes a value the user never asked for, and the reason
says nothing about the field — because the reader never heard of it.

The other direction is quieter still: a name the reader knows and no variant
declares is a name that reads as *some other* field, and the change the user
sees is a different change from the one the model asked for.

## Idea

The two lists are the same list. Say so, and check it.

## Decision

**There is a test, and it checks both directions.** For every value each
dictionary declares — the ones that are readable by design — the test reads the
value's own text back through the dictionary's reader and asks two things:

- does the reader know the name at all? (the name is not rejected as unknown)
- does the reader read it as *that* field? (the value it returns carries the
  same name)

The values that cannot be read by design — each dictionary's own `Unreadable`
— are left out: their whole purpose is to be a field the reader does not know.

**The test says what a stale name costs.** A name that no value declares does
not read: the reader refuses it and the reason names the field it did not
know.

## Rationale

The duplication is in the design — the variant is the field, and the reader
maps a name to a variant — so the two cannot be merged without giving up one
of them. What can be done is to make the drift loud.

The failure is not visible at runtime. A field that does not read produces a
`Rejected` with a reason that looks like a user error ("«Category» is not a
task field"), and the change stays on the card with that reason — the user is
told the model named a field wrong, which is true, but the field *does* exist
in the model. Only a test that compares the two lists can see the difference.

Both directions are checked, because they fail differently: a name the reader
does not know makes the change unreadable, and a name the reader reads as
another field makes the change *wrong* — and the second is the one that reaches
the user's data.

## Consequences

- **A field added to one dictionary and not to the other fails the build.**
- **A name that reads as another field fails the build.**
- **The test is in `commonTest`**, so it runs wherever the model runs.

## Links

- `shared/src/commonTest/kotlin/com/singularity/todo/feature/proposals/ProposalValueFieldNamesTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalValues.kt`
  — the four dictionaries.
- `docs/decisions/2026-10-23-the-lifecycle-belongs-to-the-domain.md`
  — the same principle, applied to the status.
