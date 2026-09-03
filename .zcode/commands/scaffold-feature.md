# /scaffold-feature — Scaffold a new CRUD feature

Creates a complete new feature module following the canonical 7-file pattern:
Ids.kt → Domain.kt → Repository.kt → RepositoryImpl.kt → UseCase.kt → ViewModel.kt → Screen.kt

Then registers the ViewModel in Modules.kt and adds a bottom nav item in Navigation.kt.

## Usage

```
/scaffold-feature <FeatureName>
```

- `FeatureName`: PascalCase name of the feature (e.g., `Event`, `Reminder`, `Label`)

## What it creates

For a feature named `<Feature>`:

```
shared/src/commonMain/kotlin/com/singularity/todo/feature/<feature>/
├── Ids.kt                  — @JvmInline value class for typed ID
├── <Feature>Domain.kt     — Domain model + validation
├── <Feature>Repository.kt — Interface (suspend CRUD + Flow reads)
├── <Feature>RepositoryImpl.kt — Room implementation
├── <Feature>UseCase.kt    — ONLY real logic (no pass-through)
├── <Feature>ViewModel.kt   — StateFlow<SealedUiState> + Intent
└── <Feature>Screen.kt     — Compose UI
```

Also:
- Registers VM in `core/di/Modules.kt` (or via Koin Annotations when migrated)
- Adds `BottomNavItem.<Feature>` to `Navigation.kt`
- Creates co-located test: `shared/src/jvmTest/kotlin/.../<Feature>ViewModelTest.kt`
- Creates test fake: `commonMain/test/fakes/Fake<Feature>Repository.kt`

## Files modified

- `shared/src/commonMain/.../core/di/Modules.kt` — add VM factory
- `shared/src/commonMain/.../feature/nav/Navigation.kt` — add nav item
- `shared/src/commonMain/.../feature/<feature>/` — create all 7 files

## Naming conventions

| Entity | File/class names |
|---|---|
| Event | `Ids.kt` → `EventId`, `EventDomain.kt` → `Event`, `EventRepository.kt` |
| Reminder | `ReminderId`, `ReminderDomain.kt` → `Reminder` |
| Label | `LabelId`, `LabelDomain.kt` → `Label` |

## Requirements before use

1. Follow the skill `singularity-todo-feature-scaffold` for the exact code pattern.
2. Ensure Room schema migration is added if the feature has new entities.
3. Add Koin bindings in Modules.kt.
4. Add navigation route in Navigation.kt.
5. Write at least one smoke test.

## Example

```
/scaffold-feature Event
```

Creates `feature/event/Ids.kt`, `EventDomain.kt`, `EventRepository.kt`, `EventRepositoryImpl.kt`, `EventUseCase.kt`, `EventViewModel.kt`, `EventScreen.kt`, `EventViewModelTest.kt`, and `FakeEventRepository.kt`.
