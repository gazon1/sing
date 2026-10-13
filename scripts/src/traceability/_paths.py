"""traceability._paths — shared path helpers for the traceability package."""
from __future__ import annotations

from pathlib import Path

from traceability import REPO_ROOT


def rel_to_repo(path: Path, root: Path = REPO_ROOT) -> str:
    """Return ``path`` relative to ``root`` as a POSIX string.

    Falls back to the absolute path (as ``Path.as_posix()``) when ``path``
    is not inside ``root`` — a ValueError from ``relative_to`` means the path
    is on a different drive or outside the repo entirely.
    """
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()
