---
title: "One JUnit XML reader, and the reporters are only renderings of it"
date: 2026-11-06
status: accepted
tags: ["tooling", "testing", "ci"]
---

# One JUnit XML reader, and the reporters are only renderings of it

## Context

Two reporters read the same JUnit XML and disagreed about what it said.

`scripts/junit-annotations.py` (the GitHub annotation emitter, run in CI) and
`scripts/ci/print-test-failures.sh` (the console reporter, run by `hk`) each
carried their own XML parsing. The audit that prompted this decision found
three concrete divergences:

- the annotation emitter checked `isinstance(result, junitparser.Failure)`, and
  `junitparser.Error` is **not** a subclass of `junitparser.Failure` — so a
  testcase that ended in `<error>` was counted as a clean pass and produced no
  annotation at all;
- the console reporter read `failure|error` correctly, which made the two
  reporters disagree on the same file;
- both truncated the failure message, and the annotation emitter additionally
  dropped the `<flakyFailure>` child on sight, so a retried test kept only its
  **terminal** failure.

Two supporting facts made the divergence survivable rather than obvious. The
annotation emitter depended on `junitparser`, which was **not** declared in
`scripts/src/pyproject.toml` — CI installed it ad hoc, so the script was inert
outside CI and could not be exercised locally. And the message truncation was
justified by a comment that pointed at the Kiwi TCMS `TEXT` column — a consumer
that no longer exists. The truncation destroyed the only copy of the traceback
in the name of a retired integration.

The repository already had a reader that got the semantics right:
`scripts/src/traceability/junit_xml.py`, used by the traceability matrix. It
encodes the precedence `failure > error > skipped > passed` and preserves the
`file` attribute on a testcase.

## Idea

- One reader owns the parse. The reporters become two renderings of one result.
- `<flakyFailure>` is retry **history**, not an outcome: a testcase that has
  both a `<flakyFailure>` and a terminal `<failure>` failed, and the terminal
  failure is what it failed with.
- The reader offers a strict variant for the reporters, because "this evidence
  is incomplete" and "there was nothing to report" are different answers: a
  sync pipeline should skip a broken artefact, a reporter should say so.
- The truncation bound stays, but it bounds the **summary line** only. The stack
  trace is evidence and is carried in full.

## Decision

`scripts/src/traceability/junit_xml.py` is the canonical reader.

- `TestCaseResult` gained `flaky_attempts` and `stacktrace`; `retried` reports
  whether a testcase carries retry history.
- `parse_junit_file_strict()` raises `ET.ParseError` on a broken document;
  `parse_junit_file()` keeps the tolerant behaviour and delegates to it.
- `MESSAGE_LIMIT` (2000) now bounds only the message summary, and the stale
  Kiwi rationale is gone. The stack trace is a separate field, never truncated.
- `scripts/junit-annotations.py` reads through the shared reader, emits
  `::error` for both failures and errors, emits `::notice` for a retry-pass,
  counts clean passes separately, and turns a parse error into an `::warning`.
- `scripts/ci/print-test-failures.sh` calls the same reader and prints the
  message, the full stack trace and the retry note.
- `scripts/measure-flake-rate.py` counts flaky failures through the reader
  instead of its own ET parser.
- `junitparser` is gone: the ad-hoc install in CI, the preflight check and the
  skill note that named it are all removed. It could not have replaced the
  reader anyway — it does not expose the `file` attribute the traceability
  matrix needs (that evaluation had already happened; it is recorded in the
  reader's docstring).

`scripts/tests/test_junit_reporting.py` pins the behaviour: an `<error>`
testcase is a failure, a retried failure reports its terminal failure, the two
reporters agree on the same file, and the truncation does not touch the stack
trace.

## Rationale

**One parse, one answer.** The defect was not that the reporters existed but
that they each decided what a failure was. Two renderings of one result cannot
disagree about the result; two parsers can, and did.

**The reader already encoded the hard part.** The precedence
`failure > error > skipped > passed` and the `file` attribute were written for
the traceability matrix and are exactly what a failure reporter needs. Writing
a second one is how the two drifted apart.

**A strict reader beside a tolerant one.** The sync pipeline reads artefacts
that may be truncated or half-written; skipping one is correct there. A
reporter that silently skips a broken file reports a green run over an
incomplete picture. One reader, two entry points, no second parser.

**`<flakyFailure>` is not an outcome.** JUnit's rerun model writes the earlier
attempt as `<flakyFailure>` and the last attempt as `<failure>`. A testcase
with both failed; reading only the first attempt, as the annotation emitter
did, reported the failure of an attempt that later passed and dropped the one
that did not.

**The annotation body stays short, the report does not.** A GitHub annotation
is a pointer — 500 characters is a summary, not a transcript. The transcript
lives in the console reporter, in the uploaded XML and in the Allure report.

**Dropping `junitparser` removes a way for the script to be inert.** A
dependency that is installed only in CI is a dependency that behaves
differently in CI and on a developer machine. The standard library's
`xml.etree` needs nothing.

## Consequences

- The annotation emitter and the console reporter cannot disagree about which
  tests failed.
- The CI pipeline installs one Python package less, and the preflight check
  for it is gone.
- The reader is now load-bearing for three consumers, so its behaviour is
  pinned by tests next to it (`scripts/tests/test_junit_reporting.py`).
- The full traceback reaches the console reporter and the uploaded XML; the
  2000-character bound applies to the summary line only.
- A JUnit XML that cannot be parsed is now visible: the reporter says so
  instead of counting nothing as success.

## Links

- `scripts/src/traceability/junit_xml.py`, `scripts/junit-annotations.py`,
  `scripts/ci/print-test-failures.sh`, `scripts/measure-flake-rate.py`
- `scripts/tests/test_junit_reporting.py`, `scripts/tests/test_junit_xml.py`
- `docs/engineering/test-reporting-audit.md` — the audit that found the
  divergence (findings F1–F3)
