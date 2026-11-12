#!/usr/bin/env python3
"""allure_deploy.py — check that the deploy published what it was asked to.

The publication ends with a push to the `gh-pages` branch, and the custom
domain lives in a CNAME file at the root of that branch. The deploy
replaces the branch contents with the publish tree, so a CNAME that is in
the branch and not in the publish tree takes the custom domain with it.

The check reads the branch back after the deploy, and the read has to
wait. The deploy is a push, and the API that answers the read may still
be serving the previous revision for a moment after the push: reading
the CNAME of the previous revision proves nothing about the revision that
was published.

So the check waits for the branch to reach the commit the deployment
created, and only then reads the CNAME. The failures it reports are told
apart, because they are three different things: a stale revision (the
branch is not the one that was deployed), a CNAME that is gone (the
deploy dropped it), and an API that did not answer (nothing was read at
all).
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import pathlib
import sys
import time
from datetime import datetime, timezone
from typing import Any

# The helpers of this directory are imported as modules, not as a package:
# the workflow runs this file as a script, and the tests load it by path.
_CI_DIR = pathlib.Path(__file__).resolve().parent
if str(_CI_DIR) not in sys.path:
    sys.path.insert(0, str(_CI_DIR))

from github_api import (  # noqa: E402
    LinkError,
    NotFound,
    Runner,
    gh_api,
    json_document,
)

#: The branch the report is published on.
DEFAULT_BRANCH = "gh-pages"

#: The file the custom domain lives in.
CNAME = "CNAME"

#: How many times the check reads the branch before it gives up, and how
#: long it waits between two reads. Ten reads at three seconds apart give
#: the API half a minute to catch up with the push.
DEFAULT_ATTEMPTS = 10
DEFAULT_DELAY = 3.0


# ── GitHub ────────────────────────────────────────────────────────────────────


def fetch_deployments(repo: str, runner: Runner) -> list[dict[str, Any]]:
    """The Pages deployments of the repository, newest first."""
    try:
        data = json_document(
            runner,
            [f"repos/{repo}/deployments?environment=github-pages&per_page=10"],
        )
    except NotFound:
        return []
    if not isinstance(data, list):
        return []
    return [item for item in data if isinstance(item, dict)]


def _moment(value: Any) -> datetime | None:
    """The moment a timestamp names, or None when it names none."""
    text = str(value or "").strip()
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None


def newest_deployment(
    deployments: list[dict[str, Any]],
    since: str | None = None,
) -> dict[str, Any] | None:
    """The deployment that is the newest one of the given moment onwards.

    The deployment of the publication that is being verified is the newest
    one that was created after the deploy started. The deployments of the
    earlier publications are older than that, and their commit is not the
    commit this run published.
    """
    floor = _moment(since)
    candidates = []
    for deployment in deployments:
        sha = str(deployment.get("sha") or "")
        if not sha:
            continue
        created = _moment(deployment.get("created_at"))
        if floor is not None and (created is None or created < floor):
            continue
        candidates.append((created or datetime.min.replace(tzinfo=timezone.utc), deployment))
    if not candidates:
        return None
    return max(candidates, key=lambda pair: pair[0])[1]


def fetch_branch_sha(repo: str, branch: str, runner: Runner) -> str | None:
    """The commit the branch points at, or None when there is no branch."""
    try:
        data = json_document(runner, [f"repos/{repo}/git/ref/heads/{branch}"])
    except NotFound:
        return None
    if isinstance(data, dict):
        sha = (data.get("object") or {}).get("sha")
        if isinstance(sha, str) and sha:
            return sha
    return None


def fetch_file_content(repo: str, path: str, ref: str, runner: Runner) -> str | None:
    """The content of a file on a ref, or None when there is no such file."""
    try:
        data = json_document(runner, [f"repos/{repo}/contents/{path}?ref={ref}"])
    except NotFound:
        return None
    if not isinstance(data, dict):
        return None
    content = data.get("content")
    if not isinstance(content, str) or not content:
        return None
    try:
        return base64.b64decode(content).decode("utf-8", errors="replace")
    except (binascii.Error, ValueError):
        return None


# ── The decisions ─────────────────────────────────────────────────────────────


def wait_for_deployed_revision(
    repo: str,
    branch: str,
    runner: Runner,
    since: str | None = None,
    attempts: int = DEFAULT_ATTEMPTS,
    delay: float = DEFAULT_DELAY,
    sleep: Any = time.sleep,
) -> dict[str, Any]:
    """Wait until the branch holds the commit the deployment created.

    The answer is the state of the wait: `reached` when the branch is the
    deployed commit, `stale` when it is not — after the attempts are
    spent. An API that does not answer is not a state of the branch, and
    it is raised as the failure it is.
    """
    expected: str | None = None
    published: str | None = None
    made = 0
    for attempt in range(1, max(1, attempts) + 1):
        made = attempt
        deployment = newest_deployment(fetch_deployments(repo, runner), since)
        expected = str((deployment or {}).get("sha") or "") or None
        published = fetch_branch_sha(repo, branch, runner)
        if expected and published and expected == published:
            return {
                "status": "reached",
                "sha": published,
                "expected": expected,
                "attempts": made,
            }
        if attempt < attempts:
            sleep(delay)
    return {
        "status": "stale",
        "sha": published,
        "expected": expected,
        "attempts": made,
    }


def classify_cname(expected: str | None, deployed: str | None) -> str:
    """What the deployed CNAME says about the captured one.

    - `not-expected` — the branch carried no CNAME, so there is nothing to
      compare and nothing that can have been lost;
    - `match` — the deployed CNAME is the captured one;
    - `missing` — the CNAME was there and is not there any more;
    - `mismatch` — there is a CNAME, and it is a different one.
    """
    if expected is None:
        return "not-expected"
    if deployed is None:
        return "missing"
    if expected.strip() == deployed.strip():
        return "match"
    return "mismatch"


def verify_cname(
    repo: str,
    branch: str,
    expected: str | None,
    runner: Runner | None = None,
    since: str | None = None,
    attempts: int = DEFAULT_ATTEMPTS,
    delay: float = DEFAULT_DELAY,
    sleep: Any = time.sleep,
) -> dict[str, Any]:
    """The CNAME of the deployed revision, and what it says about the capture.

    The capture answers one question before anything is read: was there a
    CNAME at all? A branch that carried none has nothing the deploy could
    have taken with it, and nothing to compare — the check answers that from
    the capture and stops, without waiting for a revision that would only
    confirm what the capture already said.
    """
    runner = runner if runner is not None else gh_api
    if expected is None:
        return {
            "status": "not-expected",
            "expected": None,
            "deployed": None,
            "attempts": 0,
        }
    state = wait_for_deployed_revision(
        repo,
        branch,
        runner,
        since=since,
        attempts=attempts,
        delay=delay,
        sleep=sleep,
    )
    if state["status"] != "reached":
        return {**state, "status": "stale"}
    deployed = fetch_file_content(repo, CNAME, branch, runner)
    return {
        **state,
        "status": classify_cname(expected, deployed),
        "deployed": deployed,
    }


# ── CLI ───────────────────────────────────────────────────────────────────────


def _expected_cname(path: str | None) -> str | None:
    """The captured CNAME, or None when there is no capture."""
    if not path:
        return None
    captured = pathlib.Path(path)
    if not captured.is_file():
        return None
    return captured.read_text(encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser(
        "verify-cname",
        help="wait for the deployed revision and check its CNAME",
    )
    verify.add_argument("--repo", required=True)
    verify.add_argument("--branch", default=DEFAULT_BRANCH)
    verify.add_argument("--expected-file", default=None)
    verify.add_argument("--since", default=None)
    verify.add_argument("--attempts", type=int, default=DEFAULT_ATTEMPTS)
    verify.add_argument("--delay", type=float, default=DEFAULT_DELAY)
    args = parser.parse_args(argv)

    try:
        result = verify_cname(
            args.repo,
            args.branch,
            _expected_cname(args.expected_file),
            since=args.since,
            attempts=args.attempts,
            delay=args.delay,
        )
    except LinkError as exc:
        print(f"allure-deploy error: {exc}", file=sys.stderr)
        print(json.dumps({"status": "api-error", "error": str(exc)}, sort_keys=True))
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0 if result.get("status") in {"match", "not-expected"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
