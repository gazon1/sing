# 2727 — Verify imported completion state before changing the Today overdue UI

Status: research recorded 2026-11-09 (workspace `gazon1/research-verify-imported-completion-state-before`).

## Question

The 2026-11-09 Android comparison reported **7,050 overdue tasks on Today** after restoring a backup converted from `singularity_backup_2026-11-08.json` (12,026 tasks). Is that mostly a migration/completion-mapping problem rather than a Today presentation problem?

## Source artifact (read-only)

| Item | Value |
|---|---|
| Legacy export | `/home/max/Downloads/singularity_backup_2026-11-08.json` (28 MB, `version: 2`) |
| Tasks (non-removed) | 12,026 (`_removed` absent/0 for all tasks) |
| Investigation copies | `/tmp/2727-investigation/native-no-mark.zip`, `/tmp/2727-investigation/native-with-mark.zip` (disposable; no production/live data overwritten) |

## Converter invocation / behavior (verified from code + executed runs)

`import-cli` `convert` (`ImporterCli.kt`):

- Default: `--mark-completed` **false**.
- With flag: `Mapper` sets `completedAt = updatedAt` only when `markCompleted && tk.checked == 1 && tk.state == 0` (`Mapper.kt:180`).
- It reports `Completed candidates (checked==1 && state==0)` (`Mapper.kt:104`, `ImporterCli.kt:80`).

Executed (workspace build, `:import-cli:run`, 2026-11-09):

```sh
# without flag (default)
singularity-import convert \
  --input /home/max/Downloads/singularity_backup_2026-11-08.json \
  --output /tmp/2727-investigation/native-no-mark.zip

# with flag
singularity-import convert \
  --input /home/max/Downloads/singularity_backup_2026-11-08.json \
  --output /tmp/2727-investigation/native-with-mark.zip --mark-completed
```

Both runs printed: `tasks=12026 projects=132 tags=277 notes=4268 checklists=2588 timeEntries=662`; `Completed candidates (checked==1 && state==0): 21`; `schemaVersion=8 formatVersion=1`.

The exact converter invocation used for the 2026-11-09 Android comparison is **not recoverable from this workspace** (no saved command/report found here). Given the documented default and the observed counts, it is consistent with conversion **without `--mark-completed`** (or with the current broken rule, which marks only 21). This remains an inference about that specific run, not a recorded command.

## Legacy field distribution (representative = full non-removed task set)

`checked`/`state`/`complete` cross:

| checked | state | complete | count |
|---:|---:|---:|---:|
| 1 | 1 | 0 | 9637 |
| 0 | 1 | 0 | 2362 |
| 1 | 0 | 0 | 21 |
| 0 | 0 | 0 | 4 |
| 2 | 1 | 0 | 2 |

`complete` is `0` for all 12,026 tasks, so it carries no completion signal in this export.

### Verified meaning for this export (from representative records)

- **`checked == 1` means completed/done** (9,658 tasks). Sampled `checked==1,state==1` records are past user actions with titles/dates consistent with completed items (e.g. “выпить таблетки” recurrence instances, “Заказать курьера wb”, etc.), and 2,131 of 9,637 carry recurrence-instance IDs (`…-YYYYMMDD`), matching completed occurrences. Sampled `checked==1,state==0` (21) also read as completed (past-dated, recurrence instances present).
- **`checked == 0` means not completed** (2,366). Sampled `checked==0,state==1` are open/undone items (no delete, many undated/deferred planning items).
- **`checked == 2`** (2 tasks, `state==1`, not deleted) does not indicate completion; treat as not completed.
- **`state` does not gate completion** in this export. `state==0` is only 25 tasks (21 checked, 4 unchecked) and is not the completion state; 9,637 clearly-completed tasks are `checked==1,state==1`.

Conclusion: for export version 2 / this data, **completion is `checked == 1`**. Requiring `state == 0` is wrong and drops 9,637 completed tasks. This is verified against representative records sampled from the export (the source export artifact); I did not compare interactively against the live SingularityApp UI, but the field evidence is direct and consistent.

## Count reconciliation

Using `start` date `< 2026-11-09` as a rough overdue proxy, excluding `deleteDate` (legacy archive/delete) and excluding tasks treated as completed:

| Conversion rule | tasks marked completed | rough overdue (past `start`, not deleted, not completed) |
|---|---:|---:|
| no `--mark-completed` (current default) | 0 | 7047 |
| `--mark-completed` with **current** mapper (`checked==1 && state==0`) | 21 | 7030 |
| `--mark-completed` with **`checked==1`** (recommended) | 9658 | 108 |

Payload inspection of disposable converted ZIPs matches: `native-no-mark.zip` → `completedAt=0`, overdue-by-dueDate/not-archived/not-completed=7047; `native-with-mark.zip` (current rule) → `completedAt=21`, 7030. The reported **7,050** is explained by conversion that left ~9.6k completed tasks active (default/no effective completion marking); the small delta (7047/7030 vs 7050) is expected from rough `start<today` proxy vs the app’s `TaskComputed.isOverdue`/Today semantics.

## Recommendation / decision

**Correct the import procedure and the mapper (both). Do not change the Today UI for this cause.**

1. **Mapper fix:** treat legacy completion as `checked == 1` (not `checked == 1 && state == 0`) when `--mark-completed` is set, and count/report “completed candidates” with the same predicate. `checked==2` remains not completed. (Low-risk, data-backed for this export; keeps `--mark-completed` opt-in.)
2. **Import procedure:** for legacy SingularityApp JSON imports where historical completion matters, run conversion with `--mark-completed` and verify completed/overdue counts before restore. Without it, completed tasks import as active and inflate Today/overdue.
3. **Today UI:** no change justified by this evidence. Re-evaluate #2675/UI only if an import with corrected completion still shows an unexplained overdue count.

## Acceptance criteria status

- [x] Converter invocation used for the comparison is known **as far as recoverable**: no exact saved command in workspace; code/default and counts indicate default/no effective completion marking. Exact comparison invocation not recorded — documented.
- [x] Candidate and restored completed/overdue counts reconciled (21 vs 9658 candidates; 7047/7030/108 overdue by rule; explains ~7050).
- [x] Meaning of `checked`/`state` verified against representative source records from the export; `complete` uninformative (all 0). Live-app interactive comparison not performed — stated.
- [x] Decision recorded: correct the import procedure **and** change mapper completion semantics; keep UI conservative.
- [x] No production backup or live user data overwritten (read source JSON; wrote only `/tmp/2727-investigation/*` and this doc).
