"""package-binding.py — the one place that knows how to bind `sgate` to a checkout.

The package is installed editable, so the environment decides which checkout's
`scripts/src/sgate` answers `import sgate` — and a gate that measures another
tree is a green run about somebody else's code.

Three surfaces bind the package, and they used to bind it three ways:

  - the scripts that import it put their own tree's `scripts/src` at the front
    of `sys.path` (the idiom `check-script-conventions.py` checks, R2.5);
  - `scripts/ci/static-gates.sh` puts the same directory first in
    `PYTHONPATH` for the gates it dispatches;
  - the two entry points — `./sg` and `scripts/run-gate-tests.py` — additionally
    drop the editable finders from `sys.meta_path`.

The first two are the same move in two spellings. The third is the defensive
one: an editable install registers a meta-path finder, and while it is
*appended* a `sys.path` entry wins — but the order of `sys.meta_path` is the
installer's business, not ours, and a finder that came first would win over
`sys.path` without anybody noticing. The entry points therefore do not trust
it, and they share this module so that the trust has one owner.

**This module is loaded by path, not imported.** It cannot be imported: the
runner has to work when the package is missing — that is the case it refuses —
and it cannot live inside the package, because the package is what may be
missing. It sits next to the runner instead, and both entry points reach it
the same way: by path.
"""

from __future__ import annotations

import os
import pathlib
import sys


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
    """Bind `import sgate` to the checkout that holds `root`.

    Three moves, in this order:

    1. the editable finders go — they map the package to whichever checkout
       was installed last;
    2. this checkout's `scripts/src` goes first on `sys.path` — so the package
       that answers `import sgate` is the one on disk here;
    3. the same path goes first in `PYTHONPATH` — so the *children* this
       process spawns inherit the binding. Without the third move a subprocess
       resolves the package by itself, and by "itself" means "by whatever is
       installed".

    @param root the repository root.
    @return the directory the package is bound to.
    """
    source = (pathlib.Path(root) / "scripts" / "src").resolve()
    sys.meta_path = [finder for finder in sys.meta_path if not is_editable_finder(finder)]
    if str(source) not in sys.path:
        sys.path.insert(0, str(source))
    existing = [part for part in os.environ.get("PYTHONPATH", "").split(os.pathsep) if part]
    if str(source) not in existing:
        os.environ["PYTHONPATH"] = os.pathsep.join([str(source), *existing])
    return source
