# Singularity Todo

Kotlin Multiplatform task manager with AI assistance. Targets Android and JVM Desktop (no iOS).

**Stack**: Compose Multiplatform · Room · Koin · Kermit · Supabase · Koog AI framework

<p align="center">
  <a href="https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html"><img src="https://shieldcn.dev/badge/Kotlin-Multiplatform.svg?variant=branded&theme=violet&logo=kotlin" alt="Kotlin Multiplatform" /></a>
  <img src="https://shieldcn.dev/badge/Android-JVM%20Desktop.svg?variant=secondary&logo=android" alt="Android and JVM Desktop" />
  <img src="https://shieldcn.dev/badge/Compose%20Multiplatform.svg?variant=secondary&logo=jetbrainscompose" alt="Compose Multiplatform" />
  <img src="https://shieldcn.dev/badge/MCP%20Server-37%20tools.svg?variant=secondary" alt="MCP server, 37 tools" />
</p>

<p align="center">
  <sub>CI: <a href="https://github.com/gazon1/sing/actions/workflows/ci.yml"><code>ci.yml</code></a> on <code>main</code></sub>
</p>

---

## Features

| Area | What's there |
|---|---|
| **Tasks** | Create, edit, delete, search, filter by status/project/tag, recurring tasks, reminders |
| **Notes** | Rich-text WYSIWYG editor (bold/italic/code/lists), export to HTML |
| **Projects** | Folder-like grouping, color + icon, task counts |
| **Tags** | Global tags, per-profile isolation |
| **Agenda** | Calendar view, daily/weekly schedule |
| **AI Assistant** | 37 Koog-powered tools: refine, decompose, cluster, generate descriptions, weekly planning |
| **Sync** | Supabase backend, HLC conflict resolution, offline-first |
| **Backup** | JSON export/import, per-profile |
| **MCP Server** | AI agent control via stdio (37 read/write/list tools) |
| **Multi-profile** | Isolated data per profile (Personal, AI Agent, etc.) |

---

## Licensing

This is an **open-core** project. The split is real, not aspirational, and it is
verified by a gate on every commit.

| | Licence | Built by default? |
|---|---|---|
| Everything outside `pro/` | [Apache-2.0](LICENSE) | **Yes** |
| `pro/` | [FSL-1.1-ALv2](LICENSE.pro) | No — needs `-PwithPro=true` |

A default build contains no `pro` code at all. `settings.gradle.kts` includes
`:pro` only when the build is run with `-PwithPro=true`, so:

```bash
./gradlew :androidApp:assembleDebug                      # Apache-2.0 only
./gradlew :androidApp:assembleDebug -PwithPro=true      # includes pro/
```

`scripts/check-pro-licence-boundary.py` enforces the direction of the dependency
(no Apache-2.0 file imports `com.singularity.todo.pro`), the absence of
non-OSI vendor dependencies from the free build, and that the free artifact
really is free — `--verify-apk` inspects a built APK at the dex level. A licence
split asserted only in a document is a claim the project cannot honour.

Under FSL-1.1-ALv2 the `pro/` modules convert to Apache-2.0 two years after they
are last used commercially.

### Provenance

Some code here is derived from other projects, and saying so is part of the
release rather than an apology for it. The task query language under
`feature/search/query/` derives from [Orgzly](https://github.com/orgzly/orgzly-android)
(GPL-3.0) and was **rewritten** against a written specification — see
[`docs/specs/search-query-grammar.md`](docs/specs/search-query-grammar.md). No
GPL or source-available code is vendored.

Every non-original file is classified in
[`docs/legal/PROVENANCE.md`](docs/legal/PROVENANCE.md), machine-readably in
`config/legal/provenance-registry.tsv`, and attested in
[`NOTICE`](NOTICE). `PORTED` — the one class that would have blocked
publication — is empty. No plagiarism detection was run, and most unmarked
`.kt` files are classified `ORIGINAL` by absence of markers rather than by
inspection; both limits are stated in the provenance document.

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
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to contribute: build commands, conventions, gates |
| [`SECURITY.md`](SECURITY.md) | Reporting a vulnerability privately, and the threat model |
| [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) | Behaviour expected of participants |
| `AGENTS.md` | Agent cheatsheet: project structure, DI patterns, test strategy, CLI |
| `ARCHITECTURE.md` | Full design doc (520+ lines) |
| `docs/decisions/DIGEST.md` | Auto-generated index of 490+ ADRs |
| `docs/doc-maintenance.md` | Documentation policy and ADR template |
| `docs/SKILLS-CATALOG.md` | Auto-generated index of 115 agent skills |
| `docs/decisions/*.md` | Individual architecture decision records |

Run `just docs-audit` to check doc freshness, normalize ADRs, and regenerate DIGEST.

---

## Tech choices

| Concern | Solution |
|---|---|
| DI | Koin 4.x pure DSL (NOT annotations) |
| Database | Room with auto-migrations (schema v38, `SCHEMA_VERSION` in `AppDatabase.kt`) |
| Async | Kotlin Coroutines + Flow |
| Logging | Kermit (multiplatform) |
| Date/Time | kotlinx-datetime |
| HTTP | OkHttp (JVM + Android) |
| AI | Koog framework (SimpleTool pattern) |
| Sync | HLC timestamps, conflict resolution, Supabase REST |

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html).
