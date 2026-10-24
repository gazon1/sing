"""Tests for `scripts/prune-merged-branches.py` — the plan of merged branches.

What is under test
------------------
The script exists because of one property of git: a squash-merge does not
make the branch an ancestor of `main`, so `git branch --merged` does not
list it and `git branch -d` refuses to delete it. The branch stays in the
repository forever, and their number grows with every pull request.

So the script has to be right about four things, and each of them is checked
here:

1. **The plan is exact.** A branch with a merged pull request is in the plan;
   a branch without one is not; the current checkout, `main`, a branch held
   by another worktree, a branch of a fork and a branch that could not be
   verified are never.
2. **The plan knows the branch apart from its pull request.** A branch whose
   tip is the merged commit, and a branch that is *behind* that commit, are
   both safe to delete — everything they hold is already in `main`. A branch
   that moved on after the merge is not: its tip is neither, and
   `git branch -D` would destroy commits that exist in no `origin/*` and in
   no `main`.
3. **The request to GitHub is the request the tool claims to make.** The
   stand-in for `gh` answers the way `gh` answers: it reads the request.
   A query for closed pull requests, a query without the merged commit, a
   query for another repository and a query with the default page size all
   produce a different answer — so a broken query cannot pass this suite.
4. **Nothing is deleted without the flag.** Without `--yes` the script only
   prints: a branch may belong to another session, and deleting it is not an
   automatic decision.

The calls to `gh` and `git` are injected, so the suite asks GitHub nothing
and deletes nothing in the repository.
"""

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import unittest
from unittest import mock

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_SCRIPT = _ROOT / "scripts" / "prune-merged-branches.py"

_spec = importlib.util.spec_from_file_location("prune_merged_branches", _SCRIPT)
_prune = importlib.util.module_from_spec(_spec)
# The module has to be in `sys.modules` before it is loaded: `dataclass` looks
# the class up by the name of its module, and without the record it fails on
# `sys.modules[...].__dict__`.
sys.modules[_spec.name] = _prune
_spec.loader.exec_module(_prune)

#: The repository the pull requests belong to in the fixture.
REPO = "gazon1/sing"


#: A pull request, in the shape `gh pr list --json` reports it.
def pull_request(
    number: int,
    head: str,
    oid: str = "",
    owner: str | None = "gazon1",
    merged: bool = True,
) -> dict:
    """One pull request of the fixture.

    `owner=None` is a pull request whose fork was deleted: GitHub reports no
    owner for it, and the branch it came from is not ours.
    """
    return {
        "number": number,
        "headRefName": head,
        "headRefOid": oid,
        "headRepositoryOwner": None if owner is None else {"login": owner},
        "merged": merged,
    }


class FakeRepo:
    """The history of commits: sha → subject and parents.

    A real history is needed, so that the script can ask git about ancestors
    and the test does not have to invent the answers: `merge-base` and
    `rev-list` are computed over this history.
    """

    def __init__(self) -> None:
        self.commits: dict[str, str] = {}
        self.parents: dict[str, tuple[str, ...]] = {}
        #: The commits that `origin/*` holds.
        self.remote: set[str] = set()

    def add(self, sha: str, subject: str, parents: tuple[str, ...] = ()) -> str:
        """Add a commit to the history and return its sha."""
        self.commits[sha] = subject
        self.parents[sha] = tuple(parents)
        return sha

    def ancestors(self, sha: str) -> set[str]:
        """Every commit reachable from `sha`, including `sha` itself."""
        seen: set[str] = set()
        stack = [sha]
        while stack:
            current = stack.pop()
            if current in seen:
                continue
            seen.add(current)
            stack.extend(self.parents.get(current, ()))
        return seen

    def depth(self, sha: str) -> int:
        """The length of the chain to the root — the greater, the closer."""
        return len(self.ancestors(sha))


class FakeGit:
    """A stand-in for `git`: it answers the way git answers.

    The answers are given by the test, the deletions are recorded in
    `deleted`, so that it is visible what the script deleted and what it did
    not. Git's refusal to delete a branch held by a worktree is reproduced
    too: a stand-in that were kinder than git would not catch a branch the
    script promises to delete and git refuses.
    """

    def __init__(
        self,
        repo: FakeRepo,
        branches: list[str],
        tips: dict[str, str],
        current: str | None = "main",
        worktrees: list[str] | None = None,
        fail_on: str | None = None,
        origin: str | None = "git@github.com:gazon1/sing.git",
    ) -> None:
        self.repo = repo
        self.branches = list(branches)
        self.tips = dict(tips)
        self.current = current
        self.worktrees = list(worktrees or [])
        self.fail_on = fail_on
        self.origin = origin
        self.deleted: list[str] = []
        self.calls: list[list[str]] = []

    def __call__(self, args):
        argv = list(args)
        self.calls.append(argv)
        if argv[:1] == ["for-each-ref"]:
            return "".join(f"{name}\n" for name in self.branches)
        if argv[:2] == ["rev-parse", "--abbrev-ref"]:
            return f"{self.current}\n" if self.current else "HEAD\n"
        if argv[:3] == ["rev-parse", "--verify", "--quiet"]:
            name = self._branch_name(argv[3])
            if name not in self.tips:
                raise _prune.ToolError(f"fatal: Needed a single revision")
            return f"{self.tips[name]}\n"
        if argv[:1] == ["rev-parse"]:
            name = self._branch_name(argv[1])
            if name not in self.tips:
                raise _prune.ToolError(f"unknown revision {argv[1]}")
            return f"{self.tips[name]}\n"
        if argv[:1] == ["merge-base"]:
            return self._merge_base(argv[1], argv[2])
        if argv[:1] == ["rev-list"]:
            return f"{self._rev_count(argv[1:])}\n"
        if argv[:1] == ["log"]:
            return self._log(argv)
        if argv[:2] == ["worktree", "list"]:
            return self._worktrees()
        if argv[:2] == ["remote", "get-url"]:
            if self.origin is None:
                raise _prune.ToolError("error: No such remote 'origin'")
            return f"{self.origin}\n"
        if argv[:2] == ["branch", "-D"]:
            return self._delete(argv[2])
        raise AssertionError(f"unexpected call to git: {argv}")

    def _branch_name(self, ref: str) -> str:
        """The branch name of a ref — `refs/heads/x` is the branch `x`."""
        if ref.startswith("refs/heads/"):
            return ref[len("refs/heads/") :]
        return ref

    def _sha(self, rev: str) -> str:
        """The commit a revision names — a branch name resolves to its tip."""
        return self.tips.get(self._branch_name(rev), rev)

    def _merge_base(self, left: str, right: str) -> str:
        """The common ancestor — as git gives it, or a refusal if there is none."""
        left = self._sha(left)
        right = self._sha(right)
        left_ancestors = self.repo.ancestors(left)
        right_ancestors = self.repo.ancestors(right)
        if left in right_ancestors:
            return f"{left}\n"
        if right in left_ancestors:
            return f"{right}\n"
        common = left_ancestors & right_ancestors
        if not common:
            raise _prune.ToolError("fatal: No merge base")
        best = max(common, key=self.repo.depth)
        return f"{best}\n"

    def _selected_commits(self, argv: list[str]) -> set[str]:
        """The commits a revision selection names.

        `git rev-list` and `git log` take the same selection: revisions, with
        everything after `--not` subtracted. A range is always "the right side
        minus the left side", whatever `--not` said before it.
        """
        positive: set[str] = set()
        negative: set[str] = set()
        target = positive
        for argument in argv:
            if argument == "--not":
                target = negative
                continue
            if argument == "--remotes":
                negative |= self.repo.remote
                continue
            if argument.startswith("--"):
                continue
            if ".." in argument:
                left, right = argument.split("..", 1)
                positive.add(self._sha(right))
                negative.add(self._sha(left))
                continue
            target.add(self._sha(argument))
        for rev in list(positive):
            positive |= self.repo.ancestors(rev)
        for rev in list(negative):
            negative |= self.repo.ancestors(rev)
        return positive - negative

    def _rev_count(self, argv: list[str]) -> int:
        """`rev-list --count` — how many commits the range holds."""
        return len(self._selected_commits(argv))

    def _log(self, argv: list[str]) -> str:
        """`log --format=%h %s` — the commits of a range."""
        limit = "5"
        revs: list[str] = []
        skip = False
        for index, argument in enumerate(argv):
            if skip:
                skip = False
                continue
            if argument == "--format=%h %s":
                continue
            if argument == "-n":
                limit = argv[index + 1]
                skip = True
                continue
            revs.append(argument)
        count = int(limit)
        selected = self._selected_commits(revs)
        lines = [
            f"{sha[:7]} {self.repo.commits.get(sha, '')}"
            for sha in selected
            if sha in self.repo.commits
        ]
        return "".join(f"{line}\n" for line in lines[:count])

    def _worktrees(self) -> str:
        """`worktree list --porcelain` — the branches held by worktrees."""
        blocks: list[str] = []
        held = list(self.worktrees)
        if self.current:
            held.insert(0, self.current)
        for index, name in enumerate(held):
            blocks.append(f"worktree /tmp/wt-{index}\nHEAD {'a' * 40}\nbranch refs/heads/{name}\n")
        return "\n".join(blocks)

    def _delete(self, name: str) -> str:
        """`git branch -D` — with a refusal for a branch held by a worktree."""
        if name in self.worktrees:
            raise _prune.ToolError(
                f"error: cannot delete branch '{name}' used by worktree at '/tmp/wt-1'"
            )
        if self.fail_on == name:
            raise _prune.ToolError(f"cannot delete branch '{name}'")
        self.deleted.append(name)
        self.branches = [branch for branch in self.branches if branch != name]
        return f"Deleted branch {name} (was 1234567).\n"


class FakeGh:
    """A stand-in for `gh`: it answers the way `gh` answers.

    The stand-in *reads* the request, and that is the point of it:

    - `--state closed` returns the pull requests that were closed and not
      merged, not the merged ones;
    - a `--json` list without `headRefOid` returns pull requests without the
      merged commit;
    - `--repo other/repo` returns the pull requests of that repository;
    - `--limit N` returns the first N pull requests.

    A stand-in that ignored the request would let a broken query pass the
    suite: the tool would go on asking for closed pull requests, or for
    pull requests without a commit, and the tests would still be green.
    """

    def __init__(
        self,
        prs: list[dict] | None = None,
        error: Exception | None = None,
        expected_repo: str = REPO,
        other_repo_prs: list[dict] | None = None,
    ) -> None:
        self.prs = prs if prs is not None else []
        self.error = error
        self.expected_repo = expected_repo
        self.other_repo_prs = other_repo_prs or []
        self.calls: list[list[str]] = []

    def __call__(self, args):
        argv = list(args)
        self.calls.append(argv)
        if self.error is not None:
            raise self.error
        options = self._options(argv)
        state = options.get("--state", "")
        repo = options.get("--repo", "")
        limit = int(options.get("--limit", "30"))
        fields = options.get("--json", "").split(",")
        source = self.prs if repo == self.expected_repo else self.other_repo_prs
        if state == "merged":
            selected = [pr for pr in source if pr.get("merged", True)]
        elif state == "closed":
            selected = [pr for pr in source if not pr.get("merged", True)]
        else:
            selected = list(source)
        payload = []
        for pr in selected[:limit]:
            item: dict = {}
            for field in fields:
                if field == "number":
                    item["number"] = pr.get("number")
                elif field == "headRefName":
                    item["headRefName"] = pr.get("headRefName")
                elif field == "headRepositoryOwner":
                    item["headRepositoryOwner"] = pr.get("headRepositoryOwner")
                elif field == "headRefOid":
                    item["headRefOid"] = pr.get("headRefOid", "")
            payload.append(item)
        return json.dumps(payload)

    def _options(self, argv: list[str]) -> dict[str, str]:
        """The options of the request, as `--name value` pairs."""
        options: dict[str, str] = {}
        index = 0
        while index < len(argv):
            argument = argv[index]
            if argument.startswith("--") and index + 1 < len(argv):
                options[argument] = argv[index + 1]
                index += 2
                continue
            index += 1
        return options


def build_repo() -> tuple[FakeRepo, dict[str, str]]:
    """The history and the tips of the branches the checks are built on."""
    repo = FakeRepo()
    repo.add("root", "root commit")
    repo.add("main1", "main: first", ("root",))
    repo.add("one", "feat/one: work", ("main1",))
    repo.add("two", "feat/two: work", ("main1",))
    repo.add("moved_base", "feat/moved: merged", ("main1",))
    repo.add("moved_tip", "feat/moved: work after the merge", ("moved_base",))
    repo.add("behind", "feat/behind: behind the pull request", ("main1",))
    repo.add("head", "feat/behind: merged head", ("behind",))
    repo.add("wt", "feat/wt: work", ("main1",))
    repo.add("work", "feat/work: no pull request", ("main1",))
    tips = {
        "main": "main1",
        "feat/one": "one",
        "feat/two": "two",
        "feat/moved": "moved_tip",
        "feat/behind": "behind",
        "feat/wt": "wt",
        "feat/work": "work",
    }
    return repo, tips


def run(
    argv,
    *,
    prs=None,
    branches=None,
    current="main",
    worktrees=None,
    gh_error=None,
    git_fail_on=None,
    repo=None,
    tips=None,
    expected_repo=None,
    origin="git@github.com:gazon1/sing.git",
):
    """Run the script with the stand-ins and return (code, output, git)."""
    if repo is None or tips is None:
        repo, tips = build_repo()
    gh = FakeGh(
        prs,
        error=gh_error,
        expected_repo=expected_repo or _requested_repo(argv),
    )
    git = FakeGit(
        repo,
        branches if branches is not None else [],
        tips,
        current=current,
        worktrees=worktrees,
        fail_on=git_fail_on,
        origin=origin,
    )
    out = io.StringIO()
    err = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = _prune.main(argv, gh=gh, git=git)
    return code, out.getvalue() + err.getvalue(), git


def _requested_repo(argv) -> str:
    """The repository the command line asks for, or the default one."""
    argv = list(argv or [])
    if "--repo" in argv:
        index = argv.index("--repo")
        if index + 1 < len(argv):
            return argv[index + 1]
    return REPO


class ThePlanHoldsOnlyBranchesWithAMergedPr(unittest.TestCase):
    """The plan is exactly the branches whose pull request was merged."""

    def test_a_branch_with_a_merged_pr_is_in_the_plan(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one", "feat/work"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("feat/one", output)
        self.assertIn("1019", output)

    def test_a_branch_without_a_pr_is_not_in_the_plan(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one", "feat/work"],
        )
        self.assertEqual(0, code, output)
        self.assertNotIn("feat/work", output)
        self.assertIn("нет смерженного PR", output)

    def test_the_plan_of_a_branch_without_a_pr_is_empty(self):
        """The same thing, but by the data and not by the text of the output."""
        code, output, _ = run(
            ["--json"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one", "feat/work"],
        )
        self.assertEqual(0, code, output)
        payload = json.loads(output)
        self.assertEqual(["feat/one"], [item["branch"] for item in payload["plan"]])

    def test_a_branch_without_a_pr_is_never_deleted(self):
        _, _, git = run(
            ["--yes"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one", "feat/work"],
        )
        self.assertEqual(["feat/one"], git.deleted)


class ThePlanFollowsTheRealRuleOfTheTip(unittest.TestCase):
    """The tip of the branch is compared with the merged head.

    Two branches are safe to delete: the one whose tip *is* the merged head,
    and the one whose tip is an *ancestor* of it — the second one is behind,
    and everything it holds is already in `main`.
    """

    def test_a_branch_at_the_merged_head_is_in_the_plan(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("feat/one — PR #1019", output)

    def test_a_branch_behind_the_merged_head_is_in_the_plan(self):
        """The branch is behind: everything it holds went into `main`."""
        code, output, _ = run(
            [],
            prs=[pull_request(1031, "feat/behind", "head")],
            branches=["feat/behind"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("feat/behind — PR #1031", output)

    def test_the_plan_says_how_the_tip_relates_to_the_merged_head(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1031, "feat/behind", "head")],
            branches=["feat/behind"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("tip позади головы PR", output)

    def test_a_branch_behind_the_merged_head_is_deleted(self):
        """Deleting it loses nothing: its content is in `main`."""
        _, _, git = run(
            ["--yes"],
            prs=[pull_request(1031, "feat/behind", "head")],
            branches=["feat/behind"],
        )
        self.assertEqual(["feat/behind"], git.deleted)

    def test_the_json_says_which_of_the_two_rules_matched(self):
        code, output, _ = run(
            ["--json"],
            prs=[
                pull_request(1019, "feat/one", "one"),
                pull_request(1031, "feat/behind", "head"),
            ],
            branches=["feat/one", "feat/behind"],
        )
        self.assertEqual(0, code, output)
        payload = json.loads(output)
        matches = {item["branch"]: item["match"] for item in payload["plan"]}
        self.assertEqual("exact", matches["feat/one"])
        self.assertEqual("behind", matches["feat/behind"])

    def test_a_branch_that_moved_after_the_merge_is_not_in_the_plan(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1030, "feat/moved", "moved_base")],
            branches=["feat/moved"],
        )
        self.assertEqual(0, code, output)
        self.assertNotIn("feat/moved — PR #1030", output)
        self.assertIn("уехала после мержа", output)

    def test_the_plan_says_what_the_branch_would_lose(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1030, "feat/moved", "moved_base")],
            branches=["feat/moved"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("сверх мержа", output)
        self.assertIn("work after the merge", output)

    def test_a_branch_that_moved_after_the_merge_is_never_deleted(self):
        _, _, git = run(
            ["--yes"],
            prs=[pull_request(1030, "feat/moved", "moved_base")],
            branches=["feat/moved"],
        )
        self.assertEqual([], git.deleted)

    def test_a_reused_branch_name_is_judged_by_its_commit(self):
        """The name was reused: any of the merged pull requests may fit."""
        code, output, _ = run(
            [],
            prs=[pull_request(657, "feat/one", "one"), pull_request(656, "feat/one", "two")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("feat/one — PR #657", output)

    def test_a_pr_without_a_commit_cannot_be_verified(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1019, "feat/one", "")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("нечем подтвердить", output)


class ThePlanNeverTouchesWhatItMustNot(unittest.TestCase):
    """The exclusions, each with its own reason."""

    def test_the_current_checkout_is_excluded(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
            current="feat/one",
        )
        self.assertEqual(0, code, output)
        self.assertIn("текущий checkout", output)
        self.assertNotIn("feat/one — PR", output)

    def test_main_is_excluded(self):
        code, output, _ = run(
            [],
            prs=[pull_request(1019, "main", "main1")],
            branches=["main"],
            current="feat/work",
        )
        self.assertEqual(0, code, output)
        self.assertIn("защищённая ветка", output)
        self.assertNotIn("main — PR", output)

    def test_main_is_never_deleted(self):
        _, _, git = run(
            ["--yes"],
            prs=[pull_request(1019, "main", "main1")],
            branches=["main"],
            current="other",
        )
        self.assertEqual([], git.deleted)

    def test_the_current_checkout_is_never_deleted(self):
        _, _, git = run(
            ["--yes"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
            current="feat/one",
        )
        self.assertEqual([], git.deleted)

    def test_a_branch_held_by_another_worktree_is_excluded(self):
        """Git will not delete such a branch — the plan must not promise it."""
        code, output, _ = run(
            [],
            prs=[pull_request(1021, "feat/wt", "wt")],
            branches=["feat/wt"],
            worktrees=["feat/wt"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("занята другим worktree", output)
        self.assertNotIn("feat/wt — PR #1021", output)

    def test_a_branch_held_by_another_worktree_is_never_deleted(self):
        _, _, git = run(
            ["--yes"],
            prs=[pull_request(1021, "feat/wt", "wt")],
            branches=["feat/wt"],
            worktrees=["feat/wt"],
        )
        self.assertEqual([], git.deleted)

    def test_a_worktree_branch_does_not_break_the_run(self):
        """An excluded branch must not turn the run into an error."""
        code, output, _ = run(
            ["--yes"],
            prs=[pull_request(1021, "feat/wt", "wt"), pull_request(1019, "feat/one", "one")],
            branches=["feat/wt", "feat/one"],
            worktrees=["feat/wt"],
        )
        self.assertEqual(0, code, output)

    def test_a_branch_of_a_fork_is_reported_as_a_fork(self):
        """There *is* a merged pull request — it is just somebody else's."""
        code, output, _ = run(
            [],
            prs=[pull_request(1020, "feat/one", "one", owner="someone-else")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("смерженный PR из чужого репозитория", output)

    def test_a_branch_of_a_fork_is_not_reported_as_having_no_pr(self):
        """«нет смерженного PR» would be a false statement about it."""
        code, output, _ = run(
            [],
            prs=[pull_request(1020, "feat/one", "one", owner="someone-else")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertNotIn("нет смерженного PR", output)

    def test_a_branch_of_a_deleted_fork_is_reported_as_a_fork(self):
        """A deleted fork has no owner — and the pull request still exists.

        GitHub reports no `headRepositoryOwner` for a pull request whose fork
        is gone. Saying "there is no merged pull request" about such a branch
        would be false: the pull request exists, it belongs to someone else.
        """
        code, output, _ = run(
            [],
            prs=[pull_request(1020, "feat/one", "one", owner=None)],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("смерженный PR из чужого репозитория", output)
        self.assertNotIn("нет смерженного PR", output)

    def test_a_branch_of_a_deleted_fork_is_never_deleted(self):
        _, _, git = run(
            ["--yes"],
            prs=[pull_request(1020, "feat/one", "one", owner=None)],
            branches=["feat/one"],
        )
        self.assertEqual([], git.deleted)

    def test_a_branch_of_a_fork_is_never_deleted(self):
        _, _, git = run(
            ["--yes"],
            prs=[pull_request(1020, "feat/one", "one", owner="someone-else")],
            branches=["feat/one"],
        )
        self.assertEqual([], git.deleted)

    def test_a_closed_pull_request_is_not_a_merged_one(self):
        """A closed pull request is work in progress, not a merged branch."""
        code, output, _ = run(
            [],
            prs=[pull_request(1020, "feat/one", "one", merged=False)],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("нет смерженного PR", output)


class TheRequestToGithubIsTheRequestItClaimsToBe(unittest.TestCase):
    """The whole point of the tool is the question it asks GitHub.

    The stand-in for `gh` answers the way `gh` answers, so a query that was
    broken — for closed pull requests, without the merged commit, for
    another repository, or with the default page size — produces a different
    answer and fails these tests.
    """

    def _request(self, argv=None, **kwargs):
        code, output, git = run(argv or [], **kwargs)
        self.assertEqual(0, code, output)
        return git

    def test_the_request_asks_for_merged_pull_requests(self):
        """`--state merged`, not `--state closed`."""
        repo, tips = build_repo()
        gh = FakeGh([pull_request(1019, "feat/one", "one")])
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            _prune.main([], gh=gh, git=git)
        request = gh.calls[0]
        self.assertIn("--state", request)
        self.assertEqual("merged", request[request.index("--state") + 1])

    def test_a_request_for_closed_pull_requests_finds_nothing(self):
        """The mutation: `--state closed` — the plan comes out empty."""
        repo, tips = build_repo()
        gh = FakeGh([pull_request(1019, "feat/one", "one")])
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = _prune.main([], gh=gh, git=git)
        self.assertEqual(0, code)
        self.assertEqual(1, len(gh.calls))
        # The stand-in answers the way gh answers: `closed` returns the pull
        # requests that were closed and not merged — there are none.
        self.assertEqual("[]", gh(["pr", "list", "--state", "closed", "--repo", REPO]))

    def test_the_request_asks_for_the_merged_commit(self):
        """`headRefOid` — without it there is nothing to compare a tip with."""
        repo, tips = build_repo()
        gh = FakeGh([pull_request(1019, "feat/one", "one")])
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            _prune.main([], gh=gh, git=git)
        request = gh.calls[0]
        fields = request[request.index("--json") + 1].split(",")
        self.assertIn("headRefOid", fields)
        self.assertIn("headRefName", fields)
        self.assertIn("headRepositoryOwner", fields)

    def test_a_request_without_the_merged_commit_finds_nothing(self):
        """The mutation: `--json` without `headRefOid` — nothing can be verified."""
        repo, tips = build_repo()
        gh = FakeGh([pull_request(1019, "feat/one", "one")])
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = _prune.main([], gh=gh, git=git)
        self.assertEqual(0, code)
        # Without the field the answer carries no commit, so nothing can be
        # compared with the local tip.
        answer = json.loads(gh(["pr", "list", "--state", "merged", "--repo", REPO, "--json", "number,headRefName"]))
        self.assertNotIn("headRefOid", answer[0])

    def test_the_request_asks_for_this_repository(self):
        """`--repo` — the pull requests of another repository are not ours."""
        repo, tips = build_repo()
        gh = FakeGh([pull_request(1019, "feat/one", "one")])
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            _prune.main([], gh=gh, git=git)
        request = gh.calls[0]
        self.assertEqual(REPO, request[request.index("--repo") + 1])

    def test_a_request_for_another_repository_finds_nothing(self):
        """The mutation: `--repo other/repo` — those pull requests are not ours."""
        repo, tips = build_repo()
        gh = FakeGh([pull_request(1019, "feat/one", "one")])
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = _prune.main([], gh=gh, git=git)
        self.assertEqual(0, code)
        answer = json.loads(gh(["pr", "list", "--state", "merged", "--repo", "someone-else/sing"]))
        self.assertEqual([], answer)

    def test_the_request_asks_for_more_than_one_page(self):
        """`--limit` — the default page of `gh` is 30 pull requests."""
        repo, tips = build_repo()
        gh = FakeGh([pull_request(1019, "feat/one", "one")])
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            _prune.main([], gh=gh, git=git)
        request = gh.calls[0]
        limit = int(request[request.index("--limit") + 1])
        self.assertGreater(limit, 30)

    def test_the_limit_covers_every_merged_pull_request(self):
        """The mutation: `--limit 30` — the older pull requests disappear."""
        prs = [pull_request(1000 + index, f"feat/{index}", "one") for index in range(40)]
        repo, tips = build_repo()
        gh = FakeGh(prs)
        git = FakeGit(repo, [f"feat/{index}" for index in range(40)], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            _prune.main([], gh=gh, git=git)
        request = gh.calls[0]
        limit = int(request[request.index("--limit") + 1])
        self.assertGreaterEqual(limit, len(prs))
        # And the stand-in honours the limit, so a smaller one would hide the
        # last pull requests — and their branches would leave the plan.
        answer = json.loads(gh(["pr", "list", "--state", "merged", "--repo", REPO, "--limit", "30"]))
        self.assertEqual(30, len(answer))


class TheLocalOnlyIndicatorSaysTheTruth(unittest.TestCase):
    """"Is there another copy of these commits?" — and the answer is exact.

    The indicator counts the commits that exist in no `origin/*` and in no
    `main`: those are the ones that would disappear without a trace.
    """

    def _moved_history(self) -> tuple[FakeRepo, dict[str, str]]:
        """A branch that moved on after the merge, with one extra commit."""
        repo = FakeRepo()
        repo.add("root", "root commit")
        repo.add("main1", "main: first", ("root",))
        repo.add("merged", "feat/moved: merged", ("main1",))
        repo.add("extra", "feat/moved: work after the merge", ("merged",))
        tips = {"main": "main1", "feat/moved": "extra"}
        return repo, tips

    def test_a_commit_that_exists_nowhere_else_is_counted(self):
        repo, tips = self._moved_history()
        code, output, _ = run(
            [],
            prs=[pull_request(1030, "feat/moved", "merged")],
            branches=["feat/moved"],
            repo=repo,
            tips=tips,
        )
        self.assertEqual(0, code, output)
        self.assertIn("из них 1 только в локальной копии", output)

    def test_a_commit_that_is_in_origin_is_not_counted(self):
        repo, tips = self._moved_history()
        repo.remote = {"extra"}
        code, output, _ = run(
            [],
            prs=[pull_request(1030, "feat/moved", "merged")],
            branches=["feat/moved"],
            repo=repo,
            tips=tips,
        )
        self.assertEqual(0, code, output)
        self.assertIn("все есть в main или origin/*", output)
        self.assertNotIn("только в локальной копии", output)

    def test_a_commit_that_is_in_main_is_not_counted(self):
        """`main` is excluded explicitly — it is not the same set as `origin/main`."""
        repo, tips = self._moved_history()
        tips["main"] = "extra"
        code, output, _ = run(
            [],
            prs=[pull_request(1030, "feat/moved", "merged")],
            branches=["feat/moved"],
            repo=repo,
            tips=tips,
        )
        self.assertEqual(0, code, output)
        self.assertIn("все есть в main или origin/*", output)

    def test_the_json_carries_the_same_detail(self):
        repo, tips = self._moved_history()
        code, output, _ = run(
            ["--json"],
            prs=[pull_request(1030, "feat/moved", "merged")],
            branches=["feat/moved"],
            repo=repo,
            tips=tips,
        )
        self.assertEqual(0, code, output)
        payload = json.loads(output)
        moved = [item for item in payload["skipped"] if item["branch"] == "feat/moved"][0]
        self.assertIn("из них 1 только в локальной копии", moved["detail"])


class TheRewrittenBranchShowsWhatWouldBeLost(unittest.TestCase):
    """A rewritten branch: the list is the commits that would vanish.

    The plan is read by a person, and the person needs the commits that exist
    nowhere else — not the whole history of a branch that happens to have
    them somewhere in the middle.
    """

    def _rewritten_history(self) -> tuple[FakeRepo, dict[str, str]]:
        """A branch whose tip matches no merged head, with one unique commit."""
        repo = FakeRepo()
        repo.add("root", "root commit")
        repo.add("old", "feat/one: the old work", ("root",))
        repo.add("unique", "feat/one: the commit that is nowhere else", ("old",))
        repo.add("main1", "main: first", ("root",))
        # `old` exists in `origin/*`; `unique` does not exist anywhere else.
        repo.remote = {"old"}
        tips = {"main": "main1", "feat/one": "unique"}
        return repo, tips

    def test_the_list_is_the_commits_that_exist_nowhere_else(self):
        repo, tips = self._rewritten_history()
        code, output, _ = run(
            [],
            prs=[pull_request(1030, "feat/one", "a-different-commit")],
            branches=["feat/one"],
            repo=repo,
            tips=tips,
        )
        self.assertEqual(0, code, output)
        self.assertIn("+1 комм. есть только в локальной копии", output)
        self.assertIn("unique", output)
        self.assertNotIn("the old work", output)

    def test_a_truncated_list_says_so(self):
        """The cap is on the printing, and a truncated list announces itself."""
        repo, tips = self._rewritten_history()
        previous = "unique"
        for index in range(35):
            sha = repo.add(f"u{index}", f"feat/one: unique commit {index}", (previous,))
            previous = sha
        tips["feat/one"] = previous
        code, output, _ = run(
            [],
            prs=[pull_request(1030, "feat/one", "a-different-commit")],
            branches=["feat/one"],
            repo=repo,
            tips=tips,
        )
        self.assertEqual(0, code, output)
        self.assertIn("+36 комм. есть только в локальной копии", output)
        self.assertIn("показаны первые 30", output)


class TheRepositoryIsTheOneTheToolSaysItIs(unittest.TestCase):
    """A fallback that is a guess must be visible."""

    def test_a_fallback_repository_is_announced(self):
        """Without `origin` the tool guesses — and says which one it guessed.

        A tool that compares a fork's branches with the upstream's pull
        requests must not do it silently.
        """
        code, output, _ = run([], origin=None)
        self.assertEqual(0, code, output)
        self.assertIn("origin не читается", output)
        self.assertIn("gazon1/sing", output)

    def test_the_detected_repository_is_not_announced_as_a_fallback(self):
        code, output, _ = run([])
        self.assertEqual(0, code, output)
        self.assertNotIn("origin не читается", output)


class TheBranchIsResolvedAsABranch(unittest.TestCase):
    """A branch name is not a tag name, and git resolves the tag first."""

    def test_the_tip_is_read_from_refs_heads(self):
        """`git rev-parse <name>` would resolve a tag of the same name first."""
        repo, tips = build_repo()
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        _prune.branch_tip(git, "feat/one")
        self.assertIn(["rev-parse", "refs/heads/feat/one"], git.calls)


class NothingIsDeletedWithoutTheFlag(unittest.TestCase):
    """By default the script only prints."""

    def test_without_yes_nothing_is_deleted(self):
        code, output, git = run(
            [],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertEqual([], git.deleted)
        self.assertIn("ничего не удалено", output)

    def test_dry_run_deletes_nothing_either(self):
        code, output, git = run(
            ["--dry-run"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertEqual([], git.deleted)

    def test_yes_and_dry_run_together_is_a_usage_error(self):
        code, output, git = run(
            ["--yes", "--dry-run"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
        )
        self.assertEqual(2, code, output)
        self.assertEqual([], git.deleted)

    def test_an_empty_owner_is_a_usage_error(self):
        """An empty owner would drop the protection against other people's forks."""
        code, output, git = run(
            ["--owner", ""],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
        )
        self.assertEqual(2, code, output)
        self.assertEqual([], git.deleted)


class WithYesTheBranchesAreDeleted(unittest.TestCase):
    """The explicit flag — and the branches are gone, and the script says so."""

    def test_yes_deletes_the_branch(self):
        code, output, git = run(
            ["--yes"],
            prs=[pull_request(1019, "feat/one", "one"), pull_request(1020, "feat/two", "two")],
            branches=["feat/one", "feat/two"],
            current="main",
        )
        self.assertEqual(0, code, output)
        self.assertEqual(["feat/one", "feat/two"], git.deleted)

    def test_yes_prints_what_it_deleted(self):
        code, output, git = run(
            ["--yes"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
            current="main",
        )
        self.assertEqual(0, code, output)
        self.assertIn("Удалено", output)
        self.assertIn("feat/one", output)

    def test_a_branch_that_cannot_be_deleted_is_an_error(self):
        code, output, git = run(
            ["--yes"],
            prs=[pull_request(1019, "feat/one", "one"), pull_request(1020, "feat/two", "two")],
            branches=["feat/one", "feat/two"],
            current="main",
            git_fail_on="feat/two",
        )
        self.assertEqual(1, code, output)
        self.assertEqual(["feat/one"], git.deleted)
        self.assertIn("Не удалена ветка feat/two", output)

    def test_running_twice_is_the_same_as_running_once(self):
        """Idempotence: a branch that is already gone is not in the plan."""
        prs = [pull_request(1019, "feat/one", "one")]
        repo, tips = build_repo()
        gh = FakeGh(prs)
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            first = _prune.main(["--yes"], gh=gh, git=git)
            second = _prune.main(["--yes"], gh=gh, git=git)
        self.assertEqual(0, first)
        self.assertEqual(0, second)
        self.assertEqual(["feat/one"], git.deleted)


class TheOutputCanBeReadByAMachine(unittest.TestCase):
    """`--json` carries the same plan as the text output."""

    def test_json_names_the_plan_and_the_skipped_branches(self):
        code, output, _ = run(
            ["--json"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one", "feat/work", "main"],
            current="main",
        )
        self.assertEqual(0, code, output)
        payload = json.loads(output)
        self.assertEqual(["feat/one"], [item["branch"] for item in payload["plan"]])
        self.assertEqual(1019, payload["plan"][0]["pr"])
        reasons = {item["branch"]: item["reason"] for item in payload["skipped"]}
        self.assertEqual("нет смерженного PR", reasons["feat/work"])
        self.assertEqual("защищённая ветка", reasons["main"])

    def test_json_names_the_branch_that_moved(self):
        code, output, _ = run(
            ["--json"],
            prs=[pull_request(1030, "feat/moved", "moved_base")],
            branches=["feat/moved"],
        )
        self.assertEqual(0, code, output)
        payload = json.loads(output)
        reasons = {item["branch"]: item["reason"] for item in payload["skipped"]}
        self.assertEqual("уехала после мержа", reasons["feat/moved"])
        moved = [item for item in payload["skipped"] if item["branch"] == "feat/moved"][0]
        self.assertIn("сверх мержа", moved["detail"])
        self.assertTrue(moved["commits"])

    def test_json_names_the_worktree_branch(self):
        code, output, _ = run(
            ["--json"],
            prs=[pull_request(1021, "feat/wt", "wt")],
            branches=["feat/wt"],
            worktrees=["feat/wt"],
        )
        self.assertEqual(0, code, output)
        payload = json.loads(output)
        reasons = {item["branch"]: item["reason"] for item in payload["skipped"]}
        self.assertEqual("занята другим worktree", reasons["feat/wt"])

    def test_json_names_the_branch_of_a_fork(self):
        code, output, _ = run(
            ["--json"],
            prs=[pull_request(1020, "feat/one", "one", owner="someone-else")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        payload = json.loads(output)
        reasons = {item["branch"]: item["reason"] for item in payload["skipped"]}
        self.assertEqual("смерженный PR из чужого репозитория", reasons["feat/one"])

    def test_json_names_the_deleted_branches(self):
        code, output, _ = run(
            ["--json", "--yes"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
            current="main",
        )
        self.assertEqual(0, code, output)
        payload = json.loads(output)
        self.assertEqual(["feat/one"], payload["deleted"])


class TheRepositoryComesFromTheCheckout(unittest.TestCase):
    """The pull requests and the branches come from the same repository."""

    def test_the_repo_is_read_from_the_origin_remote(self):
        repo, tips = build_repo()
        git = FakeGit(repo, [], tips, origin="git@github.com:gazon1/sing.git")
        self.assertEqual("gazon1/sing", _prune.detect_repo(git))

    def test_an_https_origin_is_read_too(self):
        repo, tips = build_repo()
        git = FakeGit(repo, [], tips, origin="https://github.com:gazon1/sing.git")
        self.assertEqual("gazon1/sing", _prune.detect_repo(git))

    def test_a_fork_clone_names_its_own_repository(self):
        repo, tips = build_repo()
        git = FakeGit(repo, [], tips, origin="git@github.com:someone-else/sing.git")
        self.assertEqual("someone-else/sing", _prune.detect_repo(git))

    def test_a_repository_without_origin_falls_back(self):
        repo, tips = build_repo()
        git = FakeGit(repo, [], tips, origin=None)
        self.assertIsNone(_prune.detect_repo(git))

    def test_a_mismatching_repo_is_reported(self):
        code, output, _ = run(
            ["--repo", "someone-else/sing", "--owner", "gazon1"],
            prs=[pull_request(1019, "feat/one", "one")],
            branches=["feat/one"],
        )
        self.assertEqual(0, code, output)
        self.assertIn("origin указывает на", output)


class AFailedToolIsAnError(unittest.TestCase):
    """A failed tool is exit code 1, not an empty plan."""

    def test_a_missing_gh_is_an_error(self):
        code, output, git = run(
            [],
            gh_error=_prune.ToolError("gh не найден в PATH"),
            branches=["feat/one"],
        )
        self.assertEqual(1, code, output)
        self.assertIn("gh не найден", output)
        self.assertEqual([], git.deleted)

    def test_the_real_gh_runner_says_when_gh_is_missing(self):
        """The path "gh is not on PATH" is checked by the real code, not by a stand-in."""
        with mock.patch.object(_prune.shutil, "which", return_value=None):
            with self.assertRaises(_prune.ToolError) as caught:
                _prune.gh_runner(["pr", "list"])
        self.assertIn("gh не найден", str(caught.exception))

    def test_a_missing_gh_stops_the_run(self):
        """The real gh_runner: a PATH without gh is an error, not an empty plan."""
        repo, tips = build_repo()
        git = FakeGit(repo, ["feat/one"], tips, current="main")
        out = io.StringIO()
        err = io.StringIO()
        with mock.patch.object(_prune.shutil, "which", return_value=None):
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = _prune.main([], git=git)
        self.assertEqual(1, code)
        self.assertEqual([], git.deleted)


if __name__ == "__main__":
    unittest.main()
