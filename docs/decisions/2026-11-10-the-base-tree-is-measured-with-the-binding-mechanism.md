---
title: The base tree is measured with the binding mechanism
date: 2026-11-10
status: accepted
deciders: [maintainer]
---

# The base tree is measured with the binding mechanism

## Context

The pre-push ratchet re-runs the failing gate-suite tests on the base tree: a
test that fails on the base as well is the trunk's failure, and the push is
not blocked on it. That re-run is a **measurement of the base tree**, and it
decides whether a push is allowed to go out.

The re-run set `PYTHONPATH` to the base tree's `scripts/src` and ran
`python -m unittest` there. That is not enough, and the repository already
says why: the packages under `scripts/src` are installed editable, so the
environment decides which checkout answers `import sgate`.

Two forms of that install are in reach:

- the modern one registers a meta-path finder, which setuptools *appends*, so
  a `sys.path` entry normally wins;
- the legacy one ships an `easy-install.pth` that puts its checkout at the
  **front of `sys.path`** — in front of `PYTHONPATH`, in front of everything.

So with a foreign editable install in reach, the base re-run imported
*another checkout's* `sgate`. A failure it reported there was read as "the
base tree fails this test as well", the push's own failure was classified as
pre-existing, and the push was waved through: a false green, produced by the
very tool that exists to prevent false greens.

The answer was already in the repository: `scripts/package-binding.py` binds
the packages to a checkout, and `scripts/run-gate-tests.py` refuses to run
when the environment serves a foreign package. The ratchet bypassed both.

## Idea

Bind the base run the way the gate suite binds itself, and refuse to compare
when the base tree cannot be bound.

## Decision

The base run starts with a bootstrap that loads `scripts/package-binding.py`
**by path** and calls `bind_local_package()` on the base tree before any test
runs. The bootstrap then checks that the packages the base tree carries
resolve to the base tree, and only then runs `unittest` from there.

The binding happens **inside the child**, not in the parent. The path is fixed
before the interpreter runs the first line of the program: a `PYTHONPATH` the
parent sets is already too late, because the foreign path is in front of it
by then.

`bind_local_package()` now puts the checkout's `scripts/src` **first** on
`sys.path` and first in `PYTHONPATH`, instead of adding it only when it is
not already there. "On the path" is not "first on the path": with the legacy
`easy-install.pth` in reach, the foreign checkout is first, and a binding
that only adds a path binds nothing. The one place that knew about the
binding is now the one place that makes it stick.

A base tree that cannot be bound — one that carries no packages of its own,
or one whose package resolves elsewhere — is **not measured at all**. The
ratchet exits 2, the comparison cannot be made, and the caller stops the
push.

## Rationale

A comparison that could not be made is not a comparison that found nothing.
Every other branch of this tool already says so — a gate that failed without
printing anything, a report that names no failing test — and a base tree that
cannot be bound is the same case: the measurement never happened, so it
cannot say what the base tree has.

The alternative — a binding that trusts `PYTHONPATH` — was not "good enough
in practice". It was measurably wrong: with a legacy editable install in
reach, the base run measured another checkout, and the review that found it
reproduced it. The contamination is not theoretical on a developer machine:
the packages of this repository are installed editable, and an editable
install of another checkout of the same repository is one `pip install -e`
away.

Binding inside the child, with the repository's own mechanism, is also the
smaller change. A second, weaker binding in the ratchet would be the thing
the review called "a second, incomplete version of the mechanism" — and the
incomplete version is what failed.

The refusal to compare is not a new failure mode either: the ratchet already
exits 2 when it cannot compare, and the hook already treats that as "the
push is not certified". What is new is one more reason for the comparison to
be impossible, and it is the honest one.

## Consequences

- The base run measures the base tree, whatever the environment serves. A
  regression test builds a foreign editable install of another checkout — the
  legacy `easy-install.pth` form, in a user site of its own — and holds that
  the base run answers with the base tree's own package.
- `bind_local_package()` binds harder than before, for everyone who calls it:
  the gate suite and the entry points get the same protection, and they had
  the same hole.
- A base tree from before the packages existed cannot be compared at all, so
  the ratchet refuses rather than measuring the environment. Such a base has
  no gate-suite tests either, so nothing is lost.
- The bootstrap is a string in `scripts/pre-push-ratchet.py`. It is short
  because the work is in `scripts/package-binding.py`; what is left is
  "load it, bind the base tree, check, run the tests".

## Links

- `scripts/pre-push-ratchet.py` — `BIND_BOOTSTRAP`, `BINDING_PATH`, `ComparisonUnavailable`, `run_tests_on_base()`.
- `scripts/package-binding.py` — `bind_local_package()`, `provided_packages()`.
- `scripts/tests/test_pre_push_ratchet.py` — `BaseTreeBindingTest`.
- `scripts/tests/test_package_binding.py` — `test_binding_puts_this_checkout_first_even_when_it_is_already_on_the_path`.
- `docs/decisions/2026-11-10-a-filename-is-a-byte-string-not-a-text.md`.
