"""Unit tests for `scripts/src/sgate/gatekit/refs.py` — which ref is the base.

Run with: python3 -m unittest discover -s scripts/tests

Every tool that answers "what does this change touch?" needs a base, and the
lists of candidates those tools used drifted apart — a change measured against
`origin/main` in one tool and against `main` in another is measured against two
different things. This module holds the answer once, and these tests hold it
to the three properties that make it an answer:

  - the integration branch comes before the trunk, and the remote's copy comes
    before the local branch;
  - a named base is EXCLUSIVE: a base the caller named and that is not here is
    no base, never a different ref;
  - no base is None, and None is never "HEAD".

The fixtures write their refs with `git update-ref origin/main HEAD`, which
lands in `.git/origin/main` — a place `for-each-ref` and `show-ref` cannot
see, and `rev-parse` resolves like any other name. A resolver that looked with
the wrong command would answer "no base" about a repository that has one.
"""

import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_MODULE = _ROOT / "scripts" / "src" / "sgate" / "gatekit" / "refs.py"

_spec = importlib.util.spec_from_file_location("gatekit_refs", _MODULE)
_refs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_refs)

from git_sandbox import no_named_base  # noqa: E402

resolve_base_ref = _refs.resolve_base_ref
resolves = _refs.resolves
BASE_CANDIDATES = _refs.BASE_CANDIDATES
REMOTE_BASE_CANDIDATES = _refs.REMOTE_BASE_CANDIDATES
BASE_REF_ENV = _refs.BASE_REF_ENV


class RefRepositoryTest(unittest.TestCase):
    """A repository built for the resolver."""

    def setUp(self) -> None:
        # The fixture's repository is its own repository: a base named in the
        # environment is about the tree the test runs in, and it is not about
        # this one. The tests that want a named base pass one themselves.
        self.enterContext(no_named_base())
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="gatekit-refs-"))
        self.addCleanup(self._remove)
        # The branch is NOT `main`: a local `main` is one of the candidates,
        # and a fixture that had one by default could not tell "the chain
        # found a local branch" from "the chain found nothing".
        self._git("init", "--quiet", "-b", "work")
        self._git("config", "user.email", "refs@example.com")
        self._git("config", "user.name", "Refs")
        (self.tmp / "file.txt").write_text("first", encoding="utf-8")
        self._git("add", "-A")
        self._git("commit", "--quiet", "-m", "first")

    def _remove(self) -> None:
        import shutil

        shutil.rmtree(self.tmp, ignore_errors=True)

    def _git(self, *args: str) -> str:
        result = subprocess.run(
            ["git", *args],
            cwd=self.tmp,
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip()

    def _ref(self, name: str) -> None:
        """A ref that stands for a branch."""
        self._git("update-ref", name, "HEAD")

    def _branch(self, name: str) -> None:
        self._git("branch", name)


class TheChainTest(RefRepositoryTest):
    """Which candidate wins."""

    def test_the_integration_branch_comes_before_the_trunk(self):
        self._ref("origin/dev")
        self._ref("origin/main")
        self.assertEqual("origin/dev", resolve_base_ref(cwd=self.tmp))

    def test_the_trunk_is_the_base_when_there_is_no_integration_branch(self):
        self._ref("origin/main")
        self.assertEqual("origin/main", resolve_base_ref(cwd=self.tmp))

    def test_the_remote_copy_comes_before_the_local_branch(self):
        self._ref("origin/main")
        self._branch("main")
        self.assertEqual("origin/main", resolve_base_ref(cwd=self.tmp))

    def test_a_local_branch_is_a_base_when_there_is_no_remote(self):
        # A clone with no remote at all still has a branch, and a change
        # measured against nothing is measured against everything.
        self._branch("main")
        self.assertEqual("main", resolve_base_ref(cwd=self.tmp))

    def test_no_base_at_all_is_none(self):
        self.assertIsNone(resolve_base_ref(cwd=self.tmp))

    def test_the_remote_chain_does_not_accept_a_local_branch(self):
        # The remote-tracking chain answers "what is this checkout carrying,
        # compared with the trunk it is based on?" — and a local branch is not
        # an upstream.
        self._branch("main")
        self.assertIsNone(resolve_base_ref(cwd=self.tmp, candidates=REMOTE_BASE_CANDIDATES))

    def test_the_remote_chain_asks_for_the_integration_branch_first(self):
        self._ref("origin/dev")
        self._ref("origin/main")
        self.assertEqual("origin/dev", resolve_base_ref(cwd=self.tmp, candidates=REMOTE_BASE_CANDIDATES))


class TheNamedBaseTest(RefRepositoryTest):
    """What `SGATE_BASE_REF` does."""

    def test_a_named_base_that_is_here_is_the_base(self):
        self._ref("origin/main")
        env = {BASE_REF_ENV: "origin/main"}
        self.assertEqual("origin/main", resolve_base_ref(cwd=self.tmp, env=env))

    def test_a_named_base_that_is_not_here_is_no_base(self):
        # Exclusive: answering with a different ref would be answering a
        # question nobody asked.
        self._ref("origin/main")
        env = {BASE_REF_ENV: "origin/dev"}
        self.assertIsNone(resolve_base_ref(cwd=self.tmp, env=env))

    def test_a_named_base_beats_the_chain(self):
        self._ref("origin/dev")
        self._ref("origin/main")
        env = {BASE_REF_ENV: "origin/main"}
        self.assertEqual("origin/main", resolve_base_ref(cwd=self.tmp, env=env))

    def test_an_empty_name_is_not_a_name(self):
        self._ref("origin/main")
        env = {BASE_REF_ENV: "   "}
        self.assertEqual("origin/main", resolve_base_ref(cwd=self.tmp, env=env))


class TheResolverTest(RefRepositoryTest):
    """How a ref is looked for."""

    def test_a_ref_written_by_update_ref_resolves(self):
        # `git update-ref origin/main HEAD` lands in `.git/origin/main`, which
        # `for-each-ref` and `show-ref` cannot see. The resolver has to look
        # the way git looks.
        self._ref("origin/main")
        self.assertTrue(resolves("origin/main", cwd=self.tmp))

    def test_a_ref_that_is_not_there_does_not_resolve(self):
        self.assertFalse(resolves("origin/main", cwd=self.tmp))


class TheChainIsReachableTest(unittest.TestCase):
    """The chain holds names that can answer."""

    def test_the_chain_holds_no_fully_qualified_name(self):
        # Git resolves `origin/dev` to the remote-tracking ref and `dev` to the
        # local branch, so a fully qualified spelling in the chain could never
        # be reached: the short name above it answers first, and the chain
        # stops there. Such a name is dead weight, and it reads like a fourth
        # way of naming the trunk.
        for candidate in BASE_CANDIDATES:
            self.assertFalse(
                candidate.startswith("refs/"),
                f"{candidate!r} is unreachable: the short name above it answers first",
            )


class TheToolsAskTheOwnerTest(unittest.TestCase):
    """No tool answers the question on its own."""

    #: The scripts that ask "what does this change touch?".
    TOOLS = (
        "git_changed.py",
        "lint-changed.py",
        "compile-changed.py",
        "changed-modules.py",
        "test-impact-map.py",
        "check-baseline-ratchet.py",
    )

    def _scripts(self) -> list[pathlib.Path]:
        scripts = _ROOT / "scripts"
        return sorted(path for path in scripts.rglob("*.py") if "tests" not in path.parts)

    def test_no_tool_falls_back_to_a_base_of_its_own(self):
        # A tool that answers "no base" with `origin/main` is answering a
        # question nobody asked: the owner already tried `origin/main` and
        # did not find it, and a named base that is not here means there is no
        # base at all.
        for script in self._scripts():
            text = script.read_text(encoding="utf-8")
            for spelling in ('or "origin/main"', "or 'origin/main'", 'or `origin/main`'):
                self.assertNotIn(
                    spelling,
                    text,
                    f"{script.name} carries a base of its own: {spelling}",
                )

    def test_no_tool_reads_a_base_variable_of_its_own(self):
        # `LINT_BASE` and `COMPILE_CHANGED_BASE` were the two ways to name a
        # base before the owner existed. They read before the owner does, so a
        # named base was not the last word — and the owner was not the owner.
        for script in self._scripts():
            text = script.read_text(encoding="utf-8")
            for name in ("LINT_BASE", "COMPILE_CHANGED_BASE"):
                self.assertNotIn(name, text, f"{script.name} reads {name}")


if __name__ == "__main__":
    unittest.main()
