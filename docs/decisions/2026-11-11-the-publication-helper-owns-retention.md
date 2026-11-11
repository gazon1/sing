---
title: The publication helper owns retention, and it writes the publication state as one unit
date: 2026-11-11
status: accepted
tags: [ci, allure, github-pages]
---

# Context

Issue [#2774](https://github.com/gazon1/sing/issues/2774) asks for the Allure Pages
publication helper (`scripts/ci/allure_pages.py`) to become a small, safe publisher.
The publication contract it implements was agreed in
`docs/decisions/allure-publication-latest-history-and-retention.md`; this record
covers the parts of it that the contract left to the implementation: who owns
retention, what a damaged manifest means, what a report path may be, and how the
helper writes to disk.

Four problems made the helper unsafe in the sense the issue means.

1. **Retention had two owners.** The helper kept the newest 20 reports per
   population, and `simple-elf/allure-report-action@v1.15` kept 20 as well. The
   action prunes the population namespace itself when it holds more than
   `keep_reports + 1` entries, and it prunes the non-numeric entries first
   (`ls | sort -n | grep -v CNAME | head -n -N | xargs rm -rv`, and `sort -n` puts
   non-numeric names first). `allure-manifest.json`, the population `index.html` and
   `last-history` were therefore removed before any report was. A namespace with
   enough history lost its manifest on every run, and the helper then published
   against an empty manifest and kept no history at all.
2. **A damaged manifest was repaired silently.** The loader normalized `latest` to
   the stored record, accepted a manifest with unknown fields and dropped them on
   the next save, accepted `schema_version: true` as `1`, and truncated a float run
   number to an integer.
3. **Paths were joined without a policy.** A report path was only checked for
   emptiness, so a manifest could name `../escape` and the helper would join it
   into the publish tree.
4. **The filesystem was written as if it could not fail.** The bootstrap wiped the
   destination before it unpacked the fetched history, the tarball could hold links,
   devices and any number of bytes, and the manifest, the population index and the
   root index were written one after another: a failure in the middle left a state
   that recorded one thing and showed another.

The pipeline was also broken in CI while this was written down: every publish run
since 2026-11-10 failed, because the report action is a Docker action and leaves
the tree it builds owned by root, while the helper runs as the runner user.

# Idea

For each of the four questions:

1. **Retention.** Keep the action pruning and make the helper follow it, keep the
   helper pruning and raise the action's threshold, or let one of them own the
   window and make the other a no-op.
2. **A damaged manifest.** Repair what can be repaired and carry on, or refuse the
   manifest and say what is wrong with it.
3. **A report path.** Check each path where it is used, or state one rule and make
   every path that reaches the filesystem go through it.
4. **Writing to disk.** Write each file as safely as it can be written on its own,
   or write the state that means something only as a whole.

# Decision

## 1. The helper owns retention

The helper keeps the newest 20 publications per population, as the publication
contract requires. The report action does not prune: the workflow raises its
`keep_reports` threshold above the number of reports the helper keeps, so the
action's pruning never runs.

A second owner of the window is a second opinion nobody asked for: the two
disagreed about what a "report" is, and the action's answer deleted the manifest
that names them.

## 2. A manifest that does not hold together is an error

The manifest states its own invariants — the identity key matches the record, the
population matches the namespace, `latest` is the record that is stored — and a
manifest that breaks one of them is refused with the reason. Unknown fields are
refused instead of dropped, and the integers are strict.

The Pages branch holds the only copy of the publication state. A manifest that is
repaired in place hides the damage: the next run publishes from a state that
nobody has seen. An error stops the publication and names the file and the field.

A manifest of an older schema is migrated, explicitly and separately from
validation: schema version 1 had no `population` field, because one manifest
held the whole site and each record named its own population. A version 1
document is split into the namespaces it names, and the records of one
population become that namespace's manifest. A version the migration does not
know is refused, never reinterpreted.

## 3. One report-path policy

A report path is one safe segment: letters, digits, `.`, `_` and `-`, starting
with a letter or a digit. One function decides it, the model validator enforces
it, and every path that reaches the filesystem is joined through it. A path that
came out of a manifest therefore cannot address anything outside the namespace it
belongs to, whether it was written by the helper or read back from the branch.

## 4. A tree is replaced only by a complete replacement

The fetched history is unpacked into a staging directory and promoted over the
destination only when the whole tarball was unpacked and accepted. A failed
fetch leaves the destination as it was. The destination is emptied only in the one
case where emptiness is the correct state: the branch does not exist yet.

The tarball is accepted only if it holds regular files and directories, at most
500 000 members and at most 4 GiB, and only if no member path leaves the tree. A
link is refused: the history of this branch is a tree of reports, and a link is a
way out of the tree it is unpacked into.

The caps are sized against the branch, not against a guess. The branch holds the
legacy root-level reports (~25 000 files) plus the retention window: twenty
reports per population, ~4 900 files each — about 220 000 files and 1.2 GB, and
bounded there by the retention the helper owns. A cap that normal operation
reaches is not a safety feature; it is a pipeline that fails on the day the
history grows past it. That is what a 20 000 member cap was: the branch held
25 249 members, and the first publication after this change would have failed
on real data.

## 5. The publication state is written as one unit

The manifest, the population index and the root index describe one state. They
are written together, and when one of the writes fails the others are put back as
they were. The report directories that fall out of the retention window are
removed after the state is committed: a leftover directory is untidy, a
half-written state is wrong.

## 6. Retention is manifest-driven

The manifest names what was published, so the manifest is what says what may go.
The helper keeps the newest publications of the window, and removes the report
directories the dropped publications named. A publication whose report directory
is gone is dropped from the manifest instead of being kept as a record of
something that is not there. A report directory the manifest does not name is
left alone: the helper does not delete history it cannot explain.

## 7. The root index names everything that is published

The root index lists the populations and the legacy root-level report folders
(the numeric directories the old layout left behind). Both are published, so
both are named.

The legacy reports are not removed, and they are not migrated into a
population namespace: they are published URLs, and rewriting them is a separate
decision. But a report that is published and not linked from anywhere is a
report nobody finds, and the root index is the one page that reaches all of
them.

## 8. The helper has one command per thing it does

`prune`, `write-root-index` and `reconcile` were commands of the helper that
nothing called. They are gone.

`prune` removed report folders and rewrote the manifest afterwards, so the
manifest named reports that were already gone; retention runs through the
publication path now, and retention is manifest-driven (section 6).
`write-root-index` wrote a page outside the state unit, so a root index could
describe a state the manifest did not; the root index is written with the
manifest and the population index, or not at all (section 5). `reconcile`
wrote into a tree that nothing deploys.

A command that nothing calls is a second implementation of a rule, and the two
implementations drift apart.

# Rationale

- One owner per decision. Two owners of the retention window produced a manifest
  that was deleted on a schedule neither of them had written down.
- An error is cheaper than a silent repair. The publication state has one copy,
  and a state that was quietly rewritten is a state nobody can reason about
  afterwards. Refusing costs one failed run; a silent repair can cost the
  history.
- One rule, checked in one place, is a rule that stays true. A path is joined in
  several places, and a rule that each place has to remember is a rule that one
  place will forget.
- A tree that is replaced only by a complete replacement cannot be half
  replaced. The window between wiping a destination and filling it is where a
  crashed run loses history.
- The publication state means something as a whole. A manifest that names a
  report the index does not point at is not a state that can be published.
- A published report that nothing links to is a report nobody finds. The root
  index is the page that reaches every published report, so it names all of them.
- One way to do a thing is one way to be wrong. A command nobody calls is a
  second implementation of a rule, and the two implementations do not stay in
  agreement.

# Consequences

- The workflow passes `keep_reports: 100000` to the report action and restores the
  runner's ownership of the tree the action built. The action's pruning is
  inert; the helper's window is the contract. The threshold is unreachable on
  purpose: it counts entries in the namespace, while the helper's window counts
  publications, and a threshold in the same ballpark as the window would be a
  second owner of it — one number away from deleting the manifest.
- A manifest that does not satisfy the invariants stops the publication. That is
  the intended behaviour, and it is a behaviour change: a manifest whose `latest`
  did not match its stored record used to be normalized and carried on with.
- Report directories the manifest does not name are no longer removed by the
  prune. The number of directories in a population namespace can therefore be
  larger than the window; the window counts publications, not directories.
- The tarball limits are fixed numbers in the helper, sized against the branch's
  steady state (~220 000 files, 1.2 GB). A history that grows past them needs a
  new number and a reason, not a bigger constant.
- A schema version 1 manifest is migrated rather than refused. It was never
  published on this branch, so the migration runs on nothing today; it exists
  because the schema changed once already and a document of the old shape must
  not be silently reinterpreted as the current one.
- The legacy root-level reports on the `gh-pages` branch are not touched by any
  of this. They are published URLs, and removing them is a separate decision.
  The root index links them, so a published report stays reachable: a report that
  is published and not linked is a report nobody finds.
- The CLI no longer has `prune`, `write-root-index` and `reconcile`. Anything that
  called them has to call the publication path instead, which is the only path
  that writes the state.
- A filesystem failure during a publication is reported the way a publication
  error is reported: one line on stderr, exit code 1, no traceback. The helper
  cannot do anything about a full disk, but it can say so instead of failing
  with a stack trace that names an internal frame.

# Links

- Issues: [#2774](https://github.com/gazon1/sing/issues/2774),
  [#2775](https://github.com/gazon1/sing/issues/2775),
  [#2776](https://github.com/gazon1/sing/issues/2776),
  [#2777](https://github.com/gazon1/sing/issues/2777)
- Code: `scripts/ci/allure_pages.py`, `scripts/tests/test_allure_pages.py`,
  `.github/workflows/ci.yml`
- Prior decisions:
  `docs/decisions/allure-publication-latest-history-and-retention.md`
