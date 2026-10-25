# The proposal reads strictly

Issue: — · Related: `docs/decisions/2026-10-23-the-format-is-told-by-its-shape.md`,
`docs/decisions/2026-10-23-the-change-says-what-it-is-about.md`,
`docs/decisions/2026-10-23-the-decisions-keep-the-reasons.md`,
`docs/decisions/2026-10-23-the-identity-of-a-row-has-a-name-of-its-own.md`,
`docs/decisions/2026-10-23-a-declared-name-is-a-name-the-reader-knows.md`,
`docs/decisions/2026-10-23-the-flat-reader-goes-when-the-old-release-goes.md`,
`docs/decisions/2026-10-23-the-test-names-the-kind-of-viewmodel.md`

Status: **accepted**
Tags: **ai write-path proposals contract dispatch codec tests**

## What

The previous round closed the write path: a change that is written cannot be
lost, and a change this build cannot read stays on the card. What was left
were six places where the *reading* of a change, or the *routing* of it, was
still a matter of guesswork rather than of agreement.

1. **The sealed reader ignored what it did not know.** The one `Json` instance
   both formats were read with had `ignoreUnknownKeys = true`, so a blob a
   newer build wrote was read with the new field dropped — and the value that
   came out was a different, valid value. The sealed format was bought to stop
   exactly that, and the reader undid it.

2. **A sealed blob could be read as a flat one.** The reader tried the sealed
   format and then the flat one. A sealed blob the sealed reader refused fell
   through to the flat reader, which read it as a change with an empty field —
   "set the unknown to nothing" about a change that is something else.

3. **The routing of a change was a list of questions asked in the wrong place.**
   Five predicates in a use case re-enumerated the model's variants, and a
   kind that answered none of them fell through to the branch that assumed a
   task. The mistake surfaced as a runtime error about a kind that did not
   exist.

4. **The reasons a change failed were mirrored three times.** Three surfaces
   subscribed to the same store and each copied it into its own state, and the
   store was handed *in* by each of them.

5. **Two names were declared twice and checked never.** The field name of a
   value was declared in the variant and again in the reader, with nothing
   keeping the lists together; the identity of a written row was a plain `Any`
   in a set that could hold a missing one.

6. **The gate's exemption said something that was no longer true.** The reason
   the agenda's tests could not use the harness was that the harness did not
   expose the ViewModel — and it does. The real reason was the clock flow that
   never ends, which is what `VmScope.Background` is for.

## Why

The last round's principle holds: **a change the user cannot see is a change
nobody decided about.** The follow-ups are the same principle in the places
where it was still not enforced:

- A change read as a *different* change is a change nobody proposed.
- A change that fails at runtime — instead of at compile time — is a change
  whose route nobody checked.
- A reason that lives in three places is a reason that is in none of them.

## The shape of the change

1. **The format is told by its shape.** A blob that carries a structure is a
   sealed blob; a blob that does not is a flat one. The reader does not try one
   and fall back to the other — it knows which one it is reading.
2. **The sealed reader is strict.** An unknown key is a key a newer build
   wrote; the reader refuses the blob, and the refusal is a change that stays
   on the card with the reason.
3. **The change says what it is about.** Every kind declares its target, the
   target carries the change with it, and the compiler checks the routing.
4. **The decisions keep the reasons.** One store, owned by the thing all three
   surfaces share.
5. **A name is declared once and checked.** A test says that every field name
   a value declares is a name the reader knows; the identity of a row has a
   name of its own.
6. **The test names the kind of ViewModel.** The agenda's tests use the
   harness, and the exemption list is empty.
7. **The flat reader's removal is a checklist**, with the trigger and the
   order — so the removal is a list to work through, not a hunt.

## What it costs

- **A change a newer build wrote is not applied by an older build.** It is
  refused, with the reason, and it stays on the card. That is the point: it is
  not read as *something else*, which is what it used to be.
- **A kind added to the model now has to say what it is about.** That is the
  cost of the compiler checking the routing, and it is a cost paid once, by
  whoever adds the kind.
- **The residual limit of the strict reader is written down**: a field the
  older build knows *and* has a default for, which the newer build dropped, is
  defaulted by the older build. Closing that needs a version envelope in the
  blob; it is a follow-up, and it is not needed for the case that matters — a
  field the older build has never heard of is refused.
- **The written format did not change.** No migration, no cost to the user's
  data.

## What it does not change

- The write path: the id is still known before the row is written, and the
  take-back still addresses the row.
- The card: a change that cannot be read still stays on the card with the
  reason.
- The flat format: still read, still covered by a test.
