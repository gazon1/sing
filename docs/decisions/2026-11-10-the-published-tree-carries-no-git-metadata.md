---
title: The published Allure tree carries no git metadata
date: 2026-11-10
status: accepted
tags: [ci, allure, github-pages]
---

# Context

The `JVM tests` job publishes a live Allure report to the `gh-pages` branch
(added in `49d443eef`). It has never worked. Every push to `main` since it was
added ended with the same red step:

- `bb5bbf41c` — run `42723163611`, job `JVM tests`, step 29 *Publish Allure
  report to GitHub Pages* — failure;
- `570da8a57` — run `42764631210`, job `JVM tests`, step 29 — failure.

The log names the cause twice. The publish step ends with

```
error: src refspec gh-pages does not match any
error: failed to push some refs to 'https://github.com/gazon1/sing.git'
```

and the commit it is trying to push was made on the wrong branch:

```
[master (root-commit) b482a7a] deploy: 570da8a57...
```

In that run every test step of the job was green — *Gradle (tests)*, *Gradle
(coverage)*, *Coverage floors*, *Kover verify (detekt-rules)*, *Gates are wired
and can fail* — and the publish step failed at step 29; the two upload steps
after it succeeded.

The path there is a chain of three steps, and each link was verified in the
sources of the actions involved:

1. **The history is loaded, and it brings a repository with it.**
   `simple-elf/allure-report-action` starts with
   `cp -r ./${INPUT_GH_PAGES}/. ./${INPUT_ALLURE_HISTORY}` — it copies the
   *whole* history directory into the directory that will be published, `.git`
   included. `cp -r` without `-p` gives the copies the ownership of the copying
   process, and that process is the container's: the action's `Dockerfile` has
   no `USER` directive, so it runs as **root**.
2. **The publish step cannot delete that `.git`.**
   `peaceiris/actions-gh-pages` removes `publish_dir/.git` before copying, with
   `rm -rf`. The removal fails with `EACCES`: the files belong to root, and the
   publish step runs as the runner user. The `.git` survives.
3. **The surviving `.git` is copied over the publish step's own clone.**
   `copyAssets` copies the tree with `cp -RfL`, and `-f` overwrites what is
   there: the clone's `.git/HEAD` is replaced by the copy's — and the copy's
   `HEAD` says `ref: refs/heads/master`, the name `actions/checkout` gave the
   history clone. The commit lands on `master`, and `git push origin gh-pages`
   has nothing to push.

The `.git` was there from the start: the checkout of `gh-pages` could not
succeed — at the time of those runs the branch did not exist — but
`actions/checkout` initialises a repository before it fetches, and the fetch
failed three times, twenty seconds apart. What was left was an initialised but
empty `.git`, and the copy of it is what the publish step could not delete.

# Idea

The published tree must not contain git metadata. The history is a set of
files, not a repository.

# Decision

**The history is downloaded as a tarball, not cloned.**

The step fetches
`https://api.github.com/repos/${GITHUB_REPOSITORY}/tarball/gh-pages` and
unpacks it into `gh-pages/`. The tarball of a branch carries the branch's files
and no `.git`, so the tree that reaches `peaceiris` is clean, the commit lands
on `gh-pages`, and the branch is created by the first publish.

A download that fails leaves nothing behind: the step removes a half-written
tree and starts with an empty history. A truncated transfer is not a history.

The step stays advisory: a fresh trunk may have no `gh-pages` branch yet, and
a report without a trend line is not a broken build. The notice distinguishes
the two cases — a missing branch starts the history, any other failure
discards it and the next publish rebuilds the branch from the new run alone.

# Rationale

The alternative — keeping the clone and deleting its `.git` afterwards — does
not work for the runner user either: the copy inside the published tree
belongs to root, and unlinking it needs privileges the workflow step should
not reach for. The tarball removes the metadata at the source and needs
neither.

Cloning was also the slow part of the step: a missing branch costs three
retries of twenty seconds each before the step gives up.

# Consequences

- The `gh-pages` branch is created by the first successful publish. On this
  repository it already exists — created on 2026-11-10 with an empty
  placeholder, so that the site answers before the first real report.
- The history keeps accumulating: each publish leaves the previous report in the
  branch, and the next run downloads it again.
- The publish step needs `contents: write` on the job, which it already had.
- GitHub Pages has to be enabled on the repository for the report to be served;
  until then the branch holds the report and the run publishes the same report
  as an artifact.
- The download carries no `Authorization` header across the redirect to
  `codeload`, so a repository that turns private would silently fall back to
  the empty-history path.

# Links

- `49d443eef` — the commit that added the publication.
- `docs/decisions/2026-11-06-the-allure-report-is-generated-in-the-build.md` —
  where the report itself is generated.
- `scripts/check-gate-wiring.py` — the gate that requires a non-blocking step
  to declare itself advisory.
