---
title: The legacy root reports are dropped from the publish tree
date: 2026-11-12
status: open
tags: [ci, allure, github-pages]
---

# Context

Issue [#2853](https://github.com/gazon1/sing/issues/2853) measures the Pages branch
after the first publication under the new contract: 254 425 602 bytes in 45 882 files,
of which 136 589 861 bytes in 25 210 files are the root-level reports and the shared
history the legacy layout left behind (`2807/`, `2808/`, `2810/`, `2819/`, `2832/` and
`last-history/`).

Nothing removes them. The report action copies the whole tree of the branch into the
publish tree on every run (`cp -r ./${GH_PAGES}/. ./${ALLURE_HISTORY}`), so the legacy
root was carried into every publication and grew the branch and every deploy with it.

`docs/decisions/2026-11-11-the-publication-helper-owns-retention.md` §7 kept them on
purpose: "The legacy reports are not removed, and they are not migrated into a
population namespace: they are published URLs, and rewriting them is a separate
decision." This record is that separate decision. It replaces §7 of that record; the
rest of it — who owns retention, what a damaged manifest means, what a report path may
be, how the state is written — stands. The publication contract already
allows it: legacy root URLs are legacy-only and "not guaranteed after bounded retention
or a history reset/rebuild that does not preserve them"
(`docs/decisions/2026-11-10-allure-publication-latest-history-and-retention.md` §5).

The root index linked the legacy reports, so removing them without touching the index
would leave links to reports that are gone.

# Idea

1. **Remove them, or keep them.** The contract permits removal; the branch is twice
   the size it needs to be, and every deploy pays for it.
2. **Where.** In the publication helper, as an idempotent migration, or as a shell
   step in the workflow.
3. **When.** Before the report action copies the tree, so the legacy root is never
   copied into the publish tree at all.
4. **What exactly.** The root numeric report directories and the root `last-history` —
   the legacy layout's shared history, which the population layout replaced with one
   history per namespace. Not the population namespaces, not the root index, not the
   `CNAME`.
5. **The root index.** It links the legacy reports while they exist; after the drop it
   must not link them any more.

# Decision

**The legacy root is dropped from the publish tree, before the report action runs.**

**The migration is a command of the publication helper**,
`allure_pages.py drop-legacy-root --site-dir <tree>`. It removes the root-level
numeric directories that hold a report (`legacy_report_dirs`), and the root
`last-history`. It is idempotent: a tree without a legacy root is left as it is. It
reports what it removed — the reports, the history, and the files and bytes that went
with them — so the publication job can record the change.

**The workflow runs it** after the history is bootstrapped and before the report
action, so the legacy root is never copied into the publish tree, and the numbers it
reports reach the job summary.

**The root index follows.** It is written with the publication state, from the tree
that is published, so after the drop it names the populations and no legacy report. A
folder that is not a report is not a report and is not removed.

**The `CNAME` stays.** The custom domain file is not the legacy root, and the publish
tree is checked against the `CNAME` the branch carried before the deploy: a `CNAME`
that is restored and then dropped takes the custom domain with it.

# Rationale

- The legacy root is dead weight. Nothing writes to it, nothing reads it, and the
  population layout replaced both its reports and its history.
- The contract already said so. "Legacy-only" means the URLs may disappear; keeping
  them forever because they are links is a second, unwritten contract.
- Removing them before the copy is the whole point: the copy is what made them
  permanent. Removing them afterwards would remove them from the branch and add them
  back on the next run.
- The helper owns the publication state, and the legacy root is part of that state.
  A shell step in the workflow would be a second implementation of a rule the helper
  already has (`legacy_report_dirs`).
- An idempotent migration is safe to run on every publication: it does nothing when
  there is nothing to do, and it cannot half-remove a report.
- The root index is written from the published tree, so it cannot link a report that
  is not there. That is a property of the existing rule, not a new one.

# Consequences

- The legacy root URLs (`/<run_number>/`) stop resolving. The ADR of 2026-11-10 permits
  it; links to them in old comments, issues and bookmarks stop working.
- The branch shrinks by the size of the legacy root — 136 589 861 bytes and 25 210
  files when this was written — and every deploy carries that much less.
- The root index stops linking the legacy reports. It names the populations, which is
  what is published after the drop.
- The root `last-history` — the legacy shared trend state — is gone. The mixed history
  was reset when the population namespaces were introduced, and the root history was
  never part of a population's trend state.
- The migration is idempotent and runs on every publication. The first publication
  after this change removes the legacy root from the branch.
- The report action still copies the whole tree of the branch into the publish tree.
  The tree it copies is now the bounded one: the retention window per population, the
  namespaces, the state.

# Links

- Issues: [#2853](https://github.com/gazon1/sing/issues/2853),
  [#2767](https://github.com/gazon1/sing/issues/2767),
  [#2756](https://github.com/gazon1/sing/issues/2756)
- Code: `scripts/ci/allure_pages.py`, `scripts/tests/test_allure_pages.py`,
  `.github/workflows/ci.yml`
- Prior decisions:
  `docs/decisions/2026-11-10-allure-publication-latest-history-and-retention.md`,
  `docs/decisions/2026-11-11-the-publication-helper-owns-retention.md`
