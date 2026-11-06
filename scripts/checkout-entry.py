"""checkout-entry.py — the one place that binds a checkout and runs an entry point in it.

`./sg` and `./rm` are the two commands of the worktree loop, and both were the
same accident waiting to happen: the console scripts on `PATH` belong to an
editable install, and the install belongs to whichever checkout ran
`pip install -e scripts/src/` — or `setup-local-tools.sh` — last. A gate run or
a Maestro run that measures another tree is not a red run; it is a green run
about somebody else's code, and the only symptom is that its findings make no
sense.

Both wrappers therefore bind the packages to themselves before handing over to
an entry point, and each used to do it in its own file. Two copies of that rule
are two rules: one of them gets fixed and the other keeps measuring the wrong
tree. This module is the one owner.

What it owns:

  - **the binding** — load `scripts/package-binding.py` by path and bind the
    packages to this checkout: the editable finders go, this checkout's
    `scripts/src` goes first on `sys.path` and first in `PYTHONPATH`;
  - **the refusal** — when a package of the install does not resolve to this
    checkout, name it and stop. A run that measures the wrong tree is no run at
    all, so the wrapper refuses before anything is measured;
  - **the answer** to "which checkout is this?", taken from the *packages* and
    not from the wrapper's own path: the wrapper lives in one checkout by
    construction, so a wrapper that answered from its own path would look right
    even when the binding had failed;
  - **the hand-over** — set `sys.argv[0]` to the name the program is called in
    its usage line, import the entry point, and run it in this process, so the
    exit code and the streams stay the entry point's own.

**This module is loaded by path, not imported**, for the same reason
`scripts/package-binding.py` is: the packages may be the thing that is broken,
so the thing that repairs them cannot live inside them. Each wrapper keeps the
few lines that load it — a loader cannot load itself — and nothing beside them:
which entry point it serves, and where its `whereami` answer comes from.
"""

from __future__ import annotations

import importlib
import importlib.util
import os
import sys
import types

#: Where the binding lives, relative to the checkout. It is loaded by path for
#: the reason above, so this is a path and not a module name.
BINDING = ("scripts", "package-binding.py")


def _load_by_path(path: str, name: str) -> types.ModuleType:
    """The module at `path`, loaded without importing it.

    @param path the file to load.
    @param name the name the module is registered under while it is loaded.
    @return the loaded module.
    """
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def bind(root: str) -> types.ModuleType:
    """Bind the packages to the checkout that holds `root`.

    @param root the checkout the entry point belongs to.
    @return the binding, which can say where a package resolves and which
        packages do not resolve here.
    """
    binding = _load_by_path(os.path.join(root, *BINDING), "package_binding")
    binding.bind_local_package(root)
    return binding


def require_local(binding: types.ModuleType, root: str) -> None:
    """Refuse to run when a package of the install resolves to another checkout.

    A package that does not resolve at all counts as foreign: the alternative
    is that the import answers from wherever the environment says, which is the
    accident this module exists to prevent.

    @param binding the module returned by `bind`.
    @param root the checkout the entry point belongs to.
    """
    foreign = binding.foreign_packages(root)
    if not foreign:
        return
    sys.stderr.write(
        f"The packages of {root} do not resolve to this checkout: "
        f"{', '.join(foreign)}.\n"
        f"Install them from here: pip install -e {os.path.join(root, 'scripts', 'src')}\n"
    )
    raise SystemExit(2)


def whereami(binding: types.ModuleType, package: str) -> str:
    """The checkout `package` resolves to, as the *packages* say it.

    The package directory is `<checkout>/scripts/src/<package>`: one level up
    is the package root (`<checkout>/scripts/src`), one more is
    `<checkout>/scripts`, and one more is the checkout.

    @param binding the module returned by `bind`.
    @param package the package that answers for the entry point.
    @return the checkout's path.
    """
    origin = binding.package_origin(package)
    if origin is None:  # pragma: no cover - the refusal above catches this
        sys.stderr.write(f"The {package} package does not resolve anywhere.\n")
        raise SystemExit(2)
    return str(origin.parent.parent.parent)


def run_entry_point(
    program: str,
    module: str,
    *,
    arguments: list[str] | None = None,
    pass_arguments: bool = False,
) -> object:
    """Run `module`'s entry point in this process, as `program`.

    The module is imported by name *here*, after the binding, and never at this
    file's own import time: an import at the top would resolve through whatever
    the environment says, which is the thing being repaired.

    Exit codes are the caller's: this returns whatever `main` returned, and the
    wrapper raises `SystemExit` where the entry point's code is its own — or
    lets a `SystemExit` out of the entry point stand, as the gate CLI does.

    @param program the name the program is called in its usage line.
    @param module the module holding `main`.
    @param arguments the argument list; the current `sys.argv[1:]` when None.
    @param pass_arguments hand `main` the argument list instead of letting it
        read `sys.argv`.
    @return whatever the entry point's `main` returned.
    """
    if arguments is None:
        arguments = sys.argv[1:]
    sys.argv = [program, *arguments]
    main = importlib.import_module(module).main
    return main(sys.argv[1:]) if pass_arguments else main()
