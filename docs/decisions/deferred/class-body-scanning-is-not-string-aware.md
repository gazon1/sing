---
title: "Class Body Scanning Is Not String Aware"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED** — intentionally not fixed. Zero measured impact: the 97 lines carrying an unbalanced brace sit after the last test member in their class. A gate that needs a new build dependency (a lexer) to fix a problem that does not exist is a gate that gets removed the first time that dependency is inconvenient. The by-results check in `check-test-runs.py` catches the class anyway.

**Tracked as:** [#173](https://github.com/gazon1/sing/issues/173)

**Found in:** the gate audit in
`2026-10-05-gate-audit-text-shape-vs-fact`, while asking what input passes the
runnable-test predicate silently.

**Situation, measured rather than suspected:** the class-body scanner counts braces
line by line and is not string-aware, and **97 lines** in the current test tree carry
an unbalanced literal brace inside a string. So the trap is set on 97 lines. Comparing
the naive counter against a string-aware one across **all 269 real test classes**
produced **zero** differing verdicts, so nothing has fallen into it — the braces that
matter are balanced `${...}` templates, and the unbalanced ones sit after the last test
member in their class.

**Why it stays open rather than being fixed:** a real lexer for a defect with zero
measured impact, in a source set (`commonTest`) that has no parser dependency today. A
gate that needs a new build dependency is a gate that gets removed the first time that
dependency is inconvenient.

**Try next:** nothing, unless a test file puts a bare `}` in a literal *above* a test
member in its class. It is already covered one layer up — the by-results check in
`check-test-runs.py` reads the run rather than the source, so a class the predicate
mis-scopes and a genuinely untagged class produce the same symptom and are caught
either way. That is the argument for keeping the structural check above the text one,
not an argument that this one is fine forever.

---
