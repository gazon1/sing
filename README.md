# Singularity Todo

Kotlin Multiplatform task manager with AI assistance. Targets Android and JVM Desktop (no iOS).

**Stack**: Compose Multiplatform · Room · Koin · Kermit · Supabase · Koog AI framework

---

## Features

| Area | What's there |
|---|---|
| **Tasks** | Create, edit, delete, search, filter by status/project/tag, recurring tasks, reminders |
| **Notes** | Rich-text WYSIWYG editor (bold/italic/code/lists), export to HTML |
| **Projects** | Folder-like grouping, color + icon, task counts |
| **Tags** | Global tags, per-profile isolation |
| **Agenda** | Calendar view, daily/weekly schedule |
| **AI Assistant** | 32 Koog-powered tools: refine, decompose, cluster, generate descriptions, weekly planning |
| **Sync** | Supabase backend, HLC conflict resolution, offline-first |
| **Backup** | JSON export/import, per-profile |
| **MCP Server** | AI agent control via stdio (32 read/write/list tools) |
| **Multi-profile** | Isolated data per profile (Personal, AI Agent, etc.) |

---

## Quickstart

### Build

```bash
# Android
./gradlew :androidApp:assembleDebug

# Desktop
./gradlew :desktopApp:run

# Run tests
./gradlew :shared:jvmTest
./gradlew :shared:testAndroidHostTest

# Full check (tests + lint + assemble)
./check.sh
```

### Working in parallel worktrees

Use `./gw` instead of `./gradlew` when more than one checkout builds at the same
time:

```bash
./gw :shared:jvmTest
./gw koverReport
```

It points `GRADLE_USER_HOME` at a private directory inside the worktree, so each
checkout owns its daemon registry and `./gradlew --stop` can no longer kill a
neighbour's build. The Gradle distribution, dependency caches and toolchain JDK
stay shared through symlinks — nothing is re-downloaded. `direnv` users get the
same thing from the committed `.envrc`.

One command when you create a worktree:

```bash
git worktree add ../my-worktree -b my-branch
./scripts/setup-worktree.sh   # run inside ../my-worktree
```

It also points `core.hooksPath` at that worktree's own `.githooks`. A single
absolute value in the shared config otherwise makes every worktree run the hooks
of one checkout — silently, and in the wrong directory.

Never run `./gradlew --stop` in a shared home; if a daemon must go, stop it
through `./gw --stop`. See
[`docs/decisions/2026-10-04-gradle-daemon-isolation.md`](docs/decisions/2026-10-04-gradle-daemon-isolation.md).

### Dogfooding with AI agent

```bash
# Build MCP server
./gradlew :mcp-server:build

# Connect ZCode / Claude Code / Cursor to:
java -jar mcp-server/build/libs/mcp-server-jvm-*.jar --profile=ai-agent
```

See `AGENTS.md` for the full agent cheatsheet (architecture, DI patterns, test strategy, CLI).

---

## Architecture

- **`shared/`** — all shared code (commonMain + androidMain + jvmMain + tests)
  - `core/` — infrastructure: database, auth, backup, sync, DI, notifications, security
  - `feature/` — UI features: tasks, notes, projects, tags, search, AI, settings, agenda, calendar
- **`androidApp/`** — Android shell (MainActivity, manifest)
- **`desktopApp/`** — Desktop Compose entry
- **`mcp-server/`** — AI agent MCP server (Koog → MCP adapter)

See `ARCHITECTURE.md` for the full design doc (package maps, expect/actual table, layer boundaries, refactoring playbook).

---

## Documentation

| File | What |
|---|---|
| `AGENTS.md` | Agent cheatsheet: project structure, DI patterns, test strategy, CLI |
| `ARCHITECTURE.md` | Full design doc (520+ lines) |
| `docs/decisions/DIGEST.md` | Auto-generated index of 270+ ADRs |
| `docs/doc-maintenance.md` | Documentation policy and ADR template |
| `docs/SKILLS-CATALOG.md` | Auto-generated index of 90+ agent skills |
| `docs/decisions/*.md` | Individual architecture decision records |

Run `just docs-audit` to check doc freshness, normalize ADRs, and regenerate DIGEST.

---

## Tech choices

| Concern | Solution |
|---|---|
| DI | Koin 4.x pure DSL (NOT annotations) |
| Database | Room with auto-migrations (schema v1 → v12+) |
| Async | Kotlin Coroutines + Flow |
| Logging | Kermit (multiplatform) |
| Date/Time | kotlinx-datetime |
| HTTP | OkHttp (JVM + Android) |
| AI | Koog framework (SimpleTool pattern) |
| Sync | HLC timestamps, conflict resolution, Supabase REST |

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html).
