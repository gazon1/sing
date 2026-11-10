---
title: A published shell title is read where it is drawn
date: 2026-11-10
status: accepted
tags: [android, shell, compose, ui]
---

# Context

On Android the shell owns the only top bar. A screen inside the shell that
wants the bar to say something else publishes it: `TopAppBar(overrideShell =
true)` hands the title and the navigation icon to `ScreenActionsHolder`, and the
shell draws them in its own bar.

The projects graph is a nested graph. The shell's state cannot see inside it,
so the shell's own name for the project detail is the name of the destination
that opened the graph ("Projects"), and the shell's arrow only knows the outer
stack. The project detail therefore publishes its name and its own back arrow,
and so do the project editor and the project list.

Publishing a title as a plain string takes two steps to reach the bar: the
publisher has to recompose and publish again, and the shell has to redraw.
Reading the state where the title is drawn takes one. On the project detail
the two-step path was seen to lose a race on a cold start: the bar said
"Project" (the `Loading` title) while the body behind it already showed the
project's name. One cold run passed and the next failed on the same APK. The
race was never root-caused — what is known is that the two-step path failed
and the one-step path passed four cold runs in a row.

# Idea

A published title does not have to be a value the publisher hands over. It can
be a reading the shell performs, in the shell's own composition.

# Decision

**A publisher whose title comes from state publishes a reading, not a string.**

`publishTitle` takes the content as a lambda; the shell invokes it when it
draws. A publisher whose text comes from state therefore hands the shell a
lambda that reads the state at the moment of drawing:

```kotlin
val state = viewModel.stateFlow.collectAsStateWithLifecycle() // a State, not a value
TopAppBar(
    title = { Text(detailBarTitle(state.value)) },
    overrideShell = true,
)
```

The state is kept as a `State<…>` and not unwrapped with `by` at the call site,
because the shell's composition has to observe the read itself: a value read in
the publisher's composition is read once, and the shell draws that one value.

A captured title is not broken. `CalendarTopBar` publishes a captured month
string and it does change when the month changes: the publisher recomposes,
the published lambda is a new instance, the holder's `title` changes, and the
shell redraws. The rule this decision states is about the number of steps, not
about captured values being frozen:

- a captured title updates when the publisher recomposes and republishes;
- a title read from the state when it is drawn updates as soon as the state
  changes.

A title that must not be one step behind its screen — the name of the project
on screen, the name of the screen on screen — is published in the reading form.

# Rationale

The shell's bar and the screen's body are two different compositions, and the
state lives in the screen's. Anything the shell draws on the screen's behalf
has to be read from the screen's state at drawing time, in the shell's
composition, or the two can disagree.

The reading form removes the publisher's recomposition from the path: the
shell observes the state itself, so the title follows the screen whether or not
the publisher republishes in time.

# Consequences

- The state must reach the publisher as a `State`. A publisher that takes a
  plain value cannot publish a title that follows the screen.
- The shell's own fallback title is `(topMostRoute as? AppDestination)?.title
  ?: current.title`, and it is a fallback: a published title takes precedence
  over it, and the shell's name is what a screen that says nothing shows.
- **That fallback is app-wide, not projects-only.** `topMostRoute()` is the
  deepest entry of the active tab's stack, so every destination that is pushed
  onto a tab now names the bar: `TasksGraph` ("Tasks"), `TasksByProject`
  ("Project Tasks"), `AttachmentViewer` ("Attachment"), `ProjectsGraph`
  ("Projects"), `ProjectDetail` ("Project"), `ProjectEditor` ("New Project"),
  `CalendarGraph` ("Calendar"), `NotesGraph` ("Notes"), `AiChat` ("AI Chat"),
  `Proposals` ("Proposals"), `ExternalAgent` ("External Agent"), and the
  settings family (`Attach Account`, `Change Password`, `Verify Email`,
  `Profiles`, `AI Usage`). Tabs and menu entries switch the stack instead of
  being pushed, so their names do not change. This is intended: the bar names
  the screen on screen. The screens that publish no title of their own are the
  ones whose bar changes, and none of them has a test for it.
- The screens that publish a title keep the precedence: the published title
  wins over the fallback, and the selection title wins over both.
- Desktop is unaffected: it has no `LocalScreenActions`, so `overrideShell` is
  inert there and the screens draw their own bars.

# Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/ScreenActions.kt`
  — the publisher and the holder.
- `shared/src/androidMain/kotlin/com/singularity/todo/AndroidShellNav3.kt` —
  the shell's bar and its fallback title.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectDetailTopBar.kt`
  — the publisher that reads its state where it is drawn.
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectDetailTopBarShellPublishTest.kt`
  — the test that draws the published title the way the shell draws it, and
  asserts that the name follows the project.
