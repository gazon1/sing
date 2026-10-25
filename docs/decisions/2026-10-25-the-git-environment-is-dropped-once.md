---
title: "The git environment a hook exports is dropped once, and the gate knows the form it takes"
date: 2026-10-25
status: accepted
tags: [gates, tooling, pre-push]
---

## Context

Git exports `GIT_DIR` — and `GIT_WORK_TREE`, `GIT_INDEX_FILE`,
`GIT_COMMON_DIR` — to the hook it runs, and they name the worktree the push
was made from. A command that means "the repository in this directory" must
not inherit them: a `git config` in a test's temporary repository then writes
into the repository the hook runs in, and this checkout is shared by ~20
worktrees. On 2026-10-23 one run of the test suite wrote four such values into
the shared configuration.

The pre-push hook ran its steps with the names removed, and it wrote the
removal down — the same four names, in the same order, once per step. Four
steps carried the list, and the header carried it a fifth time. The hook's
own header said the list of the names lives in one place,
`scripts/src/sgate/gatekit/gitenv.py`, so that the hook, the gates and the
tests cannot disagree about it. That was true of every part of the hook except
the part that removed the names: there, the hook carried a copy of the list and
the claim was a sentence in a comment.

The copy was not a list of names only. Each copy was a list of names *and* a
decision about which names to remove, written in a place where nothing checks
it. The contract test looked for the four names on every line that mentioned
`env -u GIT_DIR`, so a copy that dropped three of the four would have been
caught — after the fact, by a test nobody runs on every edit, and only in the
hook. The gate that checks the scripts, `scripts/check-git-environment.py`, did
not look at the hook at all: it scans `scripts/`, and the hook is in
`.githooks/`.

The gate also had a hole of its own, on the other side: it recognised the
removal by the mention of ONE name. `env -u GIT_DIR` was enough to satisfy it,
and a script that dropped one of the four names and read another tree passed
the gate that exists to say it does not.

## Idea

The names are an answer, and the answer has an owner. The shell cannot import
the module that owns it — the hook runs where the package is out of reach — but
it can be told the answer, and then it can hand it to every command it runs.

A gate that checks a form has to know every form. The form that reads the
answer at run time looks nothing like the form that writes it down, so the
gate has to learn it — and a gate that learns a form has to learn it properly:
the helper has to be one that removes all four names, not one that removes
one.

## Decision

1. **The names are read once, from the canon.** `sgate.gatekit.gitenv` grows
   `shell_unset_args()`, which renders the answer in the form `env` takes, and
   a `__main__` that prints it. The module is read by path, because the tree
   being pushed carries the file and not the package around it.

2. **The helper lives in `scripts/git-env.sh`, and the hook sources it.** The
   helper reads the answer from the canon when it is sourced and defines
   `without_hook_git_env`, which runs a command without the environment a hook
   exports. The file carries no copy of the names — a second copy is a list
   that can drift, and the drift is invisible until a step breaks on a tree
   that was fine.

3. **Every step of the hook that runs in the tree being pushed goes through
   the helper.** Four copies of the removal became one call.

4. **A push that cannot read the answer does not push.** The hook fails with a
   message that says what to run. A hook that ran its steps with the
   environment of the worktree the push was made from would read the wrong
   tree, and a push that says "the tree is clean" about the wrong tree is
   worse than a push that says nothing.

5. **The gate knows the new form.** A shell script that calls the sanctioned
   helper controls its environment — and the helper has to be available to it:
   either the script defines it, or it sources the file that holds it. A
   script that calls a helper nobody defined controls nothing, and a gate that
   believes it does is worse than a gate that says nothing.

6. **The old form has to be complete.** The gate now requires that a line
   removes every name the canon names, not one of them. Three of four names
   are not four names: the tree is read through the index that was left behind,
   and the command reports about a tree nobody pushed.

7. **The gate sees the command behind the helper.** `without_hook_git_env git
   ls-files` runs `git ls-files`, and the gate reads it as the command it is.
   Otherwise the helper would hide the very command the rule is about.

## Rationale

**Why the helper is a file and not a function in the hook.** A function in the
hook would be one copy of the answer in the hook and one in the canon, and the
hook's header would still be a sentence about a copy rather than about the
answer. A file under `scripts/` is also something the gate can see: the rule
that a script which calls the helper controls its environment is only sound if
the gate can tell whether the helper is there, and a file it can resolve is
the only thing it can resolve.

**Why the helper reads the answer instead of carrying it.** The list of the
names is four words long, and four words are easy to keep right — until there
are two copies of them and one of them is a comment. The canon already owns
the answer; the helper asks for it.

**Why a push that cannot read the answer does not push.** The alternative is to
run the steps with the environment inherited and report the result. The steps
would read the wrong tree, and the report would be about that tree — which is
the failure this whole arrangement exists to prevent. Failing with a message
that names the command to run is the honest answer: the hook cannot say the
tree is clean when it did not look at the tree.

**Why the gate requires the helper to be available.** A rule that accepts the
word `without_hook_git_env` anywhere in a script would accept a script that
calls a function that does not exist. That script fails when it runs — loudly,
but at a moment when the reason is not in view — and the gate would have said
the tree was fine. The gate accepts the call only when the helper is defined
in the script or in a file the script sources.

**Why the old form had to become complete.** The gate's claim is that a script
which reads another tree neutralises the four variables. It checked for one of
them. A script that dropped `GIT_DIR` alone — the variable that names the
repository the command is about — and left the other three inherited passes a
gate whose whole subject is the four of them. Requiring all four is what the
claim says.

## Consequences

- The hook carries no copy of the names, and the header's claim about the
  canon is now true of the part of the hook it was about.
- The helper is one file, and it is the answer for every script that wants it
  — not only for the hook.
- A script that drops one of the four names is now a violation of the gate
  instead of a pass. Nothing in this repository was relying on the old
  leniency: the only script that used the old form, `scripts/doctor.sh`,
  already removed all four.
- The gate reads the command behind the helper, so a script that wraps every
  git command in it is still checked — the rule is not bypassed by wrapping.
- The hook fails the push when the canon cannot be read. On a checkout that
  predates this change there is no push to make — the hook is the checkout's
  — so nothing that used to work stops working.

## Links

- ADR `2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md` —
  the damage that made the rule, measured
- ADR `2026-10-25-the-hook-does-not-name-a-gate.md` — the gates the hook runs
  are declared once
- `scripts/src/sgate/gatekit/gitenv.py` — the canon
- `scripts/git-env.sh` — the helper
- `scripts/check-git-environment.py` — the gate that knows both forms
