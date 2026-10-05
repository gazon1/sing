# genui-catalog-contract

**Status:** proposed · **Spec:** `genui-catalog` (new) · **ADR:** `docs/decisions/2026-10-05-genui-catalog-as-contract.md`

## What

A generated-UI surface and the model that produced it SHALL agree on a contract, and every
disagreement SHALL be visible. Five requirements:

1. The set of renderable components and their properties SHALL be declared once, and the agent's
   instructions, the validation of its output, and a machine-readable schema SHALL all be derived
   from that one declaration.
2. Input that violates the contract SHALL be rejected with a machine-readable reason, a location
   inside the offending message, and a severity — never silently discarded.
3. A rejection at the level of one component SHALL NOT prevent the rest of the surface from
   rendering.
4. Rejection reasons SHALL be reported back to the agent, in a bounded number of additional
   attempts, and an agent that does not converge SHALL end at a reported failure.
5. An update to one component SHALL NOT require re-rendering the rest of the surface.

## Why

The layer renders production UI (a release-note sheet) and has 14 component kinds, a parser, a
renderer registry, a data model, a transport and an engine. The engine had no caller at all: the one
live screen assembled its own parser, controller and registry instead, so the LLM path was
unreachable from the app. The transport emitted token fragments while the parser expected complete
messages, so that path could not have worked even if it had been wired.

The contract itself was the smaller half of the problem. The agent was told which component kinds
exist and nothing about their properties — types, which are required, which values are legal, which
components may contain which. Every violation of those unstated rules, plus every malformed
message, was discarded without a word to anyone. A message naming a component that does not exist
and a message missing a required field produced the same outcome as each other: nothing.

That is the failure this change is about. Generation quality is not a prompt-tuning problem when
the client cannot report back what it rejected. The most useful single fact available to a model
that invented a component is the list of components that do exist.

Requirement 5 is in the same change because the surface's reactivity was nominal: a data update
mutated the shared model and re-published the same immutable object to force an emission, and every
node in the tree observed the whole surface. Fine-grained rendering is what makes a catalog worth
extending — otherwise adding a component multiplies the cost of every update, which is the reason
the catalog stayed small.

## Why not

**Adopting the A2UI v0.9 wire format.** Higher fidelity to the specification, and the right answer
if interoperability with third-party agents is ever required. Rejected for now: release-note content
is served into the app from outside this repository, and the field names on the wire are exactly
what a format change breaks. The catalog idea is separable from the wire names, and the seam for a
spec adapter exists in the new core package.

**Keeping the name list and adding properties to the prompt text.** Prompt text and validation drift
apart within about two changes, and only one of them can be tested. The declaration is written once
and both consumers are generated.

**Fixing the engine and the transport in a separate change.** They are not separable in practice:
the unwired engine is a defect only because the transport cannot parse what it receives, and a
transport fix with no caller has no test. The catalog and the wiring are in one change so that the
agent's instructions, the validator and the transport that runs are all exercised by the same
scenarios.

## Scope

In: the component/function catalog, validation and error reporting, framing of a model stream into
complete messages, the bounded correction loop, per-component rendering updates, two-way form
fields, three domain components, the engine's connection to the AI chat, and the move of the layer's
dependency bindings into a single shared module.

Out: the A2UI v0.9 envelope and component naming, catalog negotiation and data-model sync with the
specification, a dedicated navigation destination for generated surfaces, and any component that
reads application repositories instead of the surface's own data model.
