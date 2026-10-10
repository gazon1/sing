---
title: "The Dead Refs Gate Was Green Locally And Red In Ci"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Status: RESOLVED (2026-10-04).** Fixed on the verifiability branch; the
regression test is `scripts/tests/test_check_doc-dead-refs.py` (7 tests).

**Found in:** 2026-10-04, on the first CI run of the verifiability branch. The
meta-gate found it, which is the only reason it was found at all.

**Tracked as:** fixed on the verifiability branch; regression test
`scripts/tests/test_check_doc-dead-refs.py`.

**Symptom.** `test-and-check` failed at "Gates are wired and can fail" with:

```
ERROR: gate 'doc-dead-refs' already fails on a clean tree (exit 1)
```

`check-doc-dead-refs.py` passes in every developer checkout and fails in a fresh
`git clone --depth 1`. **A gate whose result depends on the machine is the worst
shape a gate can have**: everyone trusts a signal that is not portable, and the
failure only appears for whoever has no local hook state.

**Cause.** `DIGEST.md` is gitignored on purpose — rebuilt by a post-checkout hook,
absent in a fresh clone, present after a docs refresh. The gitignore handling was
added *for exactly that reason* and it did not cover every form.

A gitignore pattern containing `/` is anchored at the repo root, so
`docs/decisions/DIGEST.md` matches that path and nothing else. Three skills
reference the same generated file by **basename** as plain `DIGEST.md`, and this
same script resolves references by basename elsewhere. `is_generated` tested only
the literal string, so those three were reported dead on a fresh checkout and silent
anywhere the hook had run.

**Fix.** `is_generated` also matches the ref's basename against the basenames of
gitignored *files* — restricted to entries carrying an extension, so a gitignored
directory named `build` cannot make an unrelated `build` look generated.

**The part worth keeping.** The regression test asserts the *negative* direction too:
`GLOSSARY.md` and a real source path must still be reported dead. A basename rule
that is too broad does not fail loudly — it just stops the gate measuring anything,
which is how this repository ended up with sixteen gates that reported success
without testing anything.

**Also fixed, found by noticing it in a staged diff rather than by a gate:**
`.gitignore` had `scripts/__pycache__/`, which does not cover
`scripts/tests/__pycache__/`, so the new test's bytecode staged cleanly. Widened to
`__pycache__/` at any depth.

**Try next — the general form.** A gate that passes locally and fails in CI is
usually assuming a developer-machine artefact: a generated file, a hook, a warm
cache, a local SDK. `check-gate-wiring.py` catches the "cannot fail" direction; this
is the "cannot be trusted" direction, and nothing catches it. Running a gate against
a fresh `git clone --depth 1` is the cheap test, and it is what turned a red CI job
into a one-line fix instead of an afternoon.

---
