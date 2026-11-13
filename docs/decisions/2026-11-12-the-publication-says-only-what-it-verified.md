---
title: The publication says only what it verified
date: 2026-11-12
status: open
tags: [ci, allure, github-pages]
supersedes: 2026-11-12-the-published-report-is-linked-on-the-merged-pr
---

# Context

Issue [#2853](https://github.com/gazon1/sing/issues/2853) collects the follow-ups
found after the first publication under the new publication contract (#2764). Three
of them are about what the publication *says* rather than about what it publishes.

**The comment belongs to somebody.** The helper found its comment by a hidden
marker and patched it. The marker is hidden — and a person who quoted it into a
comment of their own owns that comment. A patch over somebody else's comment is an
edit of a person, and GitHub shows it as one.

**The check after the deploy read the wrong revision.** The custom domain lives in
a CNAME file at the root of the `gh-pages` branch, and the deploy replaces the
branch contents with the publish tree — so the CNAME has to be read back afterwards.
The read went to the GitHub Contents API, and the API may still be serving the
*previous* revision of the branch for a moment after the push: the CNAME of the
previous revision says nothing about the revision that was published. The check
therefore proved something about the wrong tree, and a CNAME the deploy had dropped
read as a CNAME that was never there.

**The address of the site was guessed.** The helper read `html_url` from the Pages
settings and, when the settings could not be read, fell back to
`https://<owner>.github.io/<repo>`. The fallback is a guess: it is wrong for a
repository published under a custom domain, and it is exactly the case the settings
could not be read *because* the site is published elsewhere. The guess went into a
comment on a public tracker.

# Idea

1. **Only our own comment is ours.** A comment is updated when the bot wrote it.
   A comment of somebody else with the same marker is left alone, and the next run
   posts a comment of our own beside it.
2. **The choice between several of our own comments must not depend on the order
   the API answered in.** The newest one wins.
3. **The check after the deploy waits for the revision it verifies.** The branch
   is read until it holds the commit the deployment created, and only then is the
   CNAME read.
4. **The three failures are told apart.** A stale revision, a CNAME that is gone,
   and an API that did not answer are three different things.
5. **The address of the site is confirmed or configured — never guessed.** The
   Pages settings answer with the address of the site; when they do not answer, the
   address is taken from the configuration; when there is no configuration either,
   no comment is posted.

# Decision

**A comment is updated only when the bot wrote it.** The helper looks for a
comment that carries the marker *and* whose author is `github-actions[bot]`. When
several of our own comments carry the marker, the newest one wins — by
`created_at`, then by id, so the choice does not depend on the order the API
answered in. A comment of a person (or of another bot) is never edited: the helper
posts a new comment beside it.

**The check after the deploy waits for the deployed revision.** Before the publish,
the workflow records the identifiers of the Pages deployments that exist; the
helper then reads the Pages deployments and looks for the one whose identifier
the record does not name — that one is the deployment of this publication. It
then reads `refs/heads/gh-pages` until the branch holds that deployment's
commit — twenty reads at five seconds apart — and only then reads the CNAME of
the branch.

The deployment is named by its identifier and not by the moment it was created:
the API answers `created_at` with a second of precision, so the previous
publication may have finished in the very second this one started, and a
deployment "created since the deploy started" may be the previous one. A
snapshot that cannot be read is a snapshot that names nothing, and a snapshot
that names nothing would make every deployment a candidate — the check refuses
to run without one.

**The failures are told apart.** `stale` — the branch is not the revision that was
deployed, and the CNAME of that revision proves nothing. `missing` — the CNAME was
there and is not there any more: the deploy dropped it. `mismatch` — there is a
CNAME and it is a different one. `not-expected` — the branch carried no CNAME, so
there is nothing that can have been lost. An API that does not answer is not a
state of the branch: it is raised as the failure it is, and the command exits
non-zero with it.

**The address of the site is confirmed or configured.** The Pages settings are read
first: an answer with an address is `confirmed`, and that address is the one. A
404 is `not-configured` — there is no site to speak of. Anything else — 403, a 5xx,
a network error — is `unavailable`, and the settings did not answer. When the site
is not confirmed, the address is taken from the configuration (`--site-url` or
`ALLURE_REPORT_SITE_URL`), and when there is no configuration either, **no comment
is posted**: a made-up address is a link that leads nowhere.

**The rest of the contract is unchanged.** The link goes on the merged pull
request and on the issues it closes (`Closes|Fixes|Resolves` with the numbers that
follow). The step runs on a push only, because a scheduled run publishes the tip of
the default branch and the pull request that merged it is long closed. The step is
advisory (`continue-on-error`): a report that was published must not be
un-published because a comment did not post. One unreachable target does not cost
the other targets their comment.

**A closing keyword inside code is an example, not a closing keyword.** A fenced
code block is recognised by its opening fence and closed only by a fence of the same
marker that is at least as long, so a fence nested in a block is ordinary content and
a block that is never closed stays open to the end of the text. The block leaves a
blank line behind: the text before it and the text after it are two paragraphs, and
without the blank line a line of four spaces after the block would look like the
continuation of the paragraph before it. Four spaces of indent make a code block as
well, and they are counted from the indent of the enclosing list item — the content
of a list item starts after its marker, so a line indented four spaces further than
that is a paragraph of the item and not a block of code.

**The GitHub access stays `gh api`.** The migration of the git operations to
GitPython (see
`2026-11-12-the-git-operations-are-gitpythons.md`) is a migration of the git
operations. The GitHub API is a different surface, and moving it would turn a fix
of the publication into a migration of the GitHub client. The access stays behind
one client (`scripts/ci/github_api.py`) that the tests drive with a fake runner.

# Rationale

- A comment of a person is the person's. The marker is hidden, and a person who
  quoted it into a comment of their own did not give the comment away — and the
  next run posts beside it, so the report is still linked.
- A choice that depends on the order an API answered in is a choice nobody can
  reproduce. The newest of our own comments is the one the last run wrote.
- A check that reads the wrong revision is not a check. The wait costs a few
  seconds and turns "the CNAME is there" into a statement about the revision that
  was published.
- The three failures need different answers: a stale revision is a retry, a
  missing CNAME is a publication bug, and an API that did not answer is neither.
- The repository is published under `maxdrobin.ru`, and the `gh-pages` branch
  carries no CNAME at the moment. The guess `owner.github.io/repo` would have put
  a link to a site that does not exist into a comment on a public tracker.
- A confirmed address and a configured address are both somebody's decision. A
  guess is nobody's.

# Consequences

- The publication job reads the Pages deployments and the branch ref after the
  deploy. Reading the deployments needs the `deployments: read` scope — the API
  answers 403 without it, and the check fails every publication. The branch ref
  and the CNAME are read with the `contents` scope the job already holds.
- The post-deploy check takes up to a minute and a half when the API is slow to
  catch up with the push: the deployment appears in the API some seconds after
  the push, and the branch catches up with it a moment later.
- A repository whose Pages settings cannot be read publishes its report and posts
  no link, unless `ALLURE_REPORT_SITE_URL` is set. That is a configuration the
  repository does not set today: the settings answer for `gazon1/sing`.
- Several of our own marker comments on one target mean the earlier ones stay.
  They are ours, but they are also a record of the runs that wrote them.
- The helper still needs the `gh` CLI on the runner.

# Links

- Issues: [#2853](https://github.com/gazon1/sing/issues/2853)
- Code: `scripts/ci/allure_report_link.py`, `scripts/ci/allure_deploy.py`,
  `scripts/ci/github_api.py`, `scripts/tests/test_allure_report_link.py`,
  `scripts/tests/test_allure_deploy.py`,
  `scripts/tests/test_allure_publication_workflow.py`,
  `.github/workflows/ci.yml`
- Supersedes:
  `2026-11-12-the-published-report-is-linked-on-the-merged-pr`
- Related: `2026-11-12-the-git-operations-are-gitpythons.md`
