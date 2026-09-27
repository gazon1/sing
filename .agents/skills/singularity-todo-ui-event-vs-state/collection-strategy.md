# `.first()` snapshot vs continuous `.collect()` for lists

## `.first()` Snapshot vs Continuous `.collect()` for Lists in Compose

When a screen loads a list of items (projects, tasks, notes), two patterns exist: **snapshot** (load once with `.first()`) and **continuous** (collect the Flow). Use the right one.

### The snapshot pattern (❌ anti-pattern)

```kotlin
@Composable
fun ProjectPickerSheet(...) {
    val projectsRepo: ProjectsRepository = koinInject()

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    LaunchedEffect(Unit) {
        val userId = settingsRepo.userId.first()       // snapshot of userId
        projects = projectsRepo.watchProjects(userId).first()  // snapshot of list
    }
}
```

**Problems:**
- `settingsRepo.userId` is a `Flow<UserId>` — taking `.first()` loses reactivity to profile switches
- `projectsRepo.watchProjects(userId)` is a reactive `Flow<List<Project>>` — taking `.first()` means new projects created while the sheet is open are NOT shown
- If user creates a project in another screen while this picker is open, they must close and reopen to see it

### The continuous pattern (✅ correct)

```kotlin
@Composable
fun ProjectPickerSheet(...) {
    val projectsRepo: ProjectsRepository = koinInject()
    val currentUser: ProfileAwareCurrentUser = koinInject()

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }

    val userId by currentUser.scopedUserId.collectAsStateWithLifecycle()
    LaunchedEffect(userId) {
        projectsRepo.watchProjects(userId.value).collect { projects = it }
    }
}
```

**Why this is correct:**
- `currentUser.scopedUserId` is a `StateFlow<UserId>` — collecting it with `collectAsStateWithLifecycle()` re-triggers when the profile switches
- `projectsRepo.watchProjects(userId)` is collected continuously — any new project appears automatically without reopening
- The `LaunchedEffect(userId)` key ensures we re-collect when the user changes

### Decision tree

```
Is the data source a Flow?
  → YES → Is the value needed once (e.g. export, print, one-shot action)?
      → YES → `.first()` is fine
      → NO  → Use `collectAsStateWithLifecycle()` + `LaunchedEffect(key)` for re-collection on parameter changes
  → NO (it's a suspend function or blocking call) → `.first()` or explicit suspend call is appropriate
```

### Inline create — don't re-snapshot after writing

```kotlin
// ❌ WRONG — unnecessary re-fetch after create
scope.launch {
    projectsRepo.create(newProject)
    projects = projectsRepo.watchProjects(userId).first()  // re-fetches everything
}

// ✅ CORRECT — the Flow already emits the new list after create
scope.launch {
    projectsRepo.create(newProject)
    newProjectName = ""
    isCreating = false
    // No re-fetch needed — the Flow from collect {} will receive the update automatically
}
```

Room's reactive `Flow` emits a new value automatically when the backing data changes. After `projectsRepo.create()`, the `collect` block receives the updated list — no manual re-fetch needed.

### Profile switch — re-collect with `LaunchedEffect(key)`

When using `ProfileAwareCurrentUser`, always key `LaunchedEffect` on the `userId`:

```kotlin
val userId by currentUser.scopedUserId.collectAsStateWithLifecycle()
LaunchedEffect(userId) {
    projectsRepo.watchProjects(userId.value).collect { projects = it }
}
```

Without the key, switching profiles would not restart the collection — the new user's projects would never load.

### Anti-pattern: `LaunchedEffect(Unit)` with `collect {}`

```kotlin
// ❌ WRONG — collects forever, never restarts
LaunchedEffect(Unit) {
    repo.watchProjects(userId).collect { projects = it }
}

// ✅ CORRECT — key on the actual dependency
LaunchedEffect(userId) {
    repo.watchProjects(userId.value).collect { projects = it }
}
```

The `Unit` key means "never restart". If the underlying data source changes, the collector stays on the old data. Always key `LaunchedEffect` on the minimum set of parameters that, when changed, require a fresh collection.

---
