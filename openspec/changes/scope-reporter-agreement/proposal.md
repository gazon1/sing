# scope-reporter-agreement

Issues: #143 · Follows: `background-handler-injection` (archived), `detekt-rule-has-positive-control`

## What

A component can end up with two reporting destinations that are not the same one, and nothing
in the build notices.

When a component starts background work, it normally inherits both of its failure paths from a
single dependency: the failures it handles explicitly, and the failures that escape its
background work. Both go to the same place, because both are derived from the reporting
destination that component already holds.

That derivation is a default. A component that supplies its own background work context instead
of accepting the derived one supplies the two independently, and from that moment they are equal
only by however the binding happens to be written. Today two of roughly thirty components are in
that position; both currently resolve the same destination, so **no failure is currently going
anywhere unexpected.**

## Why

The migration that produced the current shape replaced a process-wide default with an explicit
choice made by the owner of the work. That removed the class of defect where a component could not
say where its own failures went. It introduced a narrower one: a component that overrides the
work context can now name a different destination for escaped failures than for the failures it
handles, and the two would be indistinguishable in a report — same symptom, two places, nothing
greppable.

This is the same class as the defect the previous change closed, at the other end. That one was a
binding that shipped a destination which discarded everything; this one is a binding whose two
destinations are chosen separately and are not required to be equal. Neither is detectable by
asking whether a destination exists.

## Why it is not a rule

The check that guards the first half asks whether a binding passes a reporting destination by
name. It cannot ask whether the work context on the adjacent line resolves to that same
destination: correlating two sibling arguments is not a name-resolution question, and the rule has
no type resolution. Adding a rule here would produce a gate that cannot fail — the artefact this
repository keeps finding, not the guarantee it is looking for.

The two shapes that *can* be checked are a test that resolves each component and compares the
resolved destinations, and a change that makes the disagreement unrepresentable. The second is
smaller and matches what most of the audited components already do.

## What is deliberately not here

No fix. This change records the requirement and the decision that has not been taken yet; the two
options are set out in #143 and the choice between them belongs to whoever implements it.

Also not here: the three components that are already consistent by construction. They are named
in the issue so a future audit does not re-count them, and the requirement below is written to be
satisfied by them already.
