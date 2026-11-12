"""Unit tests for `scripts/src/sgate/gatekit/refs.py` — which ref is the base.

Run with: python3 -m unittest discover -s scripts/tests

Every tool that answers "what does this change touch?" needs a base, and the
lists of candidates those tools used drifted apart — a change measured against
`origin/main` in one tool and against `main` in another is measured against two
different things. This module holds the answer once, and these tests hold it
to the properties that make it an answer:

  - the ROLE comes first and the freshness second: the integration branch
    before the trunk, and within one role the remote's copy before the local
    branch;
  - a named base is EXCLUSIVE: a base the caller named and that is not here is
    no base, never a different ref;
  - no base is None, and None is never "HEAD";
  - a CHANGE is never measured against itself: the resolver that answers for a
    change steps over a candidate that points at HEAD.

The fixtures write their refs with `git update-ref origin/main HEAD`, which
lands in `.git/origin/main` — a place `for-each-ref` and `show-ref` cannot
see, and `rev-parse` resolves like any other name. A resolver that looked with
the wrong command would answer "no base" about a repository that has one.
"""

import contextlib
import importlib.util
import io
import pathlib
import subprocess
import tempfile
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_MODULE = _ROOT / "scripts" / "src" / "sgate" / "gatekit" / "refs.py"
_LOADER = _ROOT / "scripts" / "refs_loader.py"

_spec = importlib.util.spec_from_file_location("gatekit_refs", _MODULE)
_refs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_refs)

from git_sandbox import no_named_base  # noqa: E402

resolve_base_ref = _refs.resolve_base_ref
resolve_change_base_ref = _refs.resolve_change_base_ref
resolves = _refs.resolves
is_null_commit = _refs.is_null_commit
BASE_CANDIDATES = _refs.BASE_CANDIDATES
REMOTE_BASE_CANDIDATES = _refs.REMOTE_BASE_CANDIDATES
BASE_REF_ENV = _refs.BASE_REF_ENV
NULL_COMMIT = _refs.NULL_COMMIT


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

    def test_the_integration_branch_wins_over_a_fresher_trunk(self):
        """The role comes first and the freshness second.

        A checkout with `origin/main` and a local `dev` — and no `origin/dev`
        — has two answers, and only one of them can be the base. The
        integration branch wins: the remote's copy of the trunk is not a
        better integration branch than the local `dev`, it is a better COPY
        of the trunk.
        """
        self._branch("dev")
        self._ref("origin/main")
        self.assertEqual("dev", resolve_base_ref(cwd=self.tmp))


class TheChangeBaseTest(RefRepositoryTest):
    """The base a CHANGE is measured against is never the change itself.

    ## The defect this covers

    A push leaves the remote-tracking copy of the pushed branch on the commit
    that was just pushed. A push to `dev` therefore leaves `origin/dev` on
    `HEAD`, and a resolver that answers "the integration branch" measures
    that push against itself: the diff is empty, every gate that selects work
    by the changed files finds nothing to do, and the run is green because it
    measured nothing.

    The change resolver steps over a candidate that points at `HEAD` and
    takes the next one — the push to `dev` is measured against `origin/main`,
    which is what the integration branch is measured against.
    """

    def setUp(self) -> None:
        super().setUp()
        # A second commit, so that a ref can point at a commit that is NOT the
        # change itself. The first commit is now HEAD's parent.
        (self.tmp / "file.txt").write_text("second", encoding="utf-8")
        self._git("add", "-A")
        self._git("commit", "--quiet", "-m", "second")
        self.older = self._git("rev-parse", "HEAD~1")

    def test_a_push_to_the_integration_branch_is_measured_against_the_trunk(self):
        self._git("update-ref", "origin/dev", "HEAD")
        self._git("update-ref", "origin/main", self.older)
        self.assertEqual("origin/main", resolve_change_base_ref(cwd=self.tmp))

    def test_the_plain_resolver_answers_the_branch_the_push_went_to(self):
        """The two resolvers answer two different questions.

        The plain one answers "which ref is this checkout's trunk?" — and the
        trunk of a push to `dev` is `dev`. The change one answers "which ref
        is this change measured against?", and it refuses the branch the
        change was pushed to.
        """
        self._git("update-ref", "origin/dev", "HEAD")
        self._git("update-ref", "origin/main", self.older)
        self.assertEqual("origin/dev", resolve_base_ref(cwd=self.tmp))
        self.assertEqual("origin/main", resolve_change_base_ref(cwd=self.tmp))

    def test_a_named_base_is_taken_as_given(self):
        # The caller named it and the caller knows whether it is the change
        # itself: a name is an answer, not a guess.
        self._git("update-ref", "origin/dev", "HEAD")
        env = {BASE_REF_ENV: "origin/dev"}
        self.assertEqual("origin/dev", resolve_change_base_ref(cwd=self.tmp, env=env))

    def test_a_named_base_that_is_not_here_is_no_base(self):
        env = {BASE_REF_ENV: "origin/dev"}
        self.assertIsNone(resolve_change_base_ref(cwd=self.tmp, env=env))

    def test_the_null_commit_is_no_base(self):
        """The payload's way of saying "the branch did not exist before".

        A push that created the branch has no state to measure against, and
        the null commit names no commit at all — so it is no base. It is NOT
        `HEAD` and NOT the trunk: naming the trunk instead would name the
        pushed commit, and the change would be measured against itself.
        """
        env = {BASE_REF_ENV: NULL_COMMIT}
        self.assertIsNone(resolve_change_base_ref(cwd=self.tmp, env=env))
        self.assertIsNone(resolve_base_ref(cwd=self.tmp, env=env))

    def test_the_change_is_never_measured_against_itself(self):
        # The local `dev` is the change itself and the trunk is not: the
        # resolver steps over the branch the change was pushed to.
        self._git("branch", "dev")
        self._git("update-ref", "origin/main", self.older)
        self.assertEqual("origin/main", resolve_change_base_ref(cwd=self.tmp))

    def test_when_every_candidate_is_the_change_itself_the_first_one_answers(self):
        # There is nothing else to measure against: the change is empty, and
        # the caller says so rather than pretending it measured something.
        self._git("update-ref", "origin/dev", "HEAD")
        self.assertEqual("origin/dev", resolve_change_base_ref(cwd=self.tmp))


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


class TheNullCommitTest(RefRepositoryTest):
    """Git's null object id — the name of no commit at all.

    GitHub's push payload uses it for `github.event.before` when the pushed
    ref did not exist before the push. The name says "there was nothing there",
    and the resolver reads it as such: a base that names nothing is no base.
    """

    def test_the_null_commit_is_recognised(self):
        self.assertTrue(is_null_commit(NULL_COMMIT))
        self.assertTrue(is_null_commit(f"  {NULL_COMMIT}  "))
        self.assertTrue(is_null_commit(NULL_COMMIT.upper()))

    def test_a_real_commit_is_not_the_null_commit(self):
        self.assertFalse(is_null_commit(self._git("rev-parse", "HEAD")))
        self.assertFalse(is_null_commit("origin/main"))

    def test_the_null_commit_does_not_resolve_to_a_commit(self):
        """`git rev-parse` accepts the null id as a NAME and fails to peel it.

        That is why the resolver asks the question explicitly rather than
        letting git answer it: a resolver that read the name without the peel
        would resolve a base that does not exist.
        """
        self.assertFalse(resolves(NULL_COMMIT, cwd=self.tmp))


class TheCommandLineTest(RefRepositoryTest):
    """The loader is also a command, and the command is the same answer.

    A recipe outside the package — `just rumdl-changed`, a shell hook — needs
    a base and cannot import the package that owns the answer. The loader is
    the door: it prints the answer, and its exit code is the answer's.
    """

    def setUp(self) -> None:
        super().setUp()
        # A second commit, so that the trunk can point at a commit that is not
        # the change itself.
        (self.tmp / "file.txt").write_text("second", encoding="utf-8")
        self._git("add", "-A")
        self._git("commit", "--quiet", "-m", "second")
        self.older = self._git("rev-parse", "HEAD~1")
        self.loader = self._load_loader()
        self._original_root = self.loader.ROOT
        self.loader.ROOT = self.tmp
        self.addCleanup(self._restore_root)

    @staticmethod
    def _load_loader():
        spec = importlib.util.spec_from_file_location("refs_loader_cli", _LOADER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _restore_root(self) -> None:
        self.loader.ROOT = self._original_root

    def _run(self, argv: list[str]) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = self.loader.main(argv)
        return code, out.getvalue().strip() or err.getvalue().strip()

    def test_it_prints_the_base_the_repository_names(self):
        self._git("update-ref", "origin/main", self.older)
        code, printed = self._run([])
        self.assertEqual(0, code, printed)
        self.assertEqual("origin/main", printed)

    def test_it_fails_when_there_is_no_base(self):
        # A caller that cannot do without a base fails loudly rather than
        # guessing one.
        code, printed = self._run([])
        self.assertEqual(1, code, printed)
        self.assertIn("base", printed.lower())

    def test_the_upstream_is_a_different_question(self):
        # The upstream of a checkout is the remote-tracking trunk; the base of
        # a change may be a local branch. The command asks both, and the two
        # answers are not the same.
        self._git("branch", "main", self.older)
        code, printed = self._run([])
        self.assertEqual(0, code, printed)
        self.assertEqual("main", printed)
        code, printed = self._run(["--upstream"])
        self.assertEqual(1, code, printed)


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
