---
title: A JVM names a file it cannot decode
date: 2026-11-10
status: accepted
deciders: [maintainer]
---

# A JVM names a file it cannot decode

## Context

Detekt runs on a JVM. A JVM reads a filename with the platform charset and
replaces every byte it cannot decode with U+FFFD. So the detekt log does not
name a file whose name is not valid UTF-8 by that name: it names it
`caf�.kt` where the tree carries `caf\udce9.kt`.

The verdict compares the paths in the log against the paths of the change. The
two readers had just been fixed to read git's output as bytes and decode with
`surrogateescape`, so the change's own paths came out as the tree names them.
The log's paths could not be, because the JVM had already thrown the bytes
away before the verdict saw them.

The comparison therefore never matched: a finding in a file the change touched
was classified as **inherited** — the trunk's failure, not the push's — and the
push was waved through. The finding was in the change, the change was stopped
for nothing, and the report said the trunk had it.

That is the wrong direction. Before the byte-string fix the reader *raised* on
such a path, and a raised reader took the whole verdict with it: the push
stopped. The fix made that case fail **open** instead of closed — a silent
green, produced by the tool that exists to prevent silent greens.

The same byte had a second effect. A path holding a lone surrogate cannot be
encoded for the terminal, so printing the report raised `UnicodeEncodeError`.
The traceback exited 1, and the caller read exit 1 as "detekt reported
findings": an accusation about a file nobody could name, made against the
developer's own change.

Both were found by review of the byte-string fix.

## Idea

Recognise the file by both of its names, and let the report be printed.

## Decision

The verdict recognises a file by the name the **tree** gives it and by the name
the **JVM** gives it.

`lossy_path()` renders a path the way a JVM does: the path's bytes, decoded
with `errors="replace"`, which is exactly the replacement the JVM performs.
`classify_findings()` matches a finding against the changed paths and against
their lossy renderings, so a finding in a file whose name the JVM mangled is
recognised as the change's own.

The log's own bytes are not the problem and are not touched: the log literally
contains U+FFFD characters, so no codec on the reading side can recover the
original bytes. The two names are the same file, and the verdict has to know
that.

The report is made printable once, centrally: `tolerant_stdio()` reconfigures
stdout and stderr with `errors="replace"` before anything is printed, so a
path that cannot be encoded is replaced rather than raising. This is the same
treatment `display_path()` already gave the diff, applied to the report as a
whole.

## Rationale

The finding has to be attributed to *somebody*. The alternative to
recognising the JVM's name is refusing to attribute it — and that is what the
verdict already does when it cannot compare at all, where a finding that
cannot be attributed counts as the change's own. The JVM's rendering is not a
different file; it is the same file under the only name the log has for it.

The rejected alternative is to make the verdict refuse to judge any change
that carries a filename the JVM cannot decode. That would be fail-closed, and
it would also mean a push is stopped for a byte in a name — which
`2026-11-10-a-filename-is-a-byte-string-not-a-text.md` explicitly rejected:
the path *can* be read, and the verdict has a question to answer about it.
Refusing there would undo that decision for the one case where the reader
cannot answer it, and would fail closed on a question that has an answer.

Reading the log with a different codec does not help and cannot help: the
bytes are gone. The substitution happened in the JVM that wrote the log.

Centralising the repair in `tolerant_stdio()` keeps it in one place. Patching
each print would leave the next print to reintroduce the same crash, and the
crash is expensive: an exit code of 1 that means "the report could not be
printed" is read as "detekt found findings".

## Consequences

- A finding in a file the change touched is the change's own finding, even
  when the JVM could not spell the file's name. The push is stopped for it,
  which is the correct direction: the branch that would have been waved
  through is now blocked, and the block names a file that can be opened.
- The report is printed even when a path in it cannot be encoded. A path that
  cannot be encoded is replaced with `?` instead of taking the whole report
  down with it.
- Two names now match one file. Both are derived from the same changed-path
  list, so the set of files the verdict judges is unchanged — only the set of
  spellings it recognises grows.
- The `surrogateescape` codec stays. `replace` would make the changed paths
  match the JVM's names by accident and match nothing else; recognising both
  names is the deliberate version of that.

## Links

- `scripts/pre-push-verdict.py` — `lossy_path()`, `classify_findings()`, `tolerant_stdio()`.
- `scripts/tests/test_pre_push_verdict.py` — `test_a_finding_in_a_file_a_jvm_names_differently_is_the_push_s_own`, `test_the_report_is_printed_even_when_a_path_cannot_be_encoded`.
- `docs/decisions/2026-11-10-a-filename-is-a-byte-string-not-a-text.md`.
