"""source_text — reading Kotlin source without a parser.

One character walk, and every operation this module offers is a projection of it.

## The model: regions

The walk classifies every character of the text as exactly one of three kinds:

- **code** — a character that can be a brace, and therefore can change the
  structure of the file.
- **comment** — the text of a line or block comment.
- **literal** — the *contents* of a string or character literal, and the
  delimiters of a string template (`${` and its matching `}`).

Everything else follows from that classification:

- `strip_comments(text)` blanks the comment characters and leaves the rest.
- `strip_code(text)` blanks the comment and literal characters and leaves the code.
- `matching_brace(text, open_index)` counts braces **in code only**, so a brace
  inside a string, inside a KDoc or inside a template's text does not move the
  structure of the file.

## Why this exists

The operations were previously hand-written, per script, and per script they were
wrong in a way that cost the repository a test class and a gate's eyes.

`scripts/src/scanners/test_classes.py` counted braces to find the end of a class
body, and its stripper understood a plain double-quoted string but not a raw string
and not a block comment. A class body that contained a raw string with a brace in
it, or a KDoc line with a brace in it, was measured to a wrong end. Five real test
classes were invisible to the test-class inventory because of it (ADR
`2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md`).

`scripts/check-log-interpolation-allowlist.py` scanned line by line, so a log call
whose message started on the next line, or whose call carried an argument
(`log.e(e) { … }`), was not seen at all: 28 of 74 interpolations.

The fix is not a parser. A parser is the more correct tool and the repository
declines it for a stated reason (`RunnableTestMember` in
`shared/src/jvmTest/kotlin/com/singularity/todo/arch/RunnableTestMember.kt`:
"commonTest has no parser dependency today, and a gate that needs a new build
dependency is a gate that gets removed the first time that dependency is
inconvenient"). The fix is one shared, honest character walk that knows the ways a
Kotlin file can hide code from a brace counter.

## The rules it follows

- **A raw string wins over a plain one.** The three-quote form is matched before
  the one-quote form, so a raw string is never cut short at its first inner quote.
- **An escape keeps the literal open.** `"a\"b"` ends at the second quote, not the
  first.
- **A template is code.** `"${run { 1 }}"` contains a brace that closes a real
  block, so the template's *body* is classified as code, while its `${` and `}` are
  literal text and do not count. A scanner that treated the whole string as text
  would lose two braces and end the enclosing block in the wrong place; one that
  treated the whole string as code would count the text around it.
- **An unterminated literal does not hang.** Every branch consumes at least one
  character, so an unbalanced quote costs a wrong answer, never a wedged gate.
- **Nothing is deleted.** Every removed character becomes a space. The result has
  the same length as the input, which is what lets a caller map an offset in the
  result back to the original.
"""

from __future__ import annotations

__all__ = [
    "block_body",
    "first_code_brace",
    "line_depths",
    "matching_brace",
    "strip_code",
    "strip_comments",
]

_RAW = '"""'

# Character kinds. The zero value is "code", so an untouched buffer is code.
_CODE = 0
_COMMENT = 1
_LITERAL = 2


def _blank(out: list[str], start: int, end: int) -> None:
    """Replace [start, end) with spaces, keeping newlines in place."""
    for i in range(start, min(end, len(out))):
        if out[i] != "\n":
            out[i] = " "


def _mark(kinds: bytearray, start: int, end: int, kind: int) -> None:
    """Classify [start, end) as `kind`."""
    if end > start:
        kinds[start:end] = bytes([kind]) * (end - start)


def _end_of_block_comment(text: str, start: int) -> int:
    """Index just past the closing marker of the block comment at `start`.

    Kotlin block comments **nest**: `/* a /* b */ c */` is one comment, and the
    outer one ends at the second `*/`. Getting this wrong is not a corner case
    — a KDoc in this repository quotes a code sample that contains a comment of
    its own:

        `onFailure { /* UI updates from the flow */ }`

    A scanner that ends the comment at the first `*/` then reads the rest of the
    KDoc as code, meets a quote character inside it, and believes a string
    literal has started — which swallows the class declaration that follows.
    """
    depth = 0
    i = start
    n = len(text)
    while i < n:
        if text.startswith("/*", i):
            depth += 1
            i += 2
            continue
        if text.startswith("*/", i):
            depth -= 1
            i += 2
            if depth == 0:
                return i
            continue
        i += 1
    return n


def _classify(text: str) -> bytearray:
    """Classify every character of `text` as code, comment or literal.

    The buffer is the length of the text and starts as code, so only the
    characters that are *not* code are written to.
    """
    kinds = bytearray(len(text))
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end == -1 else end
            _mark(kinds, i, end, _COMMENT)
            i = end
            continue
        if text.startswith("/*", i):
            end = _end_of_block_comment(text, i)
            _mark(kinds, i, end, _COMMENT)
            i = end
            continue
        if text.startswith(_RAW, i):
            i = _raw_string(text, i, kinds)
            continue
        if text[i] == '"':
            i = _plain_string(text, i, kinds)
            continue
        if text[i] == "'":
            i = _char_literal(text, i, kinds)
            continue
        i += 1
    return kinds


def _raw_string(text: str, start: int, kinds: bytearray) -> int:
    """Index just past the closing marker of the raw string at `start`.

    The awkward case is a run of four or more quotes. Kotlin's grammar allows a
    quote inside a raw string, so a run of N quotes at the end of a raw string
    means: the first N-3 are content, the last three close it. The repository
    contains one — a plain string literal, written as a raw string whose content
    starts and ends with a quote (see ArchitectureTest.kt, the `literal` regex).
    A scanner that takes the first three quotes of the trailing run as the closer
    leaves one quote dangling, and the next quote in the file opens a string that
    never closes.
    """
    n = len(text)
    i = start + 3
    content_start = i
    while i < n:
        if text.startswith("${", i):
            _mark(kinds, content_start, i, _LITERAL)
            _mark(kinds, i, i + 2, _LITERAL)
            i = _code(text, i + 2, kinds, in_template=True)
            content_start = i
            continue
        if text.startswith(_RAW, i):
            run = i
            while run < n and text[run] == '"':
                run += 1
            if run - i > 3:
                # More than three quotes: the last three of the run close the
                # string, the rest is content.
                _mark(kinds, content_start, run - 3, _LITERAL)
                return run
            _mark(kinds, content_start, i, _LITERAL)
            return i + 3
        i += 1
    _mark(kinds, content_start, n, _LITERAL)
    return n


def _plain_string(text: str, start: int, kinds: bytearray) -> int:
    """Index just past the closing quote of the string literal at `start`.

    The delimiters stay code and the content becomes literal text. A template
    inside the string is code, and is classified as such.
    """
    n = len(text)
    i = start + 1
    content_start = i
    while i < n:
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch == '"':
            _mark(kinds, content_start, i, _LITERAL)
            return i + 1
        if ch == "\n":
            # A plain string literal does not span lines in Kotlin. An
            # unterminated one ends the literal here rather than swallowing the
            # rest of the file.
            _mark(kinds, content_start, i, _LITERAL)
            return i
        if text.startswith("${", i):
            _mark(kinds, content_start, i, _LITERAL)
            _mark(kinds, i, i + 2, _LITERAL)
            i = _code(text, i + 2, kinds, in_template=True)
            content_start = i
            continue
        i += 1
    _mark(kinds, content_start, n, _LITERAL)
    return n


def _char_literal(text: str, start: int, kinds: bytearray) -> int:
    """Index just past the closing quote of the char literal at `start`."""
    n = len(text)
    i = start + 1
    content_start = i
    while i < n:
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch == "'":
            _mark(kinds, content_start, i, _LITERAL)
            return i + 1
        if ch == "\n":
            _mark(kinds, content_start, i, _LITERAL)
            return i
        i += 1
    _mark(kinds, content_start, n, _LITERAL)
    return n


def _code(text: str, start: int, kinds: bytearray, *, in_template: bool) -> int:
    """Scan code from `start` and return the index just past it.

    Inside a template the scan stops at the `}` that closes the template: the
    template's delimiters are literal text, and its body is code, so the braces
    in the body are counted and the closing `}` is not.
    """
    n = len(text)
    i = start
    depth = 0
    while i < n:
        ch = text[i]
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end == -1 else end
            _mark(kinds, i, end, _COMMENT)
            i = end
            continue
        if text.startswith("/*", i):
            end = _end_of_block_comment(text, i)
            _mark(kinds, i, end, _COMMENT)
            i = end
            continue
        if text.startswith(_RAW, i):
            i = _raw_string(text, i, kinds)
            continue
        if ch == '"':
            i = _plain_string(text, i, kinds)
            continue
        if ch == "'":
            i = _char_literal(text, i, kinds)
            continue
        if ch == "{":
            depth += 1
            i += 1
            continue
        if ch == "}":
            if in_template and depth == 0:
                _mark(kinds, i, i + 1, _LITERAL)
                return i + 1
            depth = max(depth - 1, 0)
            i += 1
            continue
        i += 1
    return i


def strip_comments(text: str) -> str:
    """Blank out comments; string literals keep their contents."""
    kinds = _classify(text)
    out = list(text)
    _blank_kind(out, kinds, _COMMENT)
    return "".join(out)


def strip_code(text: str) -> str:
    """Blank out comments and the contents of string and character literals."""
    kinds = _classify(text)
    out = list(text)
    _blank_kind(out, kinds, _COMMENT)
    _blank_kind(out, kinds, _LITERAL)
    return "".join(out)


def _blank_kind(out: list[str], kinds: bytearray, kind: int) -> None:
    """Blank every character of `out` whose kind is `kind`."""
    start = -1
    for i, k in enumerate(kinds):
        if k == kind:
            if start < 0:
                start = i
        elif start >= 0:
            _blank(out, start, i)
            start = -1
    if start >= 0:
        _blank(out, start, len(kinds))


def first_code_brace(text: str) -> int:
    """Index of the first `{` that is code, or -1 when there is none.

    "Code" is the point: a `{` in a string, in a KDoc or in a template's text
    does not open a block, and a caller that wants the first *block* must not
    stop at one.
    """
    kinds = _classify(text)
    for i, k in enumerate(kinds):
        if k == _CODE and text[i] == "{":
            return i
    return -1


def matching_brace(text: str, open_index: int) -> int:
    """Index of the `}` that closes the `{` at `open_index`, or -1.

    Only braces in code are counted. A brace inside a string, inside a comment or
    inside the text of a template is not a brace of the file, and a brace inside a
    template's *body* is — it opens and closes a real block.
    """
    kinds = _classify(text)
    depth = 0
    for i in range(open_index, len(text)):
        if kinds[i] != _CODE:
            continue
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def block_body(text: str) -> str | None:
    """The text inside the first code block, or None when it is never closed.

    The body is the text between the first `{` that is code and the `}` that
    closes it. The braces themselves are not part of it.
    """
    open_index = first_code_brace(text)
    if open_index < 0:
        return None
    close_index = matching_brace(text, open_index)
    if close_index < 0:
        return None
    return text[open_index + 1 : close_index]


def line_depths(text: str) -> list[int]:
    """The brace depth at the end of each line, one entry per line.

    Only code braces are counted, so a line that holds a string with a brace in
    it does not change the depth of the lines that follow it.
    """
    kinds = _classify(text)
    depths = []
    depth = 0
    for i, ch in enumerate(text):
        if kinds[i] == _CODE:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
        if ch == "\n":
            depths.append(depth)
    if not text.endswith("\n"):
        depths.append(depth)
    return depths
