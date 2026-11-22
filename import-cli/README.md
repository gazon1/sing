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

Profiles: `1k`, `10k`, `50k`, `100k`.

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
