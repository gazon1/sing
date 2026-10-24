# Proposal: The agent's surface says what it does

Related ADRs: `docs/decisions/2026-10-23-the-agent-surface-says-what-it-does.md` (the
decision this change rests on), `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`
(the decision behind the external agent whose follow-ups these are).

## What this is

The external-agent change shipped five things that were known to be unfinished. Each was
recorded — in a review verdict, in an issue, or in a note — rather than quietly dropped.
This change is the work those records name.

The five are not one concern. They are five small ones, and they are worth doing together
only because they share a branch, a change record and a review.

| Item | What it is | Why it matters |
|---|---|---|
| **1.1** | The write classification is published in the exported tool surface, enforced in the catalogue, checked against the tools' behaviour, and every proposal-gated tool gets the contract the two representatives have today. | "All writes ask first" is the promise the whole agent surface makes. Today it is held by a list and two tests. |
| **1.3** | The ACP client's four mutable fields are guarded by a lifecycle lock, and there is a test for closing during a turn. | A `close()` racing a `connect()` could leave a live session bound to a closed transport. No reachable path does that today — it is latent, not live. |
| **1.4** | "The protocol owns standard output" becomes an explicit contract: a type that owns the stream, passed to whoever writes the protocol, and the logger explicitly on standard error. | The contract currently rests on a `System.setOut` side effect nobody can see at the call site. |
| **2.2** | `:mcp-server` is judged by the same detekt configuration as every other module, with the project's own rules. | A module on a reduced configuration is a module whose rules are a choice nobody made. |
| **2.3** | The external agent's ViewModel and the tool server's entry point are split by concern. | Two god classes: one holds four concerns, the other runs twelve start-up steps in one function. |

## Why now

The external-agent change is on `main`. The follow-ups it left behind are recorded in three
verdicts and one issue, and they were left behind on purpose: the change was large, and the
findings were the ones that could wait without making the change wrong.

One of them waited long enough to change shape. The write-classification work was planned
around a gate that read `config/docs/ai-direct-writes.txt`. Another change has since deleted
that file and that gate, and moved the claim into a test. The work is still owed — the
catalogue now says one tool writes directly that proposes instead, and nothing notices.

## What is not in here

- The god-class debt beyond the two classes named here. Issue #994 tracks it.
- The ACP client against a real bridge. Issue #993 tracks it.
- Any change to what a tool does. Every item here is about a declaration being true, a
  lifecycle being safe, a contract being explicit, or a class being the right size.
