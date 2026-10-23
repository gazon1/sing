# The gates are this checkout, and a rule that matters is a gate that can fail

Issue: — · Related: `docs/decisions/2026-10-22-the-gates-are-this-checkout.md`

Status: **accepted**
Tags: **gates tooling verification ai write-path**

## What

Three defects, one shape: **a check that measures the wrong thing looks exactly
like a check that passed.**

1. **The gates could measure a different checkout.** The gate tooling is an
   editable-installed package, so on a host with several worktrees it resolves to
   whichever checkout installed it last. The gate that exists to check the
   *registries* was checking the other worktree's registries, and the shell
   registry invoked the python gates without a path of their own. The preflight
   asked "is the tooling importable?" instead of "is the tooling *this* checkout's?".

2. **A derived catalogue was checked by nothing.** The tool catalogue is exported
   from the tool classes; nothing compared the export with the committed file, so
   it could drift for months. And when it was checked, a description written in a
   form the reader cannot parse was reported hundreds of lines away from the place
   that broke, as "the gate is reading the source wrongly" — the cause was the
   *form of the literal*, and the message did not say so.

3. **The write-path rule was a sentence.** A tool that writes a user's data must
   propose it and let the user decide. That is written down in the decision
   record, and eight tools still write directly, because nothing failed when they
   did. The delete tools and the proposal tool propose; the same act got two
   treatments, decided by which tool the model happened to pick.

## The change in behaviour

- **The gates measure this checkout.** The python gates resolve the gate tooling
  from the checkout they run in, the preflight checks *where* the tooling came
  from, and when it is another checkout's the report names the path it resolved
  to. A checkout-local environment is the supported way to satisfy this.
- **The tool catalogue is checked.** A verification task exports the catalogue to
  a scratch file and compares it with the committed one; a mismatch fails and
  says the command that regenerates it. It runs before the long test task.
- **A failure names its cause where it broke.** The catalogue reader says what
  form of the source it cannot parse, instead of the drift being reported
  elsewhere as a misreading.
- **The write path is a gate.** A tool that writes a user's data and is not
  listed, with the reason it does, fails the build. A listed tool that no longer
  writes fails too, so a converted tool cannot hide behind a stale entry. A tool
  that both proposes and writes directly fails as well.
- **The list of direct writers is expected to shrink.** Converting a tool to the
  proposal path removes its entry. A list that only grows is a list nobody is
  working through.
- **A gate run has a summary.** One line per failed gate — the gate's name and
  why it failed — and one line for the result.
- **The judgement rules live in a file.** The rules a reviewing agent applies,
  each paired with the gate that enforces it.

## What is not in this change

- **The eight direct writers are not converted.** That is a change to the tools
  and to the proposal vocabulary, and it is its own piece of work. This change
  makes the state measurable and says where the debt is.
- **The shared environment across worktrees is not redesigned.** The preflight
  catches it loudly and a checkout-local environment is supported; the
  installation layout itself is unchanged.
- **CI is not touched.** The CI job installs the tooling from the checkout it
  builds, so the origin problem is a local one.
- **The registry of gates does not change shape.** The new gate is registered in
  the same registry as the rest, and the shell registry and the registry document
  are still generated from it.

## Why now

Because the retro that asked for it found three cases of the same shape: a
check that was green and had measured something other than what it claimed. The
write-path rule is the expensive one — a rule that is written down and not
checked is a rule that is true in the document and false in the product.
