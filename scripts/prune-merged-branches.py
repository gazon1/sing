#!/usr/bin/env python3
"""prune-merged-branches.py — remove local branches whose pull request was merged.

Why this exists
---------------
This repository merges pull requests by squash. A squash-merge does not make
the branch an ancestor of `main`: `main` gets one new commit, and the branch's
own history stays off the line. Git therefore does not see the branch as
merged — `git branch --merged` does not list it and `git branch -d` refuses to
delete it ("not fully merged"). The branch stays in the repository forever,
and their number grows with every merged pull request.

What the script does
--------------------
1. asks GitHub which pull requests are merged (`gh pr list --state merged`),
   with the commit that was merged (`headRefOid`) and the owner of the head;
2. takes the local branches from git (`git for-each-ref refs/heads/`);
3. compares the local tip of each branch with the merged head;
4. builds a plan and — by default — only prints it.

Deletion happens only under the explicit `--yes` flag. The script never
deletes anything on its own: a branch without a merged pull request may
belong to another session, and a branch with one may still be wanted by its
author.

What is never deleted, and why
------------------------------
- the branch the current checkout stands on;
- a branch held by another worktree (`git worktree list --porcelain`): git
  refuses to delete such a branch anyway, so the attempt would turn into an
  error instead of a result;
- `main` (and `master`) — the branches that always live;
- a branch with no merged pull request of our own repository: it may have an
  open pull request, and that pull request is still work in progress;
- a branch whose name matches a pull request of a *fork*: that pull request
  is somebody else's, and the local branch of the same name is not the same
  thing;
- a branch that could not be verified at all: `gh` did not report the commit
  that was merged, so there is nothing to compare the local tip with;
- **a branch that moved on after the merge.** The name matches
  `headRefName` of a merged pull request, but the local tip is neither the
  merged commit nor an ancestor of it. Something was added to the branch
  after the merge (or the branch was rewritten), and `git branch -D` would
  destroy what exists neither in `main` nor in any `origin/*`. Such branches
  get their own category and are printed with the commits they carry — the
  decision stays with the person.

The rule the plan follows
-------------------------
A branch is deleted when its tip is **the commit that was merged** (the tip
equals the pull request's `headRefOid`) **or an ancestor of it** (the branch
is behind the merged head). The second case is safe for the same reason the
first one is: everything the branch holds is inside the merged pull request,
and the merged pull request is in `main`. There is nothing in the branch that
is not already in `main` — so deleting it loses nothing.

Usage:
    python3 scripts/prune-merged-branches.py            # the plan only
    python3 scripts/prune-merged-branches.py --dry-run  # the same thing
    python3 scripts/prune-merged-branches.py --json     # machine-readable
    python3 scripts/prune-merged-branches.py --yes      # delete

Exit codes:
    0 = the plan was built (and, with `--yes`, everything was deleted)
    1 = an error (for example `gh` is not installed, or a branch could not
        be deleted)
    2 = a usage error (for example `--yes` together with `--dry-run`)

Tests:
    scripts/tests/test_prune_merged_branches.py — no network needed: the
    calls to `gh` and `git` are injected, and the plan is computed on real
    data.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass

#: The repository the pull requests are read from, when the origin remote
#: cannot be read.
DEFAULT_REPO = "gazon1/sing"

#: The owner of the branches, when the owner cannot be derived from the
#: repository.
DEFAULT_OWNER = "gazon1"

#: The branches that are never deleted, even when they have a merged pull
#: request.
PROTECTED_BRANCHES = frozenset({"main", "master"})

#: Why a branch is not deleted. The text is what the user reads, so it lives
#: here rather than being written at the call site.
REASON_PROTECTED = "защищённая ветка"
REASON_CURRENT = "текущий checkout"
REASON_WORKTREE = "занята другим worktree"
REASON_NO_PR = "нет смерженного PR"
REASON_FOREIGN = "смерженный PR из чужого репозитория"
REASON_MOVED = "уехала после мержа"
REASON_UNVERIFIED = "нечем подтвердить, что это тот самый PR"

#: How the tip of a branch relates to the merged head. The plan reports the
#: match, so that the numbers in the ADR can be read off the tool's output.
MATCH_EXACT = "exact"
MATCH_BEHIND = "behind"

#: How many merged pull requests to ask GitHub for.
#:
#: Measured on 2026-10-24: the repository has 311 merged pull requests in
#: total (`gh pr list --state merged --limit 2000 --json number`), so a
#: limit of 1000 covers every one of them with a factor of three in reserve.
#: The default page of `gh` is 30, which would hide all but the most recent
#: pull requests — and with them the branches that are the point of the
#: script.
PR_LIST_LIMIT = "1000"

#: How many commits of a branch that moved on after the merge are printed.
#:
#: Measured on 2026-10-24: the longest list of commits that exist only in the
#: local copy, among the branches that moved on after the merge in that
#: checkout, was 19 — so the cap of 30 printed every one of them in full. The
#: cap is a cap on the *printing*, not on the decision — the number of commits
#: is always printed in full, and a truncated list says so.
EXTRA_COMMIT_LIMIT = "30"

CLEAN = 0
ERROR = 1
USAGE = 2


class ToolError(RuntimeError):
    """An external tool (`gh` or `git`) failed."""


#: A function that runs an external command and returns its stdout.
#:
#: The type is spelled out because the tests replace both functions with
#: their own: a test must not reach the network.
Tool = Callable[[Sequence[str]], str]


@dataclass(frozen=True)
class MergedPr:
    """A merged pull request: its number, its branch, and the merged commit."""

    number: int
    head: str
    oid: str


@dataclass(frozen=True)
class Candidate:
    """A branch the plan may delete, the pull request that merged it, and how
    the local tip relates to the merged head."""

    name: str
    pr: int | None = None
    match: str = MATCH_EXACT


@dataclass(frozen=True)
class Skipped:
    """A branch the script leaves alone, and the reason why."""

    name: str
    reason: str
    detail: str = ""
    commits: tuple[str, ...] = ()


def gh_runner(args: Sequence[str]) -> str:
    """Run `gh` and return its stdout.

    A missing `gh` is an error (exit code 1), not an empty list: an empty
    list would mean "there are no merged pull requests", and the plan would
    come out empty for a reason that is not the reason it is empty.
    """
    if shutil.which("gh") is None:
        raise ToolError("gh не найден в PATH — установите GitHub CLI")
    return _run(["gh", *args], "gh")


def git_runner(args: Sequence[str]) -> str:
    """Run `git` and return its stdout."""
    return _run(["git", *args], "git")


def _run(argv: list[str], tool: str) -> str:
    """Run a command and return its stdout; on failure raise `ToolError`."""
    try:
        result = subprocess.run(  # noqa: S603 — a fixed argv, no shell
            argv,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as exc:
        raise ToolError(f"{tool} не запустился: {exc}") from exc
    if result.returncode != 0:
        message = (result.stderr or result.stdout or f"{tool} завершился с ошибкой").strip()
        first = message.splitlines()[0] if message else f"{tool} завершился с ошибкой"
        raise ToolError(first)
    return result.stdout


def merged_prs(gh: Tool, repo: str) -> list[dict]:
    """The merged pull requests of a repository, as `gh` reports them.

    The request asks for the merged commit (`headRefOid`) and the owner of
    the head (`headRepositoryOwner`) as well: without the commit there is
    nothing to compare a local tip with, and without the owner a branch of a
    fork looks like one of ours.
    """
    payload = gh(
        [
            "pr",
            "list",
            "--state",
            "merged",
            "--repo",
            repo,
            "--limit",
            PR_LIST_LIMIT,
            "--json",
            "number,headRefName,headRepositoryOwner,headRefOid",
        ]
    )
    try:
        data = json.loads(payload or "[]")
    except json.JSONDecodeError as exc:
        raise ToolError(f"gh вернул не JSON: {exc}") from exc
    if not isinstance(data, list):
        raise ToolError("gh вернул не список PR")
    return data


def merged_branch_prs(
    prs: Iterable[dict], owner: str
) -> tuple[dict[str, list[MergedPr]], set[str]]:
    """The merged pull requests, grouped by branch — ours and foreign.

    Returns `(ours, foreign)`. `ours` maps a branch name to its merged pull
    requests; `foreign` holds the names of branches whose pull request
    belongs to somebody else.

    Only the branches of `owner` are ours: a fork's branch lives in another
    repository, and the local branch of the same name is not the same thing.
    Such a branch is reported separately — "there is no merged pull request
    of ours" would be a false statement about it.

    A branch name can be reused — `gazon1/noddy` has five merged pull
    requests — so a branch carries a list, and the question "is this the same
    pull request?" is answered by the commit (`oid`), not by the name.
    """
    ours: dict[str, list[MergedPr]] = {}
    foreign: set[str] = set()
    for pr in prs:
        if not isinstance(pr, dict):
            continue
        head = str(pr.get("headRefName") or "").strip()
        if not head:
            continue
        owner_object = pr.get("headRepositoryOwner")
        if not isinstance(owner_object, dict):
            # The owner is unknown (a deleted fork) — such a branch is not ours,
            # and saying "there is no merged pull request" about it would be
            # false: the pull request exists, it belongs to someone else.
            foreign.add(head)
            continue
        login = str(owner_object.get("login") or "").strip()
        try:
            number = int(pr.get("number"))
        except (TypeError, ValueError):
            continue
        if login != owner:
            foreign.add(head)
            continue
        oid = str(pr.get("headRefOid") or "").strip()
        ours.setdefault(head, []).append(MergedPr(number=number, head=head, oid=oid))
    for items in ours.values():
        items.sort(key=lambda item: item.number, reverse=True)
    return ours, foreign


def local_branches(git: Tool) -> list[str]:
    """The local branches of this checkout."""
    output = git(["for-each-ref", "--format=%(refname:short)", "refs/heads/"])
    return [line.strip() for line in output.splitlines() if line.strip()]


def current_branch(git: Tool) -> str | None:
    """The branch the checkout stands on, or `None` when HEAD is detached.

    `git rev-parse --abbrev-ref HEAD` prints `HEAD` and exits with 0 on a
    detached HEAD, so no separate error handling is needed: the name `HEAD`
    is not the name of a branch.
    """
    name = git(["rev-parse", "--abbrev-ref", "HEAD"]).strip()
    if not name or name == "HEAD":
        return None
    return name


def worktree_branches(git: Tool) -> set[str]:
    """The branches held by some worktree — the current one included.

    `git branch -D` refuses to delete such a branch, so the plan must not
    promise it: git would return an error anyway, and the run would end with
    a non-zero exit code.
    """
    output = git(["worktree", "list", "--porcelain"])
    branches: set[str] = set()
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("branch refs/heads/"):
            branches.add(stripped[len("branch refs/heads/") :].strip())
    return branches


def branch_tip(git: Tool, name: str) -> str:
    """The commit the local branch points at.

    The ref is spelled out in full: `git rev-parse <name>` resolves a tag
    before a branch of the same name, so a branch called `release` would be
    compared with the commit of a tag called `release`.
    """
    return git(["rev-parse", f"refs/heads/{name}"]).strip()


def merge_base(git: Tool, left: str, right: str) -> str:
    """The common ancestor of two commits, or an empty string if there is none."""
    try:
        return git(["merge-base", left, right]).strip()
    except ToolError:
        return ""


def is_ancestor(git: Tool, ancestor: str, descendant: str) -> bool:
    """Whether `ancestor` is an ancestor of `descendant`."""
    return merge_base(git, ancestor, descendant) == ancestor


def rev_count(git: Tool, revs: Sequence[str]) -> int:
    """How many commits the range `revs` holds (the first one is the "from")."""
    output = git(["rev-list", "--count", *revs]).strip()
    try:
        return int(output or "0")
    except ValueError:
        return 0


def rev_log(git: Tool, revs: Sequence[str], limit: str = EXTRA_COMMIT_LIMIT) -> tuple[str, ...]:
    """The commits of a range — one short line each."""
    output = git(["log", "--format=%h %s", "-n", limit, *revs])
    return tuple(line.strip() for line in output.splitlines() if line.strip())


def local_only_exclusions(git: Tool) -> list[str]:
    """The revisions that are NOT "only in the local copy".

    `main` is excluded explicitly rather than through `origin/main`: in a
    checkout whose `main` is behind `origin/main`, the two are not the same
    set, and a commit that is in `main` is not lost when the branch goes.
    """
    exclusions = ["--not", "--remotes"]
    try:
        git(["rev-parse", "--verify", "--quiet", "refs/heads/main"])
    except ToolError:
        return exclusions  # no local `main` — nothing to exclude
    exclusions.append("refs/heads/main")
    return exclusions


def local_only_count(git: Tool, revs: Sequence[str]) -> int:
    """How many commits of the range exist in no `origin/*` and in no `main`.

    That is the question "is there another copy of them?" — a commit that is
    in neither the trunk nor a remote branch exists only in this local copy,
    and deleting the branch would destroy it.
    """
    return rev_count(git, [*revs, *local_only_exclusions(git)])


def local_only_commits(git: Tool, revs: Sequence[str]) -> tuple[str, ...]:
    """The commits of the range that exist in no `origin/*` and in no `main`.

    The same set `local_only_count` counts, so the list that is printed and
    the number that is printed next to it are the same set — a person reading
    the plan sees the commits that would be lost, not the history of a branch
    that was rewritten.
    """
    return rev_log(git, [*revs, *local_only_exclusions(git)])


def detect_repo(git: Tool) -> str | None:
    """The repository the `origin` remote points at, as `owner/name`.

    Without this step the script compared the branches of a fork clone with
    the pull requests of the upstream: the names match and the branches do
    not.
    """
    try:
        url = git(["remote", "get-url", "origin"]).strip()
    except ToolError:
        return None
    match = re.search(r"[:/](?P<owner>[^/:]+)/(?P<name>[^/]+?)(?:\.git)?/?$", url)
    if match is None:
        return None
    return f"{match.group('owner')}/{match.group('name')}"


def repo_owner(repo: str) -> str:
    """The owner of a repository, from an `owner/name` string."""
    return repo.split("/", 1)[0].strip()


def moved_branch(
    git: Tool,
    prs: Sequence[MergedPr],
    tip: str,
) -> tuple[str, tuple[str, ...]]:
    """What a person loses by deleting a branch that moved on.

    Returns `(description, commits)`. When one of the merged heads is an
    ancestor of the tip, the commits beyond the merge are counted; when there
    is none (the branch was rewritten), the commits that exist in no
    `origin/*` and in no `main` are counted — those are the ones that would
    disappear without a trace.
    """
    best: tuple[int, str, int] | None = None
    for pr in prs:
        if not pr.oid or not is_ancestor(git, pr.oid, tip):
            continue
        count = rev_count(git, [f"{pr.oid}..{tip}"])
        if best is None or count < best[0]:
            best = (count, pr.oid, pr.number)
    if best is not None:
        count, oid, number = best
        local_only = local_only_count(git, [f"{oid}..{tip}"])
        if local_only:
            tail = f", из них {local_only} только в локальной копии"
        else:
            tail = ", все есть в main или origin/*"
        commits = rev_log(git, [f"{oid}..{tip}"])
        if len(commits) < count:
            tail += f" (показаны первые {len(commits)})"
        return (
            f"PR #{number}: +{count} комм. сверх мержа{tail}",
            commits,
        )
    count = local_only_count(git, [tip])
    numbers = ", ".join(f"#{pr.number}" for pr in prs)
    if count:
        commits = local_only_commits(git, [tip])
        if len(commits) < count:
            numbers += f" (показаны первые {len(commits)})"
        return (
            f"PR {numbers}: tip не совпал ни с одной головой PR, "
            f"+{count} комм. есть только в локальной копии",
            commits,
        )
    return (
        f"PR {numbers}: tip не совпал ни с одной головой PR (ветку переписали), "
        f"её коммиты есть в main или origin/*",
        (),
    )


def build_plan(
    git: Tool,
    branches: Sequence[str],
    merged: dict[str, list[MergedPr]],
    foreign: set[str],
    current: str | None,
    worktrees: set[str],
) -> tuple[list[Candidate], list[Skipped]]:
    """Split the branches into "may be deleted" and "leave alone".

    The order of the checks matters. For the current branch it matters more
    to say "this is the current checkout" than "it has no merged pull
    request" — the first answers the question "why was it not deleted", the
    second answers a different one. A branch without a merged pull request
    comes before the worktree check: the absence of a pull request is the
    reason the branch is left alone, and being held by a worktree matters
    only where the branch would otherwise have been deleted.
    """
    plan: list[Candidate] = []
    skipped: list[Skipped] = []
    for name in branches:
        if name in PROTECTED_BRANCHES:
            skipped.append(Skipped(name, REASON_PROTECTED))
            continue
        if current is not None and name == current:
            skipped.append(Skipped(name, REASON_CURRENT))
            continue
        prs = merged.get(name)
        if not prs:
            if name in foreign:
                skipped.append(Skipped(name, REASON_FOREIGN))
            else:
                skipped.append(Skipped(name, REASON_NO_PR))
            continue
        if name in worktrees:
            skipped.append(Skipped(name, REASON_WORKTREE))
            continue
        if not any(pr.oid for pr in prs):
            # `gh` did not report the commit of the pull request — there is
            # nothing to compare the local tip with, and the name alone is
            # not enough to delete a branch.
            skipped.append(Skipped(name, REASON_UNVERIFIED))
            continue
        try:
            tip = branch_tip(git, name)
        except ToolError:
            skipped.append(Skipped(name, REASON_UNVERIFIED))
            continue
        exact = [pr for pr in prs if pr.oid and pr.oid == tip]
        if exact:
            plan.append(Candidate(name, max(pr.number for pr in exact), MATCH_EXACT))
            continue
        behind = [pr for pr in prs if pr.oid and is_ancestor(git, tip, pr.oid)]
        if behind:
            # The branch is behind the merged head: everything it holds went
            # into `main` through that pull request. Nothing is lost.
            plan.append(Candidate(name, max(pr.number for pr in behind), MATCH_BEHIND))
            continue
        detail, commits = moved_branch(git, prs, tip)
        skipped.append(Skipped(name, REASON_MOVED, detail=detail, commits=commits))
    return plan, skipped


def delete_branch(git: Tool, name: str) -> None:
    """Delete a local branch.

    `-D`, not `-d`: after a squash-merge the branch is not an ancestor of
    `main`, and the careful `-d` would refuse exactly the branches this
    script exists for. The safety is not in the flag but in the plan: it
    holds only branches whose tip is the commit that was merged **or an
    ancestor of it** (the branch is behind the merged head — its content is
    already in `main`), and that no worktree holds.
    """
    git(["branch", "-D", name])


def _parser() -> argparse.ArgumentParser:
    """The command line."""
    parser = argparse.ArgumentParser(
        prog="prune-merged-branches.py",
        description="Убрать локальные ветки, чей PR смержен (по умолчанию — только план).",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="удалить найденные ветки (без этого флага скрипт только печатает план)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="только план — то же, что запуск без --yes",
    )
    parser.add_argument("--json", dest="as_json", action="store_true", help="машиночитаемый вывод")
    parser.add_argument(
        "--repo",
        default=None,
        help=f"репозиторий с PR (по умолчанию — origin-remote, иначе {DEFAULT_REPO})",
    )
    parser.add_argument(
        "--owner",
        default=None,
        help="владелец веток, которые можно удалять (по умолчанию — владелец репозитория)",
    )
    return parser


def _match_note(match: str) -> str:
    """The note that says why the branch is safe to delete."""
    if match == MATCH_BEHIND:
        return "tip позади головы PR — содержимое уже в main"
    return "tip = голова PR"


def _print_plan(plan: Sequence[Candidate], skipped: Sequence[Skipped]) -> None:
    """Print the plan: what is deleted, and what is left alone.

    The branches without a merged pull request are counted rather than
    listed: there are dozens of them, and every line about them answers a
    question the count already answers. The exceptions that have a reason of
    their own are listed: "the current checkout", "held by another
    worktree", "moved on after the merge" and "a protected branch" explain
    why the branch stayed, and that is worth reading.
    """
    if plan:
        print(f"Ветки со смерженным PR ({len(plan)}):")
        for candidate in plan:
            print(f"  {candidate.name} — PR #{candidate.pr} ({_match_note(candidate.match)})")
    else:
        print("Веток со смерженным PR не найдено.")

    notable = [item for item in skipped if item.reason != REASON_NO_PR]
    if notable:
        print(f"Не трогаем ({len(notable)}):")
        for item in notable:
            detail = f": {item.detail}" if item.detail else ""
            print(f"  {item.name} — {item.reason}{detail}")
            for commit in item.commits:
                print(f"      {commit}")

    without_pr = len(skipped) - len(notable)
    if without_pr:
        print(f"Ещё {without_pr} веток: у них нет смерженного PR — они не тронуты.")


def main(
    argv: Sequence[str] | None = None,
    *,
    gh: Tool = gh_runner,
    git: Tool = git_runner,
) -> int:
    """The entry point. `gh` and `git` are injected, so a test needs no network."""
    parser = _parser()
    try:
        args = parser.parse_args(list(argv) if argv is not None else None)
    except SystemExit as exc:  # argparse prints the usage itself and exits with 2
        return int(exc.code) if exc.code is not None else USAGE

    if args.yes and args.dry_run:
        print(
            "prune-merged-branches: --dry-run и --yes вместе не имеют смысла — "
            "либо план, либо удаление.",
            file=sys.stderr,
        )
        return USAGE

    if args.owner is not None and not args.owner.strip():
        print(
            "prune-merged-branches: --owner не может быть пустым — "
            "пустой владелец снял бы защиту от чужих форков.",
            file=sys.stderr,
        )
        return USAGE

    detected = detect_repo(git)
    repo = args.repo or detected or DEFAULT_REPO
    if not args.repo and detected is None:
        # The fallback is a guess, and a guess the reader cannot see is a
        # guess that can be wrong: the tool says which repository it used.
        print(
            f"prune-merged-branches: origin не читается — PR'ы берутся из {repo} "
            f"(репозиторий по умолчанию).",
            file=sys.stderr,
        )
    if args.repo and detected and args.repo != detected:
        print(
            f"prune-merged-branches: --repo {args.repo}, а origin указывает на "
            f"{detected} — ветки берутся из текущего checkout, PR'ы — из {args.repo}.",
            file=sys.stderr,
        )
    owner = (args.owner or repo_owner(repo) or DEFAULT_OWNER).strip()

    try:
        prs = merged_prs(gh, repo)
        branches = local_branches(git)
        current = current_branch(git)
        worktrees = worktree_branches(git)
    except ToolError as exc:
        print(f"prune-merged-branches: {exc}", file=sys.stderr)
        return ERROR

    ours, foreign = merged_branch_prs(prs, owner)
    plan, skipped = build_plan(git, branches, ours, foreign, current, worktrees)

    deleted: list[str] = []
    failed: list[tuple[str, str]] = []
    if args.yes:
        for candidate in plan:
            try:
                delete_branch(git, candidate.name)
            except ToolError as exc:
                failed.append((candidate.name, str(exc)))
            else:
                deleted.append(candidate.name)

    if args.as_json:
        print(
            json.dumps(
                {
                    "repo": repo,
                    "owner": owner,
                    "current_branch": current,
                    "plan": [
                        {"branch": c.name, "pr": c.pr, "match": c.match} for c in plan
                    ],
                    "skipped": [
                        {
                            "branch": s.name,
                            "reason": s.reason,
                            "detail": s.detail,
                            "commits": list(s.commits),
                        }
                        for s in skipped
                    ],
                    "deleted": deleted,
                    "failed": [{"branch": name, "error": error} for name, error in failed],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        _print_plan(plan, skipped)
        if args.yes:
            if deleted:
                print(f"Удалено ({len(deleted)}):")
                for name in deleted:
                    print(f"  {name}")
            for name, error in failed:
                print(f"Не удалена ветка {name}: {error}", file=sys.stderr)
        else:
            print("Это план: ничего не удалено. Повторите с --yes, чтобы удалить.")

    return ERROR if failed else CLEAN


if __name__ == "__main__":
    raise SystemExit(main())
