# failure-visibility

Issues: #124, #126, #132 · Follows: `apptracer-integration`
Spec deltas: `core/ui-mvi` (REQ-1, REQ-2), `crash-reporting` (REQ-4)

## What

Close the three places where a failure the user caused is reported to a machine but never
reaches the user, and the one where a failure the machine caused looks like success.

1. `EventBus.emit` throws when a coroutine emits after `MviViewModel.onCleared()` closed the
   channel. Since the background failure handler landed, that is no longer a process death — it
   is a non-fatal report per occurrence. The user is unaffected and the report channel fills
   with a defect nobody can act on.
2. `AgendaViewModel`'s `toggleComplete`, `togglePinned` and `restore` discard their `Result`.
   Reporting was added; feedback was not, and there is nowhere to put it because
   `AgendaUiEvent` has no error variant.
3. `AppVersionGateViewModel` now fails open — a throwing remote-config read is reported and the
   default snapshot is evaluated. Correct for a version gate, and it makes a config outage
   indistinguishable from a healthy response: both end in `Allowed`.

## Why

The observability work connected the presentation layer to a reporter, and in doing so made
three kinds of silence *visible in a report* while leaving them silent *for the user*. That is a
real improvement and an incomplete one: a failure that is triaged by a machine and experienced
as nothing by a person is still a bug from the user's side, and now it also spends a report
group.

Case 1 is new noise rather than an old silence, which makes it the one to do first: it was
introduced by the fix for a different problem.

Case 3 is the mirror image — a deliberate availability trade whose cost is that the dashboard
reads healthy during an outage. The port already carries the mechanism for saying so.

Case 2 is not new, and it is the only one of the three that needs a product decision rather than
a mechanism.

## Where the two halves are specified

The user-visible half is an MVI concern and is specified in `core/ui-mvi` — the id
`MODULE-INDEX.md` already reserved for that module, rather than a new capability invented
here. The breadcrumb half is a reporting concern and is specified in `crash-reporting`,
which `apptracer-integration` will create when it is archived. Splitting them keeps each
delta in the capability that already owns the contract, so a later change to either area
amends one spec instead of forking a parallel one.

## Scope boundary

Case 2 requires a new event variant and screen handling. That is a user-visible change and
belongs in this change, not inside the reliability fix that found it — which is precisely why
the mutations report and do not surface, with the reasoning recorded at the call site.

Cases 1 and 3 change no user-visible behaviour: an event emitted after close becomes a dropped
event instead of a crash, and a gate bypass becomes greppable instead of invisible.

## Not in scope

- The ~197 `runCatching` sites in the data layer (#131). That is a per-site review, not a rule.
- `AppError` code vocabulary (#130). Per-site domain judgement; the grouping contract is
  already specified.
- The `LocalHaptic` change (#133). No user-visible behaviour; it needs a house-convention
  decision, not a spec.
