#!/usr/bin/env python3
"""allure_report_link.py — link the published Allure report on the merged PR and its issues.

A published report nobody is told about is a report nobody opens. The
publication job knows the report URL; the PR that caused it and the issues it
closes do not. This helper carries the URL from the publication to them.

What it does, in order:

- find the pull request the published commit belongs to
  (`GET /repos/{owner}/{repo}/commits/{sha}/pulls`);
- read the issues that PR closes, from the closing keywords in its body;
- post one comment on the PR and on each of those issues, with the exact run
  report and the latest report of the population;
- update that comment on the next run instead of adding another one: the
  comment carries a marker, and the marker is what finds it again.

The comment that is updated is the one this workflow wrote: the marker is
hidden, and a person who quoted it into a comment of their own owns that
comment. A marked comment of somebody else is left alone, and the next run
posts a comment of our own beside it.

The workflow runs the helper for a push only. A scheduled run publishes the
`full` population from the tip of the default branch — the pull request that
merged that commit is long closed, and its marker comment would be taken over
by a run that has nothing to do with it.

The comment is a convenience, not a gate: the workflow runs it with
`continue-on-error`, because a report that was published must not be
un-published because a comment did not post. A target that cannot be reached
— an issue number that names nothing — is recorded as failed, and the other
targets still get their comment.

The address the comment carries is the address the Pages site answers with.
When the settings do not answer, the address is taken from the
configuration (`--site-url`, or `ALLURE_REPORT_SITE_URL`), and when there is
no configuration either, no comment is posted: a made-up address is a link
that leads nowhere.

Everything the helper decides is decided by pure functions; the GitHub
access is the `gh` CLI, and the tests drive it with a fake runner.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import sys
from typing import Any, Iterable, NamedTuple

# The helpers of this directory are imported as modules, not as a package:
# the workflow runs this file as a script, and the tests load it by path.
_CI_DIR = pathlib.Path(__file__).resolve().parent
if str(_CI_DIR) not in sys.path:
    sys.path.insert(0, str(_CI_DIR))

from github_api import (  # noqa: E402
    LinkError,
    NotFound,
    Runner,
    documents as json_documents,
    gh_api,
    json_document,
)

#: The marker that finds our own comment again. It is hidden in the rendered
#: comment and it is the only thing that identifies it.
MARKER = "<!-- allure-report-link -->"

#: The address of the published site, when it is configured on purpose:
#: the Pages settings are asked first, and this is what is used when they
#: do not answer.
SITE_URL_ENV = "ALLURE_REPORT_SITE_URL"

#: The account the publication workflow writes as. Only the comments of this
#: account are ours to update: the marker is hidden, and a person who copied
#: it into a comment of their own still owns that comment.
BOT_LOGIN = "github-actions[bot]"

#: A closing keyword and the issue numbers it closes. GitHub's own set:
#: close/closes/closed, fix/fixes/fixed, resolve/resolves/resolved. `Refs` is
#: not one of them — it names an issue, it does not close it.
#:
#: The colon is GitHub's own: `Closes: #10` closes the issue just like
#: `Closes #10` does, and so does the uppercase form. A list belongs to the
#: keyword that names it: `Closes #1, #2` closes both — the merge of
#: gazon1/sing#275 closed all four issues its body named that way.
CLOSING_KEYWORDS = re.compile(
    r"\b(?:clos(?:e[sd]?)|fix(?:e[sd]?)|resolv(?:e[sd]?))\b[ \t]*:?[ \t]*#\d+(?:[ \t]*,[ \t]*#\d+)*",
    re.IGNORECASE,
)

#: The fence that opens and closes a code block.
CODE_FENCE = re.compile(r"^\s*(?:```|~~~)")

#: A quoted line: the text somebody else wrote, quoted back.
QUOTE = re.compile(r"^\s*>")

#: An inline code span — the double-backtick form first, so a span that holds
#: a backtick of its own is not cut in half.
CODE_SPAN = re.compile(r"``[^`]*``|`[^`\n]*`")


# ── The decisions ─────────────────────────────────────────────────────────────


def prose(body: str) -> str:
    """The body without the parts that are not a statement about an issue.

    A closing keyword inside a code block, inside an inline code span or in a
    quoted line is not a closing keyword: it is an example, or the text of
    somebody else. The helper comments on a public tracker, so a keyword that
    only looks like one is noise, and noise is worse than a missing comment.
    """
    kept: list[str] = []
    in_code = False
    for line in (body or "").splitlines():
        if CODE_FENCE.match(line):
            in_code = not in_code
            continue
        if in_code or QUOTE.match(line):
            continue
        kept.append(line)
    return CODE_SPAN.sub("", "\n".join(kept))


def parse_closing_issues(body: str) -> list[int]:
    """The issues a pull-request body closes.

    Only a closing keyword counts, and only the numbers that follow it: in
    `Fixes #2729 and implements #2737` the keyword closes `#2729` and the
    other number is prose. A keyword may close a list — `Closes #1, #2` — and
    the list belongs to that keyword alone. The colon is GitHub's own form:
    `Closes: #10` closes the issue just like `Closes #10` does.

    A keyword closes what is on its own line: `Closes` at the end of a line
    and `#10` at the start of the next one are two statements, not one.
    """
    numbers: list[int] = []
    for match in CLOSING_KEYWORDS.finditer(prose(body)):
        for number in re.findall(r"#(\d+)", match.group(0)):
            value = int(number)
            # Issue numbers start at one; `#0` names nothing.
            if value > 0 and value not in numbers:
                numbers.append(value)
    return sorted(numbers)


def site_url(url: str) -> str:
    """The site URL without its trailing slash."""
    return url.rstrip("/")


def report_links(base_url: str, population: str, run_number: str) -> tuple[str, str]:
    """The exact run report and the latest report of a population."""
    return (
        f"{base_url}/{population}/{run_number}/index.html",
        f"{base_url}/{population}/index.html",
    )


def comment_body(run_url: str, latest_url: str) -> str:
    """The comment body: the two links, and the marker that finds it again."""
    return (
        f"{MARKER}\n"
        "Allure report of this run:\n"
        f"{run_url}\n\n"
        "Latest report:\n"
        f"{latest_url}\n"
    )


def is_our_comment(comment: dict[str, Any]) -> bool:
    """Whether the comment was written by our own bot."""
    return str((comment.get("user") or {}).get("login") or "") == BOT_LOGIN


def _comment_key(comment: dict[str, Any]) -> tuple[str, int]:
    """The order of two comments: the newer one comes last."""
    created = str(comment.get("created_at") or "")
    try:
        identifier = int(comment.get("id") or 0)
    except (TypeError, ValueError):
        identifier = 0
    return created, identifier


def find_marker_comment(comments: Iterable[dict[str, Any]]) -> dict[str, Any] | None:
    """Our own comment that carries the marker, if there is one.

    A comment that carries the marker and was written by somebody else is
    not ours to overwrite: the marker is hidden, and a person who quoted it
    into a comment of their own owns that comment. Such a comment leaves us
    without a comment of our own, and the next run posts a new one.

    When several of our own comments carry the marker, the newest one wins,
    so the choice does not depend on the order the API answered in.
    """
    ours = [
        comment
        for comment in comments
        if isinstance(comment, dict)
        and MARKER in (comment.get("body") or "")
        and is_our_comment(comment)
    ]
    if not ours:
        return None
    return max(ours, key=_comment_key)


# ── GitHub ────────────────────────────────────────────────────────────────────


class PagesSite(NamedTuple):
    """What the Pages settings say about the site.

    `url` is the address the site answered with, and `status` says how
    that address came to be known:

    - `confirmed` — the settings answered with the URL of the site. The
      site may have a custom domain and it may not; the answer is the
      site's own, and it is right either way;
    - `not-configured` — the settings answered, and there is no site to
      speak of;
    - `unavailable` — the settings did not answer. The URL of a site that
      was never asked for is a guess, and a guess in a comment on a public
      tracker is a link that leads nowhere.
    """

    url: str | None
    status: str


def fetch_pages_site(repo: str, runner: Runner) -> PagesSite:
    """The Pages site of the repository, and how it answered."""
    try:
        data = json_document(runner, [f"repos/{repo}/pages"])
    except NotFound:
        return PagesSite(None, "not-configured")
    except LinkError:
        return PagesSite(None, "unavailable")
    if isinstance(data, dict):
        url = data.get("html_url")
        if isinstance(url, str) and url:
            return PagesSite(site_url(url), "confirmed")
    return PagesSite(None, "not-configured")


def resolve_site_url(site: PagesSite, configured: str | None = None) -> str | None:
    """The address the report is published at, or None when it is unknown.

    The confirmed address of the site wins. When the site did not answer,
    the address is taken from the configuration — the one somebody wrote
    down on purpose — and when there is no configuration either, there is
    no address: a link that is made up is worse than no link.
    """
    if site.status == "confirmed" and site.url:
        return site.url
    if configured:
        return site_url(configured)
    return None


def fetch_merged_pull_requests(repo: str, sha: str, runner: Runner) -> list[dict[str, Any]]:
    """The pull requests the published commit belongs to."""
    data = json_document(runner, [f"repos/{repo}/commits/{sha}/pulls"])
    if not isinstance(data, list):
        return []
    return [item for item in data if isinstance(item, dict)]


def fetch_comments(repo: str, number: int, runner: Runner) -> list[dict[str, Any]]:
    """The comments of an issue or a pull request."""
    documents = json_documents(runner([f"repos/{repo}/issues/{number}/comments", "--paginate"]))
    comments: list[dict[str, Any]] = []
    for document in documents:
        if isinstance(document, list):
            comments.extend(item for item in document if isinstance(item, dict))
    return comments


def upsert_comment(repo: str, number: int, body: str, runner: Runner) -> str:
    """Post the comment, or update the one that is already there.

    Only a comment of our own bot is updated. A comment somebody else wrote
    is left alone, and a new comment is posted beside it.
    """
    existing = find_marker_comment(fetch_comments(repo, number, runner))
    if (
        existing is not None
        and existing.get("id") is not None
        and is_our_comment(existing)
    ):
        json_document(
            runner,
            [
                "--method",
                "PATCH",
                f"repos/{repo}/issues/comments/{existing['id']}",
                "-f",
                f"body={body}",
            ],
        )
        return "updated"
    json_document(
        runner,
        [
            "--method",
            "POST",
            f"repos/{repo}/issues/{number}/comments",
            "-f",
            f"body={body}",
        ],
    )
    return "created"


# ── The whole thing ───────────────────────────────────────────────────────────


def post_report_link(
    repo: str,
    population: str,
    run_number: str,
    sha: str,
    runner: Runner = gh_api,
    configured_url: str | None = None,
) -> dict[str, Any]:
    """Link the published report on the merged PR and on the issues it closes."""
    site = fetch_pages_site(repo, runner)
    base = resolve_site_url(site, configured_url)
    pull_requests = fetch_merged_pull_requests(repo, sha, runner)
    if not pull_requests:
        return {"status": "no-pull-request", "site": base, "comments": []}
    if base is None:
        # The address of the site is unknown. A link that is made up is
        # worse than no link: it is a comment on a public tracker that
        # leads nowhere, and it cannot be taken back.
        return {
            "status": "no-site-url",
            "site": None,
            "site_status": site.status,
            "comments": [],
        }
    run_url, latest_url = report_links(base, population, run_number)
    body = comment_body(run_url, latest_url)
    pull_request = pull_requests[0]
    number = int(pull_request.get("number") or 0)
    issues = parse_closing_issues(str(pull_request.get("body") or ""))

    comments: list[dict[str, Any]] = []
    for target in [number, *issues]:
        try:
            action = upsert_comment(repo, target, body, runner)
        except LinkError as exc:
            # One unreachable target — an issue that does not exist, a number
            # that names nothing — must not cost the other targets their
            # comment. The report is published either way.
            comments.append({"target": target, "action": "failed", "error": str(exc)})
        else:
            comments.append({"target": target, "action": action})
    return {
        "status": "commented" if any(c["action"] != "failed" for c in comments) else "failed",
        "site": base,
        "pull_request": number,
        "issues": issues,
        "comments": comments,
    }


# ── CLI ───────────────────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--population", required=True)
    parser.add_argument("--run-number", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--repo", default=os.environ.get("GH_REPO", ""))
    parser.add_argument(
        "--site-url",
        default=os.environ.get(SITE_URL_ENV) or None,
        help=(
            "the address of the published site, used when the Pages "
            f"settings do not answer (or ${SITE_URL_ENV})"
        ),
    )
    args = parser.parse_args(argv)

    if not args.repo or "/" not in args.repo:
        print("allure-report-link: no repository (--repo or GH_REPO)", file=sys.stderr)
        return 2
    try:
        result = post_report_link(
            args.repo,
            args.population,
            args.run_number,
            args.sha,
            configured_url=args.site_url,
        )
    except LinkError as exc:
        print(f"allure-report-link error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 1 if result.get("status") in {"failed", "no-site-url"} else 0


if __name__ == "__main__":
    raise SystemExit(main())
