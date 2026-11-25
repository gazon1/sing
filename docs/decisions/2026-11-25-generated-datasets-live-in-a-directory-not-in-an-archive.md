---
title: "Generated datasets live in a dataset directory, not in a backup archive"
date: 2026-11-25
status: accepted
---

# Generated datasets live in a dataset directory, not in a backup archive

## Context

The synthetic dataset generator of #3090 writes a native backup archive. The
archive budget (`ArchiveBudget`) caps it: at most 100 000 entries and 64 MiB
decompressed. The generator honours that cap and refuses to write an archive
that exceeds it.

The cap is a production constraint with a production meaning: the budget is what
the *app* may read — a backup the user restores through the system file picker.
`ArchiveBudget.check()` and `readEntryBounded()` guard that path, and they must
keep guarding it.

The cap has a second, unintended meaning for the generator. The `100k` profile's
task rows alone take 98% of the budget (65 773 964 bytes of 67 108 864), so the
largest profile could carry **no tag rows, no notes and no checklist items at
all**. The measurements of #3153, #3157, #3176 and #3195 were therefore taken
on profiles that stop short of the one they wanted to price: the biggest
dataset with tags was `90k-sparse` and the densest was `80k-dense`, both below
100k. #3197 asks for that ceiling to be lifted.

The obvious way to lift it — raise `ArchiveBudget.MAX_TOTAL_BYTES`, or add a
flag that skips `ensureWithinArchiveBudget` — would loosen the ceiling the
production restore path relies on. That is not the trade this issue asks for:
the budget and its validation are not to be touched.

## Idea

Separate the two things the archive form was carrying at once.

An archive is a backup: it is what the app restores. A generated dataset is a
fixture: it is what the measurement harness restores. The generator wrote both
as one artifact because, up to now, they had the same size. They no longer
need to.

So the dataset gets its own form: a **dataset directory** — `manifest.json`
and `payload.json`, written as they are, side by side, uncompressed. It holds
exactly the bytes the archive form holds, so it is the same dataset, and it is
not a backup, so the archive budget does not bound it. The budget of the
production restore path stays exactly where it was.

## Decision

The generator gained a second output form, chosen by an explicit flag:

```
generate --output <path> --format dir ...
```

`--format zip` (the default) writes the archive, unchanged, with the budget
check applied as before. `--format dir` writes the dataset directory: the same
generated payload, the same manifest with the same checksum over the same
bytes, written to two files without the budget check. The flag is opt-in and
named — a typo in `--format` is rejected with the list of known forms rather
than silently falling back to the archive.

The contents of the two forms are the same dataset by construction, and
`SyntheticBackupDirectoryFormTest` asserts it: the payload bytes and the
manifest bytes written into the directory are byte-for-byte the bytes the
archive form writes for the same parameters, and the files on disk match them.

The harness got the matching seam. `PerfDataset` names the form of the input
rather than guessing it from the path: `-Pperf.archive=` for an archive,
`-Pperf.dataset=` for a dataset directory, and the two together are refused.
The dataset directory is read by `DatasetDirectoryBackupCodec`, a test-only
codec that returns the same `CodecReadResult` an archive codec returns. It
does not apply the archive budget — that is the one thing it does not do — and
everything else is the production path: `BackupImporter` validates the
manifest, migrates the payload and applies the rows exactly as it does for an
archive.

The codec is bound to the directory it reads because the codec port hands the
importer one byte stream (`FileSource`) — an archive is one file, a dataset
directory is two. That is a property of the port, not a workaround: the
directory form reads its two files and returns them as the codec's result.

The form is also visible where it matters to a reader: the generator's usage
output says which form it wrote and, for the directory form, says in the same
line that the archive budget does not bound it. The harness report prints the
form of the dataset it measured.

## Rationale

The budget is not a limit on how much test data may exist. It is a limit on
what the app will read out of a file the user picked. A dataset directory is
not such a file — the app cannot restore it, nothing in the app reads it — so
applying the budget to it was a category error, and the way it was paid for
was the largest profile losing the tag rows that the tag measurement was about.

Making it a second form rather than a relaxed budget keeps the production
constraint intact by construction. `ArchiveBudget` is not modified, its
`check()` is not bypassed, and `ensureWithinArchiveBudget` is still called for
the archive form — `SyntheticBackupProfileBudgetTest` still refuses the
datasets that exceed the budget, in the archive form. The relaxation is
opt-in: nothing changes for a caller who does not ask for the directory form.

The bytes are shared rather than re-implemented, so the two forms cannot drift.
The generator, the payload, the manifest and the checksum are the same code
path; only the packaging differs. The determinism the generator already
guarantees — same seed, same parameters, same logical ids and relationships —
carries over unchanged, because the dataset is the same dataset.

The load path is the production one. The issue asks for the dataset to be
loaded through the harness, and it is: `BackupImporter` does the reading, the
manifest validation, the migrations and the row writes. Only the reader of
the container is a test codec, and the container it reads is not the one the
app reads.

## Consequences

- The `100k` profile can carry tag rows. Two datasets were generated in the
  directory form from the same generator and the same seed (4242) as the
  archive datasets of the earlier measurements: `100k-tags-sparse` (100 000
  tasks, 75 034 `task_tags` rows, 73 052 440 bytes) and `100k-tags-dense`
  (100 000 tasks, 150 090 `task_tags` rows, 80 354 123 bytes). Both are
  larger than the archive budget of 67 108 864 bytes, and both are refused by
  the archive form of the generator — the refusal is the test that the ceiling
  still holds where it holds.
- The production budget is untouched: `ArchiveBudget.MAX_ENTRIES`,
  `ArchiveBudget.MAX_TOTAL_BYTES`, `check()` and `readEntryBounded()` are as
  they were, and the archive form of the generator still refuses an oversized
  dataset.
- The load path for a dataset directory is not the path the app uses. A
  dataset directory is a measurement fixture; it is not a backup, and the app
  is not able to restore one. `DatasetDirectoryBackupCodec.export` therefore
  fails by design — the generator writes a dataset directory, nothing exports
  one.
- The harness takes two dataset arguments instead of one, and the form of the
  dataset is named rather than inferred. A dataset path that does not exist,
  or an argument that names both forms, is refused before the run.
- The measurements of the earlier releases were taken on profiles below
  100k. The 100k datasets with tags are new ground, and they are measured
  with the same harness, the same forms and the same equivalence checks as
  the datasets they extend.

## Links

- Issue: https://github.com/gazon1/sing/issues/3197
- Generator: `import-cli/src/main/kotlin/com/singularity/todo/importer/SyntheticBackupArchive.kt`
- CLI: `import-cli/src/main/kotlin/com/singularity/todo/importer/ImporterCli.kt`
- Dataset-directory codec: `shared/src/jvmTest/kotlin/com/singularity/todo/core/backup/DatasetDirectoryBackupCodec.kt`
- Dataset form in the harness: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/PerfDataset.kt`
- Generator test: `import-cli/src/test/kotlin/com/singularity/todo/importer/SyntheticBackupDirectoryFormTest.kt`
- Restore test: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/DatasetDirectoryRestoreTest.kt`
- Measurements: `docs/architecture/dependency-query-performance.md`
- Previous decision: `docs/decisions/2026-11-25-user-scoped-blocking-tasks-read-scopes-through-the-primary-key.md`
