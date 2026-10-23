"""The git environment a test's own git must run in.

Git exports `GIT_DIR` (and `GIT_WORK_TREE`, `GIT_INDEX_FILE`,
`GIT_COMMON_DIR`) to its hooks — and a hook runs this test suite. A `git
config` inside a test then writes to THAT repository instead of the test's
own temporary one, and the damage lands in the checkout the developer is
using: a replaced `core.sshCommand`, a lost `core.hooksPath`, a rewritten
`remote.origin`.

Measured on 2026-10-23: with `GIT_DIR` exported, one run of this suite wrote
four values into the shared repository's config — the SSH command, the hooks
path, the user identity and the remote URL. The checkout then refused to
commit ("this operation must be run in a work tree"), the keepalive was gone,
and the push had nowhere to go.

So the exported variables are removed for the duration of the test. Two ways
to do that, and the second is the default:

  - `GitEnvTestCase` — a base class that strips them in `setUp` and restores
    them in `tearDown`. Every git call in the test then sees a clean
    environment, whichever way it is invoked;
  - `clean_git_env()` — the environment to pass explicitly to a subprocess
    that must run git outside the test's process environment.
"""

from __future__ import annotations

import os
import unittest

#: The variables git exports to a hook, and that make a git command operate
#: on a repository other than the one in the current directory.
GIT_ENVIRONMENT = (
    'GIT_DIR',
    'GIT_WORK_TREE',
    'GIT_INDEX_FILE',
    'GIT_COMMON_DIR',
    'GIT_OBJECT_DIRECTORY',
    'GIT_ALTERNATE_OBJECT_DIRECTORIES',
    'GIT_QUARANTINE_PATH',
)


def clean_git_env() -> dict[str, str]:
    """The current environment, without the variables git exports to a hook."""
    env = dict(os.environ)
    for name in GIT_ENVIRONMENT:
        env.pop(name, None)
    return env


def strip_git_environment() -> dict[str, str]:
    """Remove the exported variables from this process; return what was removed."""
    saved: dict[str, str] = {}
    for name in GIT_ENVIRONMENT:
        if name in os.environ:
            saved[name] = os.environ.pop(name)
    return saved


def restore_git_environment(saved: dict[str, str]) -> None:
    """Put the exported variables back."""
    for name in GIT_ENVIRONMENT:
        os.environ.pop(name, None)
    os.environ.update(saved)


class GitEnvTestCase(unittest.TestCase):
    """A test case whose git calls cannot reach the repository they run in.

    The variables are removed from `os.environ` — not merely passed around —
    because the calls that do the damage are the ones that do not think about
    the environment at all: `subprocess.run(['git', 'config', ...], cwd=tmp)`.

    A subclass that defines its own `setUp` does not have to call `super()`:
    the base class wraps whatever the subclass defines, so the environment is
    clean before the subclass's own setup runs and is restored afterwards.
    """

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        original_setup = cls.__dict__.get('setUp')
        original_teardown = cls.__dict__.get('tearDown')

        def setUp(self: GitEnvTestCase, *args: object, **kw: object) -> None:
            self._saved_git_env = strip_git_environment()
            if original_setup is not None:
                original_setup(self, *args, **kw)

        def tearDown(self: GitEnvTestCase, *args: object, **kw: object) -> None:
            try:
                if original_teardown is not None:
                    original_teardown(self, *args, **kw)
            finally:
                restore_git_environment(getattr(self, '_saved_git_env', {}))

        cls.setUp = setUp  # type: ignore[method-assign]
        cls.tearDown = tearDown  # type: ignore[method-assign]
