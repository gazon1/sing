---
title: "A press is a turn, until it has to change something"
date: 2026-10-06
tags: [genui, a2ui, architecture, llm, chat]
status: accepted
---

## Context

A control in a generated surface reports `(surfaceId, name, data)` and nothing else. The chat turns
that into the next turn of the same conversation: a press becomes a prompt, the model answers, and
the surface changes. That is the whole mechanism today.

It works for the majority of controls, which ask rather than act. `open_task` wants the user to see a
task; "show only overdue" wants a different answer. The model is already in context, the user is
already in a flow, and a tool call would add a round trip to say less.

It does not work for a control that has to *change* something. The first real case is the form: a
user types a title, presses "Add", and the intent is that a task now exists. Today that press would
be sent to the model as "the user pressed Add", and the model would have to infer the fields from
the conversation, re-derive the title, and call a tool on the user's behalf — for a value the user
just typed into a control the model drew.

That last clause is the problem. The values exist only in the surface's data model; the model never
saw them. So the one action that most obviously needs to mutate is the one action the model is least
able to perform correctly.

Two shapes are available, and the choice has to be made before three forms ship rather than after.

## Idea

1. **Keep one path.** A press is always a turn, and mutation is the model calling a tool because the
   turn asked it to. Simple, and the mechanism already exists.
2. **Two paths from the start.** A press is either a turn or an action, chosen by where the name
   comes from: names the catalog declares are actions, everything else is conversation.
3. **Every press is an action.** The surface reports intent; the client decides what to do with it,
   and the model is told what happened rather than deciding what to do.

## Decision

Option 2, with the split made at the catalog rather than at the control.

**A component declares whether its action mutates.** The catalog already describes every control and
already reaches the model verbatim, so this costs one property per control rather than a second
mechanism. The property is a claim about the control's meaning — "pressing this changes something"
— not about what the change is.

**A mutating action is executed by the client, not by the model.** The action registry maps the name
to the same tools the AI layer already has. The payload the model wrote — now resolved against the
data model at press time, so it carries what the user typed — is the tool's arguments. The model is
not asked to reproduce values it never received.

**Everything else stays a turn.** Navigation, filtering, "tell me more" are conversation, and
routing them through a tool call would add latency and a failure mode to the majority of controls to
serve the minority.

**Option 3 is rejected** because it takes away the thing that makes the layer work at all. A surface
the client interprets is a client that has to understand every possible action before the model can
draw anything, which is the opposite of a catalog.

## Rationale

The deciding argument is that the two cases have genuinely different *sources of truth*. A turn's
input is the conversation, and the model owns it. An action's input is the data model, and the user
owns it — they just typed it. Sending both through one channel forces the weaker owner to restate
what the stronger one already has.

Splitting at the catalog rather than at the call site matters for the same reason the catalog is the
contract in the first place: the distinction has to be visible to the model, because it decides what
a control can promise. A control that says "this adds a task" and turns out to only re-ask the model
is a lie the model made on the user's behalf.

The cost is real and worth naming: two mechanisms, one more property on every control, and an action
registry that has to exist before the first mutating control does. Deferring it is also possible and
is what happens by default — but the deferral is invisible, because until a mutating control ships,
"a press is a turn" looks like a complete design rather than a temporary simplification.

## Consequences

- `UiNode` gains one property on controls that mutate; `A2uiComponentSchema` gains the matching one.
- A new `GenuiActionRegistry` maps names to tools. It is the first consumer of the payload a button
  already resolves.
- The rejection counter becomes more useful: an action name the registry does not know is now a
  reportable defect rather than a sentence sent to a model.
- The prompt has to say which controls act and which ask. That is a wording change, and the honest
  cost of the split is that the model carries one more rule.
- Forms do not need a `form` component to submit. The payload templates already express "send
  everything under this path", and the action that receives it does the rest.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/render/GenuiText.kt` — payload
  resolution at press time
- `docs/decisions/2026-10-05-genui-surface-belongs-to-one-answer.md` — the surface this runs against
- `.agents/skills/singularity-todo-genui-catalog/SKILL.md` — where a new control declares itself