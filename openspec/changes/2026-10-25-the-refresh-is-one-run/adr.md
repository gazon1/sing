# ADR Review Manifest — the refresh is one run, and one run has one owner

The sequence numbers below are the ordinal of the ADR inside this change; the
corpus in `docs/decisions/` is not numbered.

## ADRs Created

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| 1 | the-refresh-is-one-run | The refresh is one run, and one run has one owner | accepted | One owner of the run; the archive step before the synchronisation step; the core declares its own narrow contract for the archive step; the report belongs to the screen that asked |

## ADRs Reviewed (No Action)

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| — | 2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine | The sync engine needs the repositories, and the repositories need the engine | accepted | The core holds no repositories. The refresh keeps that: the core declares the contract it needs from the archive step, and the feature's port inherits it |
| — | 2026-10-22-an-event-bus-has-one-consumer | An event bus has one consumer, so a screen collects its events once | accepted | An event has one consumer. The refresh's report therefore belongs to the screen that asked for it, and no other screen repeats it |
| — | 2026-09-08-task-archive-restore-contract | Task archive vs delete: separate contracts via archiveAt | accepted | A task in the archive is restorable. A task moved there in bulk is the same kind of archived task, so it stays restorable |
| — | 2026-09-27-di-module-aggregator-narrative | The DI module aggregator | accepted | The bindings live in a per-domain module of their own and are aggregated by the facade; the facade stays a list, not a set of includes |

## Decisions That Did Not Meet the Bar

- **A button in the top bar that does what the gesture does.** The gesture is the
  point: it is what the user reaches for on a screen they are already looking
  at. A second way to ask for the same thing is a second place to keep in step
  with the first, and it is not what was asked for.
- **A setting that turns the gesture off.** The refresh does what the user means
  when they pull a list down, and it reports what it did. A switch would be a
  way to be surprised by the opposite.
- **Archiving notes, projects and tags as well.** Only tasks have an archive,
  and only tasks can be "completed" in the sense the archive step means. The
  other kinds of content have their own lifecycles, and extending the archive
  to them is a separate decision about separate kinds of content.
- **Letting the scheduled synchronisation carry the refresh's archive step.**
  The archive step has to happen inside the gesture, on the user's demand, and
  it has to be ordered against that gesture's synchronisation cycle. Folding it
  into the schedule would move it out of the user's reach and out of order.
- **Teaching the synchronisation core about archiving.** The core holds no
  repositories, and the archive step is not a repository. Keeping the two steps
  in one run does not require the core to know what archiving is.
