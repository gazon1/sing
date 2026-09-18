---
title: "Preview with VM-as-parameter, not Koin-in-preview"
date: 2026-09-09
tags: [preview, compose, koin, architecture]
status: accepted
---

## Context

`ProjectDetailScreen` and other screens used `@Preview` composables that called `koinViewModel { … }` directly. In Android Studio / JVM test harness (which does not start Koin), these previews crashed with `IllegalStateException: KoinApplication has not been started`.

We evaluated two approaches to fix this:
1. **`PreviewKoin` helper** — a composable that starts a minimal Koin application for the preview scope
2. **VM-as-parameter pattern** — split every screen into a public Koin wrapper and a private content composable that accepts the VM

## Decision

Adopted **VM-as-parameter pattern**. Every screen now has:

```kotlin
// Public — Koin entry point (NOT private, NOT @Preview)
@Composable
fun ProjectDetailScreen(projectId: ProjectId, onBack: () -> Unit, ...) {
    val vm: ProjectDetailViewModel = koinViewModel { parametersOf(projectId) }
    ProjectDetailContent(viewModel = vm, projectId, ...)
}

// Private — accepts VM as parameter, usable in @Preview
@Composable
private fun ProjectDetailContent(viewModel: ProjectDetailViewModel, ...) {
    // all real Compose UI
}

@Preview
@Composable
private fun ProjectDetailScreen_Preview() {
    PreviewThemed {
        val vm = ProjectDetailViewModel(
            projectId = sampleId,
            projectRepo = FakeProjectsRepository(),
            taskRepo = FakeTaskRepository(),
            ...
        )
        ProjectDetailContent(vm, ...)
    }
}
```

## Rationale

**`PreviewKoin` failed** because:
- Koin 4.x DSL (`factoryFor`, `include`) has different availability in `KoinAppDeclaration` lambda vs. runtime module DSL
- The Koin authors did not design Koin for preview contexts — lifecycle management is undefined
- Even a minimal KoinApplication requires all repository/VM bindings to be registered, which defeats the goal of a lightweight preview

**VM-as-parameter succeeds** because:
- Zero Koin in previews — no application context needed
- Screens are more testable (VMs can be unit-tested with FakeRepositories, no Koin injection needed)
- The pattern is idiomatic Compose — screens become reusable with any VM implementation
- FakeRepositories in `commonMain` make this work across all preview contexts

## Consequences

- All new screens MUST follow the `PublicScreen` / `PrivateContent` naming pattern
- `@Preview` composables are always `private` and call the `*Content` variant with manually constructed VMs
- FakeRepositories live in `commonMain/test/fakes/` (not `commonTest`) so `commonMain` previews can access them
- Do NOT introduce `koinViewModel()` inside any `@Preview` — CI/preview harness does not start Koin
