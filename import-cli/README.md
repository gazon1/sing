# import-cli

JVM CLI for Singularity backup import workflows.

## Commands

### `convert`

Converts a legacy SingularityApp backup JSON into a native backup ZIP.

```bash
./gw :import-cli:run --args='convert --input /path/to/legacy.json --output /path/to/native.zip'
```

Optional: `--mark-completed` marks legacy `checked==1` tasks as completed.

### `verify`

Verifies a native backup ZIP can be read by the current build.

```bash
./gw :import-cli:run --args='verify --input /path/to/backup.zip'
```

### `generate`

Generates a deterministic synthetic native backup ZIP for performance testing. The payload is built from backup DTOs and serialized with the current `StableJson` + `JvmBackupCodec`. A sidecar manifest is written next to the archive.

Named profiles set defaults; explicit params override them. Actual support for a profile is gated by archive budget checks and `verify`/decoder validation, not by the task count alone.

Profiles: `1k`, `10k`, `50k`, `100k`. A profile is a composition that fits the archive budget, not just a task count — `SyntheticBackupProfileBudgetTest` fails the build if a profile stops fitting or stops round-tripping through the codec. The payload column is what each profile produced with seed `42` (the archive payload, not the compressed ZIP); the budget is 64 MiB of decompressed payload.

| Profile | Tasks | Projects | Tags | Notes | Checklist items | Dependencies | Payload |
|---|---|---|---|---|---|---|---|
| `1k` | 1 000 | 20 | 30 | 100 | 2 000 | 150 | 1.4 MB |
| `10k` | 10 000 | 100 | 100 | 1 000 | 20 000 | 1 500 | 13.9 MB |
| `50k` | 50 000 | 250 | 120 | 2 000 | 25 000 | 5 000 | 48.2 MB |
| `100k` | 100 000 | 300 | 0 | 0 | 0 | 2 000 | 65.8 MB |

`100k` is the largest profile provided and measured, and it is constrained by the archive budget rather than by a task-count limit: the 100k task rows alone are ~61.7 MB of the 64 MiB budget, so that profile carries no tags, notes or checklist items and only a token dependency graph. Nothing forbids a custom dataset above 100k tasks — feasibility is decided by the serialized size, so pass the explicit parameters and the command either produces a smaller-composition dataset or fails the archive budget check with diagnostics, as designed. The limit is a contract the app enforces on import.

```bash
# Reproducible 10k profile
./gw :import-cli:run --args='generate --profile 10k --seed 42 --output /tmp/synthetic-10k.zip'

# Custom dataset
./gw :import-cli:run --args='generate --seed 7 --tasks 2000 --projects 40 --task-hierarchy-depth 4 --subtask-share 0.25 --tags 60 --dependency-density 0.15 --notes 200 --checklist-items 4000 --output /tmp/synthetic-custom.zip'

# Verify generated archive
./gw :import-cli:run --args='verify --input /tmp/synthetic-10k.zip'
```

Parameters:

| Option | Meaning |
|---|---|
| `--output` | Output ZIP path (required) |
| `--seed` | Deterministic seed, default `1` |
| `--profile` | `1k`, `10k`, `50k`, `100k` |
| `--tasks` | Task count (required without profile) |
| `--projects` | Project count |
| `--task-hierarchy-depth` | Max task/project hierarchy depth |
| `--subtask-share` | Share of tasks created as subtasks, `0.0..1.0` |
| `--tags` | Tag count |
| `--dependency-density` | Approx dependency edges relative to tasks, `0.0..1.0`; DAG by construction |
| `--notes` | Note count |
| `--checklist-items` | Checklist item count |
| `--due-date-share` | Share of tasks with a due date, default `0.75` |
| `--due-date-window-start-days` | First day of the due-date window, **inclusive**, default `-30` (negative = before the base epoch, i.e. overdue) |
| `--due-date-window-end-days` | End of the due-date window, **exclusive**, default `120`; a window of `-10..-1` produces due dates for days `-10` through `-2` |
| `--completed-share` | Share of tasks that are completed, default `0.20` |
| `--next-up-share` | Share of the *not-completed* tasks claimed as `next_up`, default `0.19` |
| `--in-progress-share` | Share of the *not-completed* tasks claimed as `in_progress`, default `0.13`; together with `--next-up-share` it must not exceed `1.0` |
| `--priority-weights` | Relative weight per priority, comma-separated in the order `none,low,medium,high,urgent`, default `1,1,1,1,1`. `0,0,0,1,9` gives roughly 10% `high` and 90% `urgent` |

The base epoch is fixed at `2025-01-01T00:00:00Z`, so the same seed and params always produce the same dates on any machine. Nothing reads the current time.

A scenario that needs a mostly-overdue, mostly-completed dataset asks for it directly:

```bash
./gw :import-cli:run --args='generate --seed 7 --tasks 20000 --due-date-share 1.0 --due-date-window-start-days -60 --due-date-window-end-days -1 --completed-share 0.8 --output /tmp/synthetic-overdue.zip'
```

Output:

- `<output>.zip` — native backup archive.
- `<output>.manifest.json` (or `<output>.manifest.json` next to non-`.zip` output) — sidecar with seed, generator version, profile/params, actual counts, schema version, payload checksum.

Constraints:

- Deterministic for same seed + params: same logical data, IDs, and relationships. Byte-identical ZIP is not required.
- No current time, no unseeded randomness, no machine-specific data in generated payload.
- Dependency graph is generated acyclic.
- Archive budget is enforced; on overflow the command fails with diagnostics and does not bypass/truncate.
- Attachments are not generated in this stage.
