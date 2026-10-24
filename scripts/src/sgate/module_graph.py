"""The module graph — ``scripts/changed-modules.py`` — loaded by path.

The graph is the single source of truth for which modules exist and which of
them have a detekt verdict. Gates that need that answer used to load the file
themselves, and each loader was a place where the answer could differ: the
script is not an installed package, it lives in the checkout under a name that
does not begin with an underscore, and importing it by module name would bind
to whichever tree the interpreter happened to see first.

One loader, used by every gate:

* :func:`load_module_graph` — the loaded module, or ``None`` when the file is
  missing or cannot be loaded.
* :func:`detekt_tasks` — the detekt task names (``:module:detekt``) the graph
  reports, which is exactly what ``changed-modules.py --detekt-tasks`` prints
  and exactly what CI judges.

A gate that wants the list asks the graph, not a hand-written copy of it.
"""

from __future__ import annotations

import importlib.util
import pathlib

#: The name the graph is loaded under. It is not a package name: the file is
#: loaded from a path, so the name only has to be unique inside this process.
_GRAPH_MODULE_NAME = 'changed_modules'


def load_module_graph(root: pathlib.Path):
    """Load ``scripts/changed-modules.py`` from ``root``, or return ``None``.

    ``None`` means "the graph is not available here" — a checkout without the
    script, or one where loading it failed. Callers decide what that means;
    a gate that cannot read the graph must not silently conclude that a
    module has no checks.
    """
    path = pathlib.Path(root) / 'scripts' / 'changed-modules.py'
    if not path.is_file():
        return None
    spec = importlib.util.spec_from_file_location(_GRAPH_MODULE_NAME, path)
    if spec is None or spec.loader is None:
        return None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def detekt_tasks(root: pathlib.Path) -> set[str]:
    """The detekt tasks the graph reports, as Gradle task names.

    This is the same list ``changed-modules.py --detekt-tasks`` prints: the
    modules that have a detekt verdict, read from the build rather than from a
    list somebody wrote out by hand.
    """
    module = load_module_graph(root)
    if module is None:
        return set()
    tasks = getattr(module, 'TASKS', None)
    if tasks is None:
        return set()
    return {f':{name}:detekt' for name in tasks.detekt_modules()}


__all__ = ['detekt_tasks', 'load_module_graph']
