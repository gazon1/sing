"""Which ref is a change measured against.

Every tool that answers "what does this change touch?" needs a base: the ref
the change is compared with. The tools used to answer that question on their
own, each with its own list of candidates, and the lists drifted — a change
measured against `origin/main` in one tool and against `main` in another is
measured against two different things.

This module holds the answer once.

The base
--------
The trunk is the branch the work is based on. With a protected integration
branch that is `dev` for ordinary work and `main` for a release; a caller that
knows which one it wants says so through `SGATE_BASE_REF`, and the chain
below is the answer for everybody else.

Two chains, and the difference matters
--------------------------------------
`REMOTE_BASE_CANDIDATES` holds the **remote-tracking** copies only. A tool that
asks "what is this checkout carrying, compared with the trunk it is based on?"
uses it, because a local branch is not an upstream: the local `main` of a
checkout that has never fetched is the checkout's own branch, and measuring a
checkout against itself answers nothing.

`BASE_CANDIDATES` holds the same refs plus the local branches. A tool that asks
"what does this change touch?" uses it, because a clone without a remote still
has a branch, and a change measured against nothing is measured against
everything.

The order
---------
The ROLE comes first and the freshness second. `dev` comes before `main`
whatever else is true, because the trunk a change is based on is the
integration branch while it exists and the release branch after it. Within one
role the remote-tracking copy comes before the local branch: the remote's copy
is the branch as the world sees it, and the local branch is the branch as this
checkout last had it.

The two rules can disagree — a checkout with `origin/main` and a local `dev`
has both, and only one of them can be the answer. The role wins: `dev` is the
integration branch, and a change based on the integration branch is measured
against it. The remote's copy of `main` is not a better integration branch
than the local `dev`; it is a better *copy of the trunk*.

The change and the base
-----------------------
Two questions, two answers, and confusing them is how a change ends up
measured against itself:

  * "which ref is this checkout's trunk?" — `resolve_base_ref`. A tool that
    asks about the upstream of a checkout asks this.
  * "which ref is THIS CHANGE measured against?" — `resolve_change_base_ref`.
    A tool that asks what a change touches asks this.

The second refuses a base that points at the change itself. A push to `dev`
leaves `origin/dev` on the commit that was just pushed, so a chain that
answers "the integration branch" would measure that push against itself and
call the result "no change". The change resolver steps over a candidate that
points at `HEAD` and takes the next one.

The fallback
------------
A repository that has no `dev` branch yet must still measure its changes
against something. `origin/main` is in the chain for exactly that reason, and
the chain is a fallback rather than a rule: `SGATE_BASE_REF` names the base
when the caller knows better.

An explicit name is exclusive: when `SGATE_BASE_REF` names a ref that is not
here, there is no base. Answering with a different ref would be answering a
question nobody asked, and a tool that measures against a base the caller did
not name measures against the wrong thing without saying so.

ADR `docs/decisions/2026-11-11-the-change-is-measured-against-the-integration-branch.md`.
"""

from __future__ import annotations

import importlib.util
import os
import pathlib
import subprocess
import sys

#: The git environment owner, loaded by path.
#:
#: `sgate.gatekit.gitenv` is the owner of the answer "which variables must a git
#: command inside a tree not inherit", and this module needs that answer. It
#: loads the owner BY PATH rather than importing it, because this file is
#: itself loaded by path (`scripts/refs_loader.py`) in the places where the
#: package is out of reach — and importing `sgate.gatekit.gitenv` from there
#: would import the package, whose `__init__` pulls in `click` and `loguru`.
#: The file next to this one is the owner's file, whatever route reaches it.
#:
#: The module name is the one `scripts/gitenv_loader.py` uses, so the two
#: routes share ONE module rather than holding two copies of one file.
GITENV_MODULE_NAME = "sgate_gatekit_gitenv"
GITENV_PATH = pathlib.Path(__file__).resolve().parent / "gitenv.py"


def _gitenv():
    """The owner of the git environment, loaded from its file."""
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


def _controlled_env(env=None) -> dict[str, str]:
    """The environment a git command of this module runs in.

    Git exports `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and `GIT_COMMON_DIR`
    to the hooks it runs, and they name the tree the hook runs in — they win
    over the directory a git command is pointed at. A `git rev-parse` that
    inherits them resolves the ref in the HOOK's tree, not in the tree the
    caller asked about, and the answer is about a repository nobody named.

    So the environment is built here, whatever the caller passed: the caller's
    environment is honoured except for the variables git exports to a hook, and
    those are removed. A caller that already scrubbed them is unaffected — the
    removal of a name that is not there changes nothing.
    """
    gitenv = _gitenv()
    controlled = gitenv.tree_env() if env is None else dict(env)
    for name in gitenv.INHERITED_FROM_HOOK:
        controlled.pop(name, None)
    return controlled

#: The environment variable that names the base explicitly.
#:
#: The owner of the name is `sgate.gatekit.gitenv`: that module owns the
#: question "which names must not cross into another tree", and this name is
#: one of them — it names a ref in the tree the caller is in.
BASE_REF_ENV = _gitenv().BASE_REF_ENV

#: What a tool says when no base ref resolves here.
#:
#: A change measured against a base nobody named is a change measured against
#: the wrong thing without saying so, so a tool that has no base says this
#: instead of quietly picking one.
NO_BASE_REF_MESSAGE = (
    "no base ref could be resolved here: neither the integration branch nor the "
    f"trunk resolves in this checkout. Fetch one, or name it with {BASE_REF_ENV}, "
    "or pass --base."
)

#: The remote-tracking refs the trunk is looked for under, in order.
#:
#: The remote's copy of the trunk, and only that: a local branch is not an
#: upstream, and a checkout measured against its own branch answers nothing.
REMOTE_BASE_CANDIDATES = (
    "origin/dev",
    "origin/main",
)

#: The refs a change is measured against, in order.
#:
#: The role comes first: the integration branch, then the trunk. Within a
#: role the remote-tracking copy comes before the local branch — the remote's
#: copy is the branch as the world sees it.
#:
#: The short names are enough: git resolves `origin/dev` to the remote-tracking
#: ref and `dev` to the local branch, so the fully qualified spellings would
#: never be reached.
BASE_CANDIDATES = (
    "origin/dev",
    "dev",
    "origin/main",
    "main",
)


def commit_of(ref: str, cwd=None, env=None) -> str | None:
    """The commit `ref` names, or None when it names none.

    The ref is read the way git reads it: `git rev-parse --verify --quiet
    <ref>^{commit}`. Not `for-each-ref`, not `show-ref` — those do not see a
    ref written by `git update-ref origin/main HEAD`, which lands in
    `.git/origin/main` and is invisible to them, while `rev-parse` resolves it
    like any other name. The test fixtures build their upstream that way.

    The command runs in the environment this module builds, so it answers
    about the tree it was pointed at and not about the tree a hook happens to
    run in.
    """
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"],
        cwd=cwd,
        env=_controlled_env(env),
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip() or None


def resolves(ref: str, cwd=None, env=None) -> bool:
    """Whether `ref` names a commit in this repository."""
    return commit_of(ref, cwd=cwd, env=env) is not None


def resolve_base_ref(cwd=None, env=None, candidates=None) -> str | None:
    """The ref this change is measured against, or None when there is none.

    *cwd* is the tree the ref is looked for in; *env* is the environment the
    git command runs in — a command that runs inside a hook passes the scrubbed
    environment, so that git answers about the repository it discovers rather
    than about the one the hook exports.

    *candidates* is the chain to answer from. It defaults to `BASE_CANDIDATES`
    — the refs a change is measured against — and a tool that needs the
    remote-tracking copies only passes `REMOTE_BASE_CANDIDATES`.

    None means there is no base: no candidate resolved, or the explicit name
    did not resolve. None is not "measure against HEAD" — a tool that has no
    base says so, and a tool that must measure against something measures
    against everything.

    This answers "which ref is this checkout's trunk?". A tool that asks what
    a CHANGE touches wants `resolve_change_base_ref` instead: it steps over a
    candidate that points at the change itself.
    """
    effective = os.environ if env is None else env
    explicit = effective.get(BASE_REF_ENV, "").strip()
    if explicit:
        # An explicit name is exclusive: a base the caller named and that is
        # not here is not a base, and the chain is not the answer to it.
        return explicit if resolves(explicit, cwd=cwd, env=env) else None

    for candidate in BASE_CANDIDATES if candidates is None else candidates:
        if resolves(candidate, cwd=cwd, env=env):
            return candidate
    return None


def resolve_change_base_ref(cwd=None, env=None, candidates=None) -> str | None:
    """The ref THIS CHANGE is measured against, or None when there is none.

    The difference from `resolve_base_ref` is one rule: a base that points at
    the change itself is not a base.

    A push leaves the remote-tracking copy of the pushed branch on the commit
    that was just pushed. A push to `dev` therefore leaves `origin/dev` on
    `HEAD`, and a chain that answers "the integration branch" measures that
    push against itself — the diff is empty, every gate that selects work by
    the changed files finds nothing to do, and the run is green because it
    measured nothing. That is the vacuous comparison this resolver exists to
    prevent.

    So the chain steps over a candidate that points at `HEAD` and takes the
    next one: a push to `dev` is measured against `origin/main`, which is what
    the integration branch is measured against. When every candidate points at
    `HEAD` there is nothing else to measure against, and the first of them is
    the answer — the change is empty, and the caller says so rather than
    pretending it measured something.

    A NAMED base is exclusive and is taken as given: the caller named it, and
    the caller knows whether it is the change itself. A name that does not
    resolve is no base at all — never a different ref, and never `HEAD`.

    *candidates* is the chain to answer from; it defaults to
    `BASE_CANDIDATES`.
    """
    effective = os.environ if env is None else env
    explicit = effective.get(BASE_REF_ENV, "").strip()
    if explicit:
        return explicit if resolves(explicit, cwd=cwd, env=env) else None

    head = commit_of("HEAD", cwd=cwd, env=env)
    change_itself = None
    for candidate in BASE_CANDIDATES if candidates is None else candidates:
        commit = commit_of(candidate, cwd=cwd, env=env)
        if commit is None:
            continue
        if head is None or commit != head:
            return candidate
        if change_itself is None:
            change_itself = candidate
    return change_itself


__all__ = [
    "BASE_CANDIDATES",
    "BASE_REF_ENV",
    "NO_BASE_REF_MESSAGE",
    "REMOTE_BASE_CANDIDATES",
    "commit_of",
    "resolve_base_ref",
    "resolve_change_base_ref",
    "resolves",
]
