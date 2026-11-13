---
title: The published report is linked on the merged PR and on the issues it closes
date: 2026-11-12
status: superseded
tags: [ci, allure, github-pages]
superseded-by: 2026-11-12-the-publication-says-only-what-it-verified
---

> **Superseded (2026-11-12):** the contract this decision describes — where the
> link comes from and what happens when the Pages settings cannot be read — is
> replaced by
> [2026-11-12-the-publication-says-only-what-it-verified.md](2026-11-12-the-publication-says-only-what-it-verified.md).
> The address of the site is no longer guessed when the settings do not answer,
> and the comment is no longer edited when somebody else wrote it. The rest of
> this decision — the link goes on the merged pull request and on the issues it
> closes, and the step is advisory — still holds. The scopes it names are
> corrected below: the first real publication showed that a comment on a pull
> request needs the scope of a pull request.

# Context

Issue [#2853](https://github.com/gazon1/sing/issues/2853) collects the follow-ups found
during the verification of the first publication under the new publication contract
(#2764). One of them is that the report is published and nobody is told about it.

The publication job knows the URL of the report it published. The pull request that
caused the publication and the issues that pull request closes do not: the report is
reachable through the root index and through the population index, and a reader who
does not know to look there does not find it. The verification of the first publication
was done by hand, by opening the site and reading the index — a thing a human does once
and a machine does not.

The issue asks for the report link to be posted on the merged pull request and on every
issue named by a closing keyword (`Closes|Fixes|Resolves`) in that pull request's body,
and for the comment to be updated on the next run instead of piling up.

# Idea

1. **Where the link comes from.** The publication job knows the run number, the
   population and the commit; the Pages site knows its own URL. Read the URL from the
   Pages settings, and fall back to the repository's own Pages URL when the settings
   cannot be read.
2. **Which pull request.** Find it through the published commit
   (`GET /repos/{owner}/{repo}/commits/{sha}/pulls`). A scheduled run publishes the
   tip of the default branch, and the pull request that merged that commit is long
   closed — commenting on it would take over the marker comment of a pull request
   that has nothing to do with the run.
3. **Which issues.** The closing keywords in the pull request's body. `Refs` is not a
   closing keyword: it names an issue, it does not close it, and commenting on every
   issue somebody referenced would be spam.
4. **One comment or many.** A comment carries a hidden marker, and the next run
   updates the comment that carries it. A rerun and a nightly therefore leave one
   comment, not two.
5. **Where the code lives.** In the publication helper, or beside it. The publication
   helper (`scripts/ci/allure_pages.py`) owns the publication contract and touches no
   network at all; the comment needs GitHub. A separate helper
   (`scripts/ci/allure_report_link.py`) keeps both properties.
6. **When a comment fails.** A comment that does not post must not un-publish a report
   that was published.

# Decision

The report link is posted by a new helper, `scripts/ci/allure_report_link.py`, from the
publication job after the deploy.

**The site URL** comes from `html_url` of `GET /repos/{owner}/{repo}/pages`, with
`https://<owner>.github.io/<repo>` as the fallback. A token that may not read the Pages
settings is not a failure: the fallback is where the report is published anyway.

**The pull request** is the one the published commit belongs to. The step runs for a
push only: a push publishes the merge of a pull request, and the pull request is the
one that caused the run. A scheduled or a dispatched run publishes the tip of the
default branch, whose pull request is long closed; its marker comment belongs to
that pull request, and a nightly must not take it over.

**The issues** are the ones the pull request's body closes. A closing keyword closes
the number that follows it and, optionally, a comma-separated list with it:
`Fixes #2729 and implements #2737` closes `#2729` alone. GitHub's own forms are
the ones the helper reads: the colon (`Closes: #10`) and the uppercase form
(`CLOSES #10`) close just like `Closes #10`, and a keyword in a code block, in an
inline code span or in a quoted line is an example or a quotation, not a closing
keyword.

**The comment** carries the exact run report (`<site>/<population>/<run_number>/index.html`),
the latest report of the population (`<site>/<population>/index.html`), and the hidden
marker `<!-- allure-report-link -->`.

**The comment is upserted.** The helper looks for a comment that carries the marker —
the bot's own comment first — and patches it; when there is none, it posts one.

**The job's permissions** are the ones this needs and no more: `issues: write` for the
comments, `pull-requests: write` to find the pull request and to write the comment on
it, `pages: read` to read the site URL, next to the `contents: write` and
`actions: read` the publication already had. The scope of the pull requests was
`read` here at first, and the first real publication answered 403 with it: a comment
on a pull request is not a comment on an issue, and the token may not write it
without the scope of a pull request.

**A failing comment does not fail the publication.** The step runs with
`continue-on-error`, and it says so in its name: a soft step nobody declared is a
step that reads as a gate.

**One unreachable target does not cost the others their comment.** A target that
cannot be reached — an issue number that names nothing — is recorded as failed in
the helper's own result, and the helper goes on to the next target. Only a run in
which every target failed is a failed run.

**The decisions are pure functions.** Closing-keyword parsing, URL building, the
comment body and the choice between patching and posting are decided without a
network; the tests drive the GitHub access with a fake `gh` runner.

# Rationale

- A published report that nobody is told about is a report nobody opens. The link
  belongs where the reader is: on the pull request that caused the run, and on the
  issue the pull request closes.
- `Refs` is not a closing keyword, and treating it as one would comment on every
  issue a pull request ever mentioned. The repository's convention is `Closes #123`
  and `Fixes #123`.
- One comment per target is a comment a reader can read. A marker comment that is
  updated is one; a comment per run is a wall of links that hides the one that matters.
- A push is the only run with a pull request behind it. A scheduled run publishes the
  tip of the default branch, and the pull request that merged it has been closed for
  weeks; its marker comment is the record of that pull request's report, and a
  nightly that takes it over loses the report that pull request produced.
- The comment is a convenience, not a gate. Failing the publication because a comment
  did not post would trade a published report for an unposted comment.
- Reading the site URL from the Pages settings keeps the link correct when the site
  is published under a custom domain. The fallback is a guess, but it is the URL the
  report is published at when the settings cannot be read.
- Pure functions with a fake runner keep the rules testable without a network, and
  keep the GitHub access in one place.

# Consequences

- The publication job writes outside the Pages branch: it comments on issues and pull
  requests. The permissions it holds are wider than they were, and every one of them
  is named for a call this job makes.
- A pull request that closes an issue gets the report link on both. The comment on the
  issue is the same body, and the same marker, so the next run updates both.
- A scheduled or a dispatched run posts nothing: the step does not run for it. The
  `full` population is published on a schedule, and its report is reached through the
  root index.
- A closing keyword in a code block, in an inline code span or in a quoted line closes
  nothing here. A keyword that only looks like one would put a link on an issue the
  pull request did not close, and a link on the wrong issue is spam.
- A run whose commit belongs to several pull requests comments on the first one. The
  endpoint answers with the pull requests associated with the commit, and the merged
  one is the one that matters.
- The helper needs the `gh` CLI on the runner. It is part of the runner image.
- A cross-repository keyword (`KEYWORD owner/repo#123`) closes nothing here. The
  job holds no permission on another repository, so the comment is one it cannot
  place; the issue in this repository is not closed by it either.
- The comment body is written in English and names the population; it is a link, not
  a report.

# Links

- Issues: [#2853](https://github.com/gazon1/sing/issues/2853),
  [#2756](https://github.com/gazon1/sing/issues/2756)
- Code: `scripts/ci/allure_report_link.py`, `scripts/tests/test_allure_report_link.py`,
  `.github/workflows/ci.yml`
- Prior decisions:
  `docs/decisions/2026-11-10-allure-publication-latest-history-and-retention.md`,
  `docs/decisions/2026-11-11-the-publication-helper-owns-retention.md`
