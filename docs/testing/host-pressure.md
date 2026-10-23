# What the host pressure costs

`sgate host-check` answers one question: is there room for another run, and
what is in the way? This page publishes the numbers it is measured against, so
the next measurement can move them — and the measurement that moves them has to
be written down here, the way these are.

## The failure it answers

A Gradle build died in the middle of a compile with `No space left on device`,
and the space was not the disk. The scratch directory (`/tmp`) is a tmpfs with
**1,048,576 inodes**, and the drafts of previous sessions had taken **700,450**
of them — 67 %. The diagnosis cost about six calls and a restart of a
35-minute run.

`sgate host-load` did not see it: it measures the CPU, and the CPU was idle.

## The measurements (2026-10-23, this host)

| What | Measured | How |
|---|---|---|
| Inodes of the scratch filesystem | 1,048,576 | `df -i /tmp` |
| Inodes in use | 700,450 (67 %) | `df -i /tmp` |
| Inodes of the checkout's filesystem | unlimited | `df -i` reports 0 for btrfs, which allocates inodes dynamically |
| One session's draft | 54,912 inodes | `find /tmp/wt-phaseb -xdev \| wc -l` |
| The largest draft on the host | 86,802 inodes | `find /tmp/hookremote.git -xdev \| wc -l` |
| The longest run in this repository | 37 min 25 s | `docs/testing/gate-costs.md` |
| The longest job of the last CI run | 31 min 29 s | `config/docs/gate-benchmarks.json` |

The inode numbers come from `os.statvfs` — the call `df -i` makes. The tool
reads them directly, so the numbers it prints and the numbers `df` prints are
the same numbers.

## The thresholds, and why they are where they are

### `tight` at 80 % of the inodes

The scratch filesystem holds 1,048,576 inodes, and one session's draft costs
54,912 of them. Twenty percent of the filesystem is 209,715 inodes, which is
fewer than four more drafts (4 × 54,912 = 219,648).

So the line is drawn at **80 %**: past it, the host cannot host four more
sessions, and the run that dies is a build in the middle of a compile — the
failure above. The threshold is a module constant
(`host_load.TIGHT_INODE_PERCENT`) so the next measurement can move it.

### A draft is dead after 24 hours

The longest single command this repository measures takes 37 min 25 s. A
scratch directory that nothing has touched for 24 hours — 39 times that
longest run — cannot belong to a running command; it belongs to a session that
ended.

The threshold is `host_load.STALE_DRAFT_HOURS`.

### A session's draft costs 54,912 inodes

The divisor that turns free inodes into "how many more sessions fit". It is
`host_load.DRAFT_INODE_COST`, and the report prints the number of drafts that
still fit rather than a raw inode count, because the draft is the unit a
session spends.

### The scan is bounded at 120 seconds

The walk of the scratch tree is the expensive part of the command: it reads
the mtime of every entry, because a draft is dead only when *nothing* in it
has been touched inside the staleness window. On this host the scratch tree
holds 935,585 entries, and one walk of it costs **5.6–6.2 s** measured on
2026-10-23 at a load of 24 on 16 cores.

The bound is `host_load.SCAN_TIMEOUT_SECONDS` — 120 s, twenty times the loaded
measurement. It does not fire on a healthy scratch directory; it fires on one
that cannot be walked in two minutes, which is not a scratch directory
anybody should be pruning.

## Where the drafts may live

The command prints an `rm -rf`, so the directory it scans has to be a place
where a session's leftovers live — never a place where the system lives. A
scratch directory is accepted when it is

* one of the temporary directories — `/tmp`, `/var/tmp`, `$TMPDIR` — or a
  directory inside one of them; or
* a directory the user marked as a drafts directory, by putting the file
  `.sgate-drafts` in it.

Everything else is refused, and the refusal is printed: the filesystem root,
the home directory, `/srv`. Without that rule `--scratch /` names `/afs`,
`/media` and `/srv` for removal — all three of them are directories of the
system, and all three of them exist on the machine where the command was
written.

The refusal is the first guard; the second is in the prune command itself,
which never names the filesystem root, a directory directly under it, or the
home directory, whatever the scan found.

## What the command does, and does not do

It reads the real state of the machine and it deletes nothing:

* it reports the inode pressure of the scratch filesystem and of the checkout —
  the two places a run writes;
* it names the biggest drafts, with their inode count and their size, and marks
  each one **dead** (nothing has touched it inside the staleness window),
  **live** (a session is writing to it) or **unknown** (it could not be read);
* it prints the command that removes the dead drafts — `rm -rf …`, quoted —
  and never runs it.

Five rules keep the prune suggestion safe, and none of them is a guess about
names:

* hidden entries are skipped — the scratch directory on this host holds a live
  AppImage mount under a dot-name;
* symlinks are skipped — a link is not a draft, and the age of the link says
  nothing about the age of what it points at;
* mount points are skipped, and so is a draft that holds a mount of its own —
  `rm -rf` walks into a mounted filesystem, while `find -xdev` stops at it;
* a directory touched inside the staleness window is never named by the prune
  command;
* a directory that could not be read is never named either — unknown is not
  dead.

`--top N` counts the **dead** drafts, not the whole list. The caller asked
how many dead drafts to name, and a limit that counted the live ones as well
answered "there is nothing to prune" while dead drafts sat further down the
list — the false verdict this command exists to prevent. The biggest live
drafts are named beside the dead ones, so the question "where did the inodes
go" has an answer even when every draft is still being written to.

```bash
sgate host-check             # the summary
sgate host-check --quiet     # the verdict and the prune command only
sgate host-check --json      # one JSON document
sgate host-check --fail-when-tight   # exit 1 when a filesystem is tight
```

## The waiting, and the one caller

The run this page is about was watched by a loop of `sleep 30 && tail`. Each
poll cost a round of context, and each round cost the same whether the run had
thirty seconds left or thirty minutes. The tool that replaces it is
`./wait-for`:

```bash
./wait-for shared/build/diagnostics/jvmTest-fast.log "BUILD SUCCESSFUL"
```

It waits for the line that means the run is over and prints the tail exactly
once — code 0 when the pattern appeared, 1 when the timeout expired (the tail
is printed anyway), 2 when the arguments do not make sense. Its default
timeout is 3600 seconds, which is longer than the longest run measured above,
because a default shorter than the longest run turns one run into two waits.
`--timeout 0` waits without a limit.
