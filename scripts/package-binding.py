"""package-binding.py — the one place that knows how to bind the packages to a checkout.

The packages are installed editable, so the environment decides which checkout's
`scripts/src` answers `import sgate` — and a gate that measures another tree is
a green run about somebody else's code. The same accident is available to every
other package of the install: `emulator` (the Maestro runner and the emulator
starter), `scanners` and `traceability` all sit next to `sgate` under
`scripts/src/`, so they are all installed from the same place and all resolve to
whichever checkout was installed last.

Three surfaces bind the package, and they used to bind it three ways:

  - the scripts that import it put their own tree's `scripts/src` at the front
    of `sys.path` (the idiom `check-script-conventions.py` checks, R2.5);
  - `scripts/ci/static-gates.sh` puts the same directory first in
    `PYTHONPATH` for the gates it dispatches;
  - the entry points — `./sg` and `./rm` through `scripts/checkout-entry.py`,
    and `scripts/run-gate-tests.py` by itself — additionally drop the editable
    finders from `sys.meta_path`.

The first two are the same move in two spellings. The third is the defensive
one: an editable install registers a meta-path finder, and while it is
*appended* a `sys.path` entry wins — but the order of `sys.meta_path` is the
installer's business, not ours, and a finder that came first would win over
`sys.path` without anybody noticing. The entry points therefore do not trust
it, and they share this module so that the trust has one owner.

The older form of an editable install is worse than a finder: its
`easy-install.pth` puts the checkout at the **front of `sys.path`**, in front
of `PYTHONPATH`, so nothing on `PYTHONPATH` wins over it unless it comes
first. That is why `bind_local_package` puts this checkout's `scripts/src`
first rather than merely onto the path.

**This module is loaded by path, not imported.** It cannot be imported: the
runner has to work when the package is missing — that is the case it refuses —
and it cannot live inside the package, because the package is what may be
missing. It sits next to the runner instead, and every entry point reaches it
the same way: by path.
"""

from __future__ import annotations

import importlib.util
import os
import pathlib
import sys

#: The packages the editable install carries. They are siblings under
#: `scripts/src/`, so one binding owns all of them — `scripts/src/pyproject.toml`
#: declares them together, and an install that brought one of them brought the
#: others from the same checkout.
PACKAGES = ("emulator", "scanners", "sgate", "traceability")


def is_editable_finder(finder: object) -> bool:
    """Whether a meta-path finder came from an editable install.

    An editable install registers a finder that maps `sgate` to the checkout it
    was installed from. Setuptools appends it to `sys.meta_path`, so a
    `sys.path` entry wins while it is there — but the plain `sgate` on PATH
    has no such entry, and the import resolves to the foreign clone. The
    wrapper drops the finder and puts this checkout's `scripts/src` first, so
    the answer does not depend on the setuptools version that installed it.
    """
    name = getattr(finder, "__module__", "") or ""
    if "editable" in name.lower():
        return True
    return "editable" in type(finder).__module__.lower()


def bind_local_package(root: pathlib.Path | str) -> pathlib.Path:
    """Bind `import sgate` — and every sibling package — to the checkout that holds `root`.

    Three moves, in this order:

    1. the editable finders go — they map the packages to whichever checkout
       was installed last;
    2. this checkout's `scripts/src` goes first on `sys.path` — so the package
       that answers `import sgate` is the one on disk here;
    3. the same path goes first in `PYTHONPATH` — so the *children* this
       process spawns inherit the binding. Without the third move a subprocess
       resolves the package by itself, and by "itself" means "by whatever is
       installed".

    "First" is the whole of it. A path that is *on* `sys.path` but not first
    loses: the legacy `easy-install.pth` of an editable install puts its
    checkout at the front of `sys.path`, in front of `PYTHONPATH`, and a
    binding that only adds a path that is already there binds nothing. So the
    source is put first, and put there again if something else moved it.

    @param root the repository root.
    @return the directory the packages are bound to.
    """
    source = (pathlib.Path(root) / "scripts" / "src").resolve()
    sys.meta_path = [finder for finder in sys.meta_path if not is_editable_finder(finder)]
    while str(source) in sys.path:
        sys.path.remove(str(source))
    sys.path.insert(0, str(source))
    existing = [
        part for part in os.environ.get("PYTHONPATH", "").split(os.pathsep) if part and part != str(source)
    ]
    os.environ["PYTHONPATH"] = os.pathsep.join([str(source), *existing])
    return source


def package_origin(package: str) -> pathlib.Path | None:
    """Where a package resolves from right now, or `None` when it does not resolve.

    A package that has no `__init__.py` is a *namespace package*: it has no
    origin, but it has a place it was found in, and that place answers the
    question the same way.

    @param package the package name.
    @return the directory the package resolves to, or `None`.
    """
    try:
        spec = importlib.util.find_spec(package)
    except (ImportError, ValueError):
        return None
    if spec is None:
        return None
    if spec.origin:
        return pathlib.Path(spec.origin).resolve().parent
    locations = list(spec.submodule_search_locations or [])
    if not locations:
        return None
    return pathlib.Path(locations[0]).resolve()


def foreign_packages(root: pathlib.Path | str) -> list[str]:
    """The packages that do not resolve to the checkout that holds `root`.

    A package that does not resolve at all counts as foreign: the alternative
    is that the import answers from wherever the environment says, which is the
    accident this module exists to prevent.

    @param root the repository root.
    @return the names of the packages that are not this checkout's.
    """
    source = (pathlib.Path(root) / "scripts" / "src").resolve()
    foreign: list[str] = []
    for name in PACKAGES:
        origin = package_origin(name)
        if origin is None or source not in origin.parents:
            foreign.append(name)
    return foreign


def provided_packages(root: pathlib.Path | str) -> list[str]:
    """The packages the checkout that holds `root` carries on disk.

    A package the checkout does not have cannot be bound to it, and a package
    that does not resolve at all is not evidence of a foreign install — it is
    a package this tree has never heard of. The two are told apart here, so
    that a caller can ask "does the package that answers `import sgate` come
    from this checkout?" without being answered "no" about packages that are
    not in the picture at all.

    @param root the repository root.
    @return the names of the packages this checkout carries.
    """
    source = (pathlib.Path(root) / "scripts" / "src").resolve()
    return [name for name in PACKAGES if (source / name).is_dir()]
