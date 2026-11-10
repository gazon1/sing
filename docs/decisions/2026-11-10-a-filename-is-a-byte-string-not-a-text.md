---
date: 2026-11-10
status: accepted
deciders: [maintainer]
---

# A filename is a byte string, not a text

## Context

The pre-push verdict and the module graph read the paths of a change out of
git. Both ask for them the same way: `core.quotePath=false` and the `-z`
format, so that a path with non-ASCII letters comes out as the tree names it
(`Café.kt`) and not as git quotes it (`Caf\303\251.kt`). That is settled:
`2026-11-09-the-push-scope-is-what-the-tree-carries.md` and
`2026-11-09-the-pre-push-hook-judges-the-push-not-the-repository.md`.

Reading the paths was the last thing done in text mode, and a filename is not
a text. Git stores a filename as bytes, and it accepts a filename whose bytes
are not valid UTF-8. A reader that decodes such a path strictly raises
`UnicodeDecodeError` — and the reader that raises takes the whole answer with
it. In the verdict that meant: a push carrying a file whose name is not valid
UTF-8 was stopped by a traceback, and the report said detekt had found
findings, which is not what had happened. In the module graph the same reader
meant the module plan could not be produced at all.

The text mode had a second, quieter effect: a subprocess in text mode
translates newlines, so a carriage return inside a filename came back as a
newline. That path was not unreadable — it was **different** from the path git
reported, and a path that differs from the tree's own naming is a path no
module claims.

Both were found by review, on a branch that had just fixed the escaping in the
same readers — the fix that removed git's quoting is what made the invalid
bytes reach the decoder at all.

## Idea

Read the bytes git wrote, and decode them with `surrogateescape`.

## Decision

The path readers read git's output **as bytes** and decode each path with
`errors="surrogateescape"`. The undecodable bytes become lone surrogates and
become the original bytes again on the way out, so the path survives the round
trip, still equals the path git reports, and can still be compared with it.

This holds for every git call whose output is a path:

- `scripts/pre-push-verdict.py` — `changed_paths()` (the diff), `git()` (the
  status), and the Kotlin file listing that feeds `git hash-object`;
- `scripts/changed-modules.py` — the diff and the status.

The `hash-object` call takes the paths **as bytes on stdin**, because a path
that holds a lone surrogate cannot be encoded back to UTF-8 for the subprocess.

Where output is only *displayed* — the diff the verdict prints when the tree
changed under it — it is decoded with `errors="replace"`, because a report
that cannot be printed is a report nobody sees.

The hook's own readers are shell, and they read bytes already: `read -r` over
a NUL-free newline stream keeps a byte string as a byte string. They are not
affected.

## Rationale

`surrogateescape` is the one codec error handler that is a **round trip**:
every byte sequence decodes to a string that encodes back to the same bytes.
That is what a path needs — the reader has to hand back the name the tree
actually has, not an approximation of it, because the reader's whole job is to
compare it with other names.

The alternatives do not survive contact with the case:

- **Decode with `errors="replace"`** turns the undecodable bytes into `?`.
  The path then differs from every other spelling of it, so it matches nothing
  — the same failure as the escaped form, only quieter: the change would be
  judged as carrying no Kotlin at all, with nothing raised.
- **Ignore the path** (skip a name that does not decode) drops a real change
  on the floor, and does it silently.
- **Refuse the push** is the honest reading of "I cannot read this", but it is
  the wrong answer: the path *can* be read, and the verdict has a question to
  answer about it. A push is stopped for a finding, not for a byte in a name.

The direction of the failure matters. The old behaviour failed **closed** — the
push stopped — so it was never a false green. It was still wrong: it stopped a
push for a reason that had nothing to do with the tree's code, reported it as
something it was not, and did so on the strength of a verdict that never ran.

## Consequences

- A filename that is not valid UTF-8 is read, named, and judged like any
  other. Eight regression tests cover it, across both readers: the path is
  read, the path survives the round trip byte for byte, the reader does not
  raise, and a carriage return is not turned into a newline.
- `scripts/changed-modules.py` had the same defect before this change, and
  this change fixes it there too. The two readers are one answer to one
  question — "which files does this change carry" — and a defect in one of
  them is a defect in the answer.
- The `str` a path is now carries may hold lone surrogates. Python's
  filesystem encoding uses `surrogateescape` on POSIX, so such a path still
  opens, stats and compares correctly. It is not encodable as plain UTF-8 —
  anything that *writes* such a path out as text has to say what it will do
  with it. The one place that writes paths out (the diff the verdict prints)
  replaces them.
- A newline inside a filename does not break the digest, and not because of
  this decision: git quotes a name that holds a newline (`"we\nird.kt"`) when
  it writes it, so the name still occupies one line, and
  `git hash-object --stdin-paths` unquotes what it reads. The round trip
  through git's own quoting is what makes the newline survive, and it is
  checked: the hash of a quoted path equals the hash of the file itself.

## Links

- `scripts/pre-push-verdict.py` — `PATH_DECODE`, `decode_path()`, `changed_paths()`.
- `scripts/changed-modules.py` — `PATH_DECODE`, `decode_path()`, `changed_paths()`.
- `scripts/tests/test_pre_push_verdict.py` — `PathDecodingTest`.
- `scripts/tests/test_changed_modules.py` — `ThePathsComeOutOfGitTest`.
- `docs/decisions/2026-11-09-the-push-scope-is-what-the-tree-carries.md`.
- `docs/decisions/2026-11-09-the-pre-push-hook-judges-the-push-not-the-repository.md`.
