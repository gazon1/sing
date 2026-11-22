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

`100k` is the ceiling the payload format allows, not a tuning choice: the 100k task rows alone are ~61.7 MB of the 64 MiB budget, so that profile carries no tags, notes or checklist items and only a token dependency graph. Ask for a richer 100k composition with the explicit parameters and the command fails the archive budget check, as designed — the limit is a contract the app enforces on import.

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

Output:

- `<output>.zip` — native backup archive.
- `<output>.manifest.json` (or `<output>.manifest.json` next to non-`.zip` output) — sidecar with seed, generator version, profile/params, actual counts, schema version, payload checksum.

Constraints:

- Deterministic for same seed + params: same logical data, IDs, and relationships. Byte-identical ZIP is not required.
- No current time, no unseeded randomness, no machine-specific data in generated payload.
- Dependency graph is generated acyclic.
- Archive budget is enforced; on overflow the command fails with diagnostics and does not bypass/truncate.
- Attachments are not generated in this stage.
