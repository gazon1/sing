# Tasks — the-backup-keeps-the-verb

## Phase 1 — the edge carries its meaning

- [x] **shared/**: the backup payload records the verb of every dependency edge,
      and a restore puts back the relation that was there.
      Test: a DTO test asserting the verb survives the mapping in both
      directions.

## Phase 2 — a round trip keeps the graph

- [x] **shared/**: a backup and a restore leave the graph as it was.
      Test: a round-trip test over a real database, with one edge that blocks
      and one that only follows up.

## Phase 3 — a backup written before the verb existed

- [x] **shared/**: an edge without a recorded meaning restores as a block, and
      the payload version says so.
      Test: a DTO test asserting the default, and the migration that stamps the
      verb on every edge of an older payload.

## Phase 4 — verification

- [x] **shared/** + **desktopApp/**: run the full gate set.
      Test: all four green.
