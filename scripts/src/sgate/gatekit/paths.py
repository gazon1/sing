"""sgate.gatekit.paths — where a gate is allowed to write.

A gate that generates a file has to know two things: that the file it writes
belongs to *this* checkout, and that the check compares against the same path
the write produces. Both were once left to arithmetic on `__file__`, and the
arithmetic was wrong once: a catalogue that counted its repository root one
level too high wrote to `../docs/tools/catalog.md` — a directory outside the
repository — while its `--check` compared against a path inside it and reported
the catalogue as current. The file outside the repository was the one that
changed; the file inside it never did.

So the rules are here, once:

  * a generated path is resolved against the root it belongs to, and it is
    checked to be inside it;
  * the path a check compares against and the path a write produces are the
    same value, computed by the same function.
"""
from __future__ import annotations

from pathlib import Path


def is_inside(root: Path, path: Path) -> bool:
    """Whether `path` is `root` itself or something under it.

    Both sides are resolved first: a path that has not been resolved still
    carries its `..` segments, and `..` is how a file leaves the repository.
    """
    resolved_root = root.resolve()
    resolved_path = path.resolve()
    if resolved_path == resolved_root:
        return True
    return resolved_root in resolved_path.parents


def assert_inside(root: Path, path: Path, what: str) -> Path:
    """The path, when it is inside the repository; a failure when it is not.

    A generator that writes outside the repository writes into somebody else's
    tree, and the check that certifies it reads a different file — so the gate
    is green and the file nobody looks at is the one that changed.
    """
    if not is_inside(root, path):
        raise ValueError(
            f"{what} is outside the repository: {path} is not under {root.resolve()}. "
            "A gate that writes outside the tree it certifies writes to nobody's file."
        )
    return path


def generated_path(root: Path, relative: str) -> Path:
    """The path of a generated file, checked to be inside the repository.

    One function, so the check and the write cannot disagree about where the
    file is: they call the same one.
    """
    return assert_inside(root, root / relative, f"the generated file '{relative}'")
