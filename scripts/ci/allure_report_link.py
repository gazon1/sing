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

The workflow runs the helper for a push only. A scheduled run publishes the
`full` population without a pull request behind it, and the commit it
publishes is the tip of the default branch — the pull request that merged it
is long closed, and its marker comment would be taken over by a run that has
nothing to do with it.

The comment is a convenience, not a gate: the workflow runs it with
`continue-on-error`, because a report that was published must not be
un-published because a comment did not post.

Everything the helper decides is decided by pure functions; the GitHub
access is the `gh` CLI, and the tests drive it with a fake runner.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from typing import Any, Callable, Iterable

#: The marker that finds our own comment again. It is hidden in the rendered
#: comment and it is the only thing that identifies it.
MARKER = "<!-- allure-report-link -->"

#: A closing keyword and the issue numbers it closes. GitHub's own set:
#: close/closes/closed, fix/fixes/fixed, resolve/resolves/resolved. `Refs` is
#: not one of them — it names an issue, it does not close it.
#:
#: The colon is GitHub's own: `Closes: #10` closes the issue just like
#: `Closes #10` does, and so does the uppercase form. A list belongs to the
#: keyword that names it: `Closes #1, #2` closes both — the merge of
#: gazon1/sing#275 closed all four issues its body named that way.
CLOSING_KEYWORDS = re.compile(
    r"\b(?:clos(?:e[sd]?)|fix(?:e[sd]?)|resolv(?:e[sd]?))\b\s*:?\s*#\d+(?:\s*,\s*#\d+)*",
    re.IGNORECASE,
)

#: The fence that opens and closes a code block.
CODE_FENCE = re.compile(r"^\s*(?:```|~~~)")

#: A quoted line: the text somebody else wrote, quoted back.
QUOTE = re.compile(r"^\s*>")

#: An inline code span.
CODE_SPAN = re.compile(r"`[^`\n]*`")

#: The runner that talks to GitHub: a list of `gh api` arguments, and the
#: response body. The tests replace it with a fake.
Runner = Callable[[list[str]], str]


class LinkError(Exception):
    """A failure to read from or write to GitHub."""


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
    """
    numbers: list[int] = []
    for match in CLOSING_KEYWORDS.finditer(prose(body)):
        for number in re.findall(r"#(\d+)", match.group(0)):
            value = int(number)
            if value not in numbers:
                numbers.append(value)
    return sorted(numbers)


def site_url(html_url: str | None, owner: str, repo: str) -> str:
    """The published site's base URL.

    The Pages site knows its own URL. When it does not answer, the
    repository's own Pages URL is the fallback — it is where the report is
    published either way.
    """
    if html_url:
        return html_url.rstrip("/")
    return f"https://{owner}.github.io/{repo}"


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


def find_marker_comment(comments: Iterable[dict[str, Any]]) -> dict[str, Any] | None:
    """The comment that already carries the marker, if there is one.

    The bot's own comment wins: a comment that carries the marker and was
    written by somebody else is not ours to overwrite.
    """
    marked = [
        comment
        for comment in comments
        if isinstance(comment, dict) and MARKER in (comment.get("body") or "")
    ]
    if not marked:
        return None
    bots = [
        comment
        for comment in marked
        if str((comment.get("user") or {}).get("login") or "").endswith("[bot]")
    ]
    return bots[0] if bots else marked[0]


# ── GitHub ────────────────────────────────────────────────────────────────────


def gh_api(args: list[str]) -> str:
    """Run `gh api` and return its response body."""
    result = subprocess.run(
        ["gh", "api", *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise LinkError((result.stderr or "").strip() or f"gh api {' '.join(args)} failed")
    return result.stdout


def _documents(text: str) -> list[Any]:
    """Every JSON document in a response body.

    `gh api --paginate` answers with one JSON array per page, concatenated.
    """
    decoder = json.JSONDecoder()
    documents: list[Any] = []
    index = 0
    while index < len(text):
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            break
        try:
            document, index = decoder.raw_decode(text, index)
        except json.JSONDecodeError as exc:
            raise LinkError(f"gh api answered with invalid JSON: {exc}") from exc
        documents.append(document)
    return documents


def _json(runner: Runner, args: list[str]) -> Any:
    """Run the runner and read one JSON document from its answer."""
    documents = _documents(runner(args))
    return documents[0] if documents else None


def fetch_pages_url(repo: str, runner: Runner) -> str | None:
    """The Pages site's own URL, or None when it does not answer.

    A token that may not read the Pages settings is not a failure of the
    comment: the fallback URL is where the report is published anyway.
    """
    try:
        data = _json(runner, [f"repos/{repo}/pages"])
    except LinkError:
        return None
    if isinstance(data, dict):
        url = data.get("html_url")
        if isinstance(url, str) and url:
            return url
    return None


def fetch_merged_pull_requests(repo: str, sha: str, runner: Runner) -> list[dict[str, Any]]:
    """The pull requests the published commit belongs to."""
    data = _json(runner, [f"repos/{repo}/commits/{sha}/pulls"])
    if not isinstance(data, list):
        return []
    return [item for item in data if isinstance(item, dict)]


def fetch_comments(repo: str, number: int, runner: Runner) -> list[dict[str, Any]]:
    """The comments of an issue or a pull request."""
    documents = _documents(runner([f"repos/{repo}/issues/{number}/comments", "--paginate"]))
    comments: list[dict[str, Any]] = []
    for document in documents:
        if isinstance(document, list):
            comments.extend(item for item in document if isinstance(item, dict))
    return comments


def upsert_comment(repo: str, number: int, body: str, runner: Runner) -> str:
    """Post the comment, or update the one that is already there."""
    existing = find_marker_comment(fetch_comments(repo, number, runner))
    if existing is not None and existing.get("id") is not None:
        _json(
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
    _json(
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
) -> dict[str, Any]:
    """Link the published report on the merged PR and on the issues it closes."""
    owner, _, name = repo.partition("/")
    base = site_url(fetch_pages_url(repo, runner), owner, name)
    run_url, latest_url = report_links(base, population, run_number)
    body = comment_body(run_url, latest_url)

    pull_requests = fetch_merged_pull_requests(repo, sha, runner)
    if not pull_requests:
        return {"status": "no-pull-request", "site": base, "comments": []}
    pull_request = pull_requests[0]
    number = int(pull_request.get("number") or 0)
    issues = parse_closing_issues(str(pull_request.get("body") or ""))

    comments = [{"target": number, "action": upsert_comment(repo, number, body, runner)}]
    for issue in issues:
        comments.append({"target": issue, "action": upsert_comment(repo, issue, body, runner)})
    return {
        "status": "commented",
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
        )
    except LinkError as exc:
        print(f"allure-report-link error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
