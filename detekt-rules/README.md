# Detekt Rules

Custom detekt rules for this project. Rules target architecture violations that static analysis can catch automatically.

## Rules

| Rule | Severity | What it catches |
|---|---|---|
| `PassThroughUseCase` | error | CRUD use cases that only delegate to repository (no business logic) |
| `KDocOnContract` | warning | Missing KDoc on expect/actual, Repository interfaces, ViewModels |

## Build

```bash
./gradlew :detekt-rules:build   # Compiles rules and publishes to local maven
./gradlew :detekt-rules:test    # Test rules against fixture code
```

## Adding a new rule

1. Create `com.singularity.todo.detekt.rules.MyNewRule.kt` extending `Rule`
2. Add test in `src/test/kotlin/com/singularity/todo/detekt/rules/`
3. Register in `src/main/resources/config/detekt.yml` under `custom-rulesets`
4. Run `just detekt-baseline` to update baseline if new findings are expected

## Baseline management

```bash
just detekt-baseline   # Regenerate baseline files for shared + desktopApp
```

Baseline files are stored in project root and checked in. They suppress known violations so new ones stand out.
