"""The one way to reach the base refs when the package is out of reach.

`sgate.gatekit.refs` owns the answer to "which ref is this change measured
against". The gates import it as a package — but the package pulls in `click`
and `loguru` when it is imported, and a script that runs with the ambient
`python3` (the pre-push hook does) cannot count on either. So the scripts
that cannot reach the package load the same file by path, here.

The module is cached under a name of its own, so that every script that
reaches the answer this way holds the *same* module, and not two copies of
the same file. The gates that import the package get the same functions,
because the file is the same file.

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
REFS_MODULE_NAME = "sgate_gatekit_refs"

#: Where the answer lives.
REFS_PATH = ROOT / "scripts" / "src" / "sgate" / "gatekit" / "refs.py"


def load_refs():
    """The base refs, loaded from their file."""
    module = sys.modules.get(REFS_MODULE_NAME)
    if module is not None:
        return module
    spec = importlib.util.spec_from_file_location(REFS_MODULE_NAME, REFS_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load the base refs from {REFS_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[REFS_MODULE_NAME] = module
    spec.loader.exec_module(module)
    return module


#: The environment variable that names the base explicitly.
BASE_REF_ENV = load_refs().BASE_REF_ENV

#: What a tool says when no base ref resolves here.
NO_BASE_REF_MESSAGE = load_refs().NO_BASE_REF_MESSAGE

#: The remote-tracking refs the trunk is looked for under.
REMOTE_BASE_CANDIDATES = load_refs().REMOTE_BASE_CANDIDATES

#: The refs a change is measured against.
BASE_CANDIDATES = load_refs().BASE_CANDIDATES

#: The ref this change is measured against, or None when there is none.
resolve_base_ref = load_refs().resolve_base_ref

#: The ref THIS CHANGE is measured against, or None when there is none.
#:
#: The difference matters: this one steps over a candidate that points at the
#: change itself, so a push is never measured against the branch it was
#: pushed to.
resolve_change_base_ref = load_refs().resolve_change_base_ref
