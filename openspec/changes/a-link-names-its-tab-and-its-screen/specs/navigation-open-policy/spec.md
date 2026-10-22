# A link names its tab and its screen — Observable Behavior

**capability:** `navigation-open-policy` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-NAV-008

A deep link **SHALL** resolve to exactly one target — a top-level tab and a screen —
and opening it **SHALL** make that tab active with a back stack of exactly
`[tab, screen]`.

**Rationale:** a link that opens a screen without naming a tab opens it on
whatever the app happened to be showing, so "back" returns to an unrelated
screen. The link is the app's only public surface; it has to say where it goes.

The link is resolved by a registry that answers from an explicit, ordered list of
the app's links. The order is part of the contract: the first matching link
wins, because "the best match" is not defined when two templates accept the same
URI.

**Test coverage:** `DeepLinkRegistryTest` (the link table, the order of the
checks), `Nav3StateDeepLinkTest` (the stack and the tab), `DeepLinkGraphTest`
(the registry resolves from the real DI graph).

#### Scenario: A task link opens the task on its tab
- A link `singularity://task/{taskId}` arrives
- The task detail is opened on the Agenda tab
- The Agenda tab's stack holds the tab and the task screen, in that order

#### Scenario: Back from a linked screen returns to the tab's own screen
- A link opened a task detail on the Agenda tab
- The user presses Back
- The Agenda tab's own screen is shown

#### Scenario: A link on another tab switches to it
- A link names a screen on a tab that is not the active one
- The named tab becomes active
- That tab's stack holds the tab and the screen, in that order

#### Scenario: An unknown link changes nothing
- A link arrives that matches no registered link
- The app stays where it is
- No stack is rebuilt

#### Scenario: The first matching link wins
- Two registered links would accept the same URI
- The one that comes first in the list is the one that opens

---

### Requirement: REQ-NAV-009

The grammar of a link — its URI template and the reverse parsing of it —
**SHALL** be declared once, by the feature that owns the link, and **SHALL** be
read from there by both the producer of the link and the matcher that consumes
it.

**Rationale:** the task link's format lived in three places at once — the
producer, a constant nobody read, and a second parser that read the link out of
free text by hand. A change to one of them left the other two in the old
format, and the link stopped opening with no error anywhere.

**Test coverage:** `TaskLinkSpecTest` (the round trip, in a description and on
its own).

#### Scenario: The producer and the matcher agree
- The calendar sync writes a link from the spec
- The matcher parses a link with the same spec
- The identifier that goes in is the identifier that comes out

#### Scenario: There is one parser
- A link is found inside a free-text description
- The identifier is read by the spec, not by a second hand-written parser

---

### Requirement: REQ-NAV-010

An entry point that receives a link **SHALL NOT** parse it. It **SHALL** build a
request from the incoming URI and ask the registry what it opens, and a URI that
matches nothing **SHALL** leave the app on its current screen without an error.

**Rationale:** the magic-link reader pulled two query parameters out of any
intent data, without checking the scheme or the host — so a link with the right
parameters but the wrong authority was a magic link. The grammar belongs to the
feature; an entry point that parses it is a second grammar.

**Test coverage:** `DeepLinkRegistryTest` (unknown host, other scheme, missing
argument, extra path segment — no match).

#### Scenario: A link with the wrong authority is not a link
- An intent carries `?email=` and `?token=` but its authority is not the one the
  magic link declares
- Nothing is opened

#### Scenario: A malformed link is not a crash
- A link arrives that cannot be read as a URI
- The app opens on its start tab
- No exception reaches the user
