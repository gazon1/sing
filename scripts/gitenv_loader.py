"""The one way to reach the git environment when the package is out of reach.

`sgate.gatekit.gitenv` owns the answer to "which git variables must a command
inside a tree not inherit". The gates import it as a package — but the package
pulls in `click` and `loguru` when it is imported, and a script that runs with
the ambient `python3` (the pre-push hook does) cannot count on either. So the
scripts that cannot reach the package load the same file by path, here.

The module is cached under a name of its own, so that every script that
reaches the answer this way holds the *same* function object, and not two
copies of the same file. The gates that import the package get the same
functions, because the file is the same file.

ADR `docs/decisions/2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md`.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys

#: The repository root — the parent of `scripts/`.
ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The name the module is cached under. It differs from the package path on
#: purpose: the package import and the by-path import are two ways of reaching
#: one file, and they must not fight over one entry of `sys.modules`.
GITENV_MODULE_NAME = "sgate_gatekit_gitenv"

#: Where the answer lives.
GITENV_PATH = ROOT / "scripts" / "src" / "sgate" / "gatekit" / "gitenv.py"


def load_gitenv():
    """The canonical git environment, loaded from its file."""
    module = sys.modules.get(GITENV_MODULE_NAME)
    if module is not None:
        return module
    spec = importlib.util.spec_from_file_location(GITENV_MODULE_NAME, GITENV_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load the git environment from {GITENV_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[GITENV_MODULE_NAME] = module
    spec.loader.exec_module(module)
    return module


#: The names git exports to a hook, as the owner states them.
INHERITED_FROM_HOOK = load_gitenv().INHERITED_FROM_HOOK

#: The environment a git command runs in when its caller is a hook.
tree_env = load_gitenv().tree_env
