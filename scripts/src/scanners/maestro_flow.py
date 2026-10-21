"""maestro_flow — one reader for the `tags:` block of a Maestro flow.

A Maestro flow is a header block, a `---`, and a list of commands. The tags
that say which suite a flow belongs to live in a `tags:` block, and three
modules in this repository used to read that block three ways:

  * `emulator.maestro._flow.flow_tags` — the runner. Scans the whole file,
    document by document, and is what `run-maestro` actually filters on.
  * `traceability.links.scan_maestro` — the whole file, but a tags block that
    was never closed stayed open, so a later `- something` line was read as
    a tag.
  * `traceability.maestro_suggest._header_tags` — stopped at the first `---`,
    so a flow whose tags block sits in a later document was invisible to it.

The reader below is the runner's, with the line number each tag was declared
on kept alongside it. The runner is the authority: a tag it cannot see is a
tag it cannot filter on, so any other reader that disagrees with it is wrong
about a fact the runner acts on.

The one thing the reader does *not* do is parse YAML. A flow file is not
valid YAML as a whole — its commands are a list of maps whose keys repeat —
and loading it would parse a document this module has no opinion about.
"""
from __future__ import annotations

__all__ = ["tag_entries", "flow_tags_text"]


def tag_entries(text: str) -> list[tuple[int, str]]:
    """Every tag a flow declares, as (line number, tag) pairs.

    The line number is 1-based, as an editor and a stack trace both count.
    The tag is the value as declared: surrounding quotes and a trailing `#`
    comment are removed, because neither is part of the tag.
    """
    entries: list[tuple[int, str]] = []
    in_tags = False
    for line_no, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("---"):
            # A document separator ends the block it interrupts. A `tags:`
            # block in the next document is read on its own terms.
            in_tags = False
            continue
        if stripped.startswith("tags:"):
            in_tags = True
            continue
        if not in_tags:
            continue
        if stripped.startswith("- "):
            value = stripped[1:].strip()
            # A YAML comment may follow the value on the same line. It is
            # removed before the quotes are, because a quoted value followed
            # by a comment ends in a quote that is not the closing one — and
            # stripping quotes first leaves it behind as part of the tag.
            value = value.split("#")[0].strip()
            value = value.strip("'\"").strip()
            if value:
                entries.append((line_no, value))
            continue
        if stripped == "" or stripped.startswith("#"):
            # A blank line and a comment do not close the block.
            continue
        # Anything else closes it: a tags block is a list, and the line that
        # ends it is the line that is not one.
        in_tags = False
    return entries


def flow_tags_text(text: str) -> list[str]:
    """Every tag a flow declares, in order, without line numbers."""
    return [tag for _, tag in tag_entries(text)]
