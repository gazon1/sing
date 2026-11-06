# The scrollbar publishes the state it draws from

## Idea

The desktop client draws no scrollbar anywhere in `shared/` or `desktopApp/`: the
long lists (agenda, notes, projects, calendar, statistics) scroll, but nothing says
how long they are or where in them the user is. On a mouse-driven window that is a
standard affordance, and Compose ships it as one modifier on the desktop artifact
only. Android must not get a persistent bar: the gesture is the position there, and
the platform already fades its own bar in and out.

## Decision

1. **A modifier pair, not a wrapper composable.** `Modifier.platformVerticalScrollbar`
   exists twice in `:shared-ui`'s `commonMain` — once over `LazyListState`, once over
   `ScrollState` — because a `LazyColumn` and a `verticalScroll` column are two
   Compose types with two adapters. The caller keeps owning the scroll state; the
   modifier only paints the bar onto the node it is attached to.

2. **The `actual`s live in `:shared-ui`'s own `androidMain` / `jvmMain`.** `:shared`
   and `:shared-ui` are separate Gradle projects, so each is a separate module to the
   compiler: an `actual` in `:shared` would leave these `expect`s unsatisfied and
   would itself have no `expect` in its own module.

3. **The bar publishes the state it draws from as a semantics property**
   (`PlatformScrollbarSemantics.listState` / `.columnState`), not as a `testTag`.
   A bar draws and hit-tests but publishes no semantics of its own, so something has
   to be addressable for a test — but a `testTag` is the wrong something:
   - Compose keeps one value per key on a node, so a bar that stamps a tag on a
     container that already has one (`NotesListScreen`'s `TestTags.NOTES_LIST`)
     silently replaces that tag or is silently replaced by it. Two tags cannot
     coexist on one node.
   - A shared tag string cannot distinguish two lists on one screen; a namespaced
     property key can.
   - And a tag can only say "the bar is here". Publishing the *instance* says which
     state the bar is using, which is the contract each call site actually has to
     keep: the container's `state =` and the modifier must get the same variable. A
     second `rememberLazyListState()` publishes a state nothing scrolls, and a test
     that scrolls the container and reads the published state back sees it.

   The key is public because the assertions live in `:desktopApp`'s `jvmTest`, which
   sees this module's public API only. `internal` would make the contract
   untestable, which is the whole point of publishing it.

4. **Each call site is a pair**: one `rememberLazyListState()`, passed to the
   container's `state =` and to the modifier. Most call sites create it locally; the
   agenda's list is handed `listState: LazyListState` by its screen and passes the
   same instance on.

5. **A width cap goes next to the bar and works on either side of it**, because the
   bar takes its geometry from the node it wraps and `widthIn` reports its child's
   width. The notes and projects lists write
   `…platformVerticalScrollbar(state).widthIn(max = 640.dp)`, so on a wide window the
   bar sits at the capped column's edge rather than at the window's.

## Rationale

The alternative to (3) was to keep a test-only `const val` tag and assert its
presence, which is what the first cut did. It could not express the contract that
actually breaks — two states for one list — and it collided with the container's own
tag, so on the notes list one of the two values was silently dropped. An
`assertSame(listState, published)` plus a scroll is the smallest thing that fails
when a call site is mis-wired, and it costs one semantics property on a node that
already carries `TestTags`/`ScrollAxisRange`.

Publishing semantics rather than intercepting the modifier was also rejected:
`Modifier` cannot inspect the node it is composed onto, so a bar that "only stamps a
tag if none is present" cannot be written against the chain it is part of.

## Consequences

- `PlatformScrollbarTest` (fast) covers the mechanism: the published instance is the
  one handed in, a container without the modifier publishes nothing, the
  two-states mistake is caught, an existing `testTag` and the bar coexist on one
  node, and the column overload publishes its `ScrollState`.
- `DesktopScrollbarFlowTest` (slow) is the first screen-level check: the notes list
  carries the bar after a real navigation. The remaining call sites are the agenda
  (`AgendaContent`, `SavedAgendaContent`, `SavedAgendaListScreen`), projects
  (`ProjectsScreen`, `ProjectDetailBody`), calendar (`TimeGridView`), notes
  (`NotesListScreen`), project detail and statistics (`StatisticsScreen`, three
  columns). They are covered by the mechanism tests but not yet by their own screen
  traversal; the assertion shape is the one `DesktopScrollbarFlowTest` uses.
- Known debt, out of scope here: `AgendaContent` applies `fillMaxSize()` *outside*
  `widthIn(max = 640.dp)`, and a `fillMaxSize` fixes the constraints before the cap,
  so the cap is a no-op there and the agenda's list — and its bar — span the window.
- If a scrolling surface is later added without the modifier, nothing fails: the
  seam cannot see containers it is not applied to. Only the per-screen traversals
  close that gap.
