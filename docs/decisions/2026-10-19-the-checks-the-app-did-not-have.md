---
title: "The checks the app did not have: a resolvable DI graph, an explicit optional dependency, a root-aware harness, and a tag that survives the merge"
date: 2026-10-19
status: accepted
tags: [di, testing, compose, maestro]
---

## Context

The state-based text field migration (ADR `2026-10-19-text-field-state-migration`) and its
follow-up (ADR `2026-10-19-text-field-bridge-guards-and-field-shapes`) changed how every
field in the app holds its text. Doing that work surfaced four places where the app had
**no check at all** — not a weak check, no check — and each of them had already cost
something real.

**The DI graph was never resolved.** `TestPlatformModuleParityTest` compares the
*registrations* of the production platform module against the test one: which types each
binds. It never resolves anything. `TaskDetailCoordinatorGraphTest` resolves a handful of
definitions by hand, and its own comment claims the job is "runtime validation via
`checkModules()`" — a call that appears nowhere in the repository, for a library that is
not a dependency of the module the tests live in. The consequence: `RewriteNoteUseCase`
takes the `TypedTool` *interface*, `factoryOf` resolves a constructor parameter by its
declared type, and the generic argument erases to the raw `TypedTool`, for which nothing
was bound. The definition was **registered**, so every registration-shaped check was
happy, and every *creation* of it threw `NoDefinitionFoundException`. The note editor
could not be composed on any platform. It stayed invisible because nothing ever resolved
the definition.

**"AI is optional" was implemented with `getOrNull`.** `getOrNull` answers one question:
*is there a definition for this type?* A definition that exists and *fails to be created*
is not a missing definition — it throws, and the caller cannot tell the two apart. So the
"AI is optional" wiring in `NotesDiModule` did not degrade to "AI is not configured" when
the AI binding was broken; it took the whole note editor down in composition. The same
shape was in the settings contributors in `CoreDiModule`.

**The harness assumed one semantics root.** `onRoot` asserts that the scene has exactly
one root. A `Popup` and a `Dialog` each open their own window, so a screen with a sheet or
a dialog has two roots and `onRoot` throws "Expected exactly '1' node but found '2'".
That is why the failure bundle's semantics dump threw instead of reporting the failure,
and why `mainTreeSize()` and the a11y check misbehaved on exactly the screens that open a
sheet — the a11y check swallowed the exception and reported *no violations*, on the
screens where a missing label is most likely.

**A test tag inside a merging card cannot be addressed.** A Material3 `Card` is a
`Surface`, and a `Surface` merges its descendants. The note row carried its title-based
tag on a `Row` inside the card, so the tag existed in the unmerged tree only — and
Maestro drives the accessibility tree, which holds the merged nodes. Three Maestro flows
addressed a note by its title, and the selector could not exist where they looked. The
flows were also written against a navigation that does not exist: tapping a note opens
its *preview*, and the editor is reached from the preview's Edit action.

## Idea

Give each of these a check that fails when the thing breaks, and put the checks where the
next change will meet them: resolve the graph at runtime, make the optional dependency
explicit, make the harness root-aware, and put the tag where the merge cannot swallow it.

## Decision

1. **A runtime graph check.** `DomainGraphResolutionTest` builds the graph the desktop
   harness uses (`domainModule()` + `testPlatformModule()`), enumerates every definition
   through the instance registry, and creates each one. A definition that cannot be
   created is a failure with the cause chain in the message. A definition that needs a
   *runtime parameter* (a ViewModel that edits one entity) is skipped: the caller
   supplies it, and its absence is not a broken graph. The check is a test, not a new
   dependency — `koin-test` is not on the desktop test classpath, and adding it is not
   worth a build change for one call.

2. **An explicit optional dependency.** `Scope.getOptionalOrNull { failure -> … }` in
   `core/di` returns `null` for a definition that is *not registered* and for a
   definition that *cannot be created*, and reports the second case to the caller. The
   note editor and the settings screen use it; the failure is logged, so a broken
   binding is visible in the log even though the screen survives it.

3. **One root-aware way to look at the tree.** `semanticsTree()`, `semanticsRoots()` and
   `mergedRoots()` in the desktop harness walk *every* root. The failure dump, the
   isolated-compose harness, the boot test and the a11y check all go through them, and a
   convention rule (`raw root lookups are only in test helpers`) keeps a raw `onRoot(`
   from creeping back into a test file.

4. **The tag lives outside the merge.** The note row keeps `note_item_<id>` on the card —
   the card is the merged node, and it keeps its own tag — and `note_item_by_title_<slug>`
   moves to the item's outer node, which is not merged. `Modifier.testTag` stores a single
   value, so one node cannot carry both; two nodes can. The Maestro flows are rewritten
   to match the navigation that exists: the quick-add opens the editor, a row opens the
   preview, the editor is reached from the preview's Edit action.

5. **The field shapes are named at every call site.** The anchored shape gets a helper
   (`rememberAnchoredTextFieldState`) and the sites that hand-rolled it use it. The
   inventory of every field in the app — local, bridged or anchored — is in the
   `singularity-todo-text-field-states` skill, so the next agent does not re-derive it.

## Rationale

Each of these is a check for a failure that already happened. The graph check would have
caught the note editor on day one, on every platform, in seconds. The optional shape turns
"the screen crashed" into "the feature is unavailable, and the log says why". The
root-aware harness turns a confusing `onRoot` exception into the tree the test actually
had. The tag placement turns a selector that could never match into one that does.

The common thread is that every one of these failures was *silent*: a registered
definition that could not be created, an optional dependency that took the screen down
instead of degrading, a dump that threw instead of reporting, a tag that existed in a
tree nobody drives. The checks are deliberately cheap and local — a test that resolves
the graph, a helper that walks every root, a rule that bans a raw selector — so that the
next failure of the same shape is a red test rather than a support ticket.

## Consequences

- The graph check instantiates every definition, including ViewModels. It runs against
  the test platform module, so no real database, port or file is touched. A definition
  with side effects on construction would show up here first, which is the point.
- `getOptionalOrNull` reports the failure through a callback, and the two call sites log
  it. A broken AI binding now produces a log line and a working screen, not a crash.
- The a11y check now sees every root, so screens with a sheet or a dialog are checked
  properly for the first time. It may report violations that were previously invisible.
- The note row's two tags live on two nodes. A test that addresses the card by its id
  still works; a test that addressed the title tag in the merged tree now works too.
- The Maestro flows for notes are rewritten, not merely adjusted: the quick-add, the
  preview and the Edit action are three different screens, and the flows now say which
  one they are on.

## Links

- ADR `2026-10-19-text-field-state-migration` — the migration these checks follow from.
- ADR `2026-10-19-text-field-bridge-guards-and-field-shapes` — the bridge's guards and
  the three field shapes.
- ADR `2026-10-04-undo-snackbar-and-popup-testtags` — the earlier encounter with the
  accessibility tree and merged semantics.
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/DomainGraphResolutionTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/OptionalDependencies.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/SemanticsTrees.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/components/NoteCard.kt`
- skill `singularity-todo-text-field-states` — the field-shape inventory.
