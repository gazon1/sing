---
title: "The JVM test database has one owner, and a lexical gate keeps it that way"
date: 2026-11-06
status: accepted
tags: ["tests", "database", "architecture", "gates"]
---

# The JVM test database has one owner, and a lexical gate keeps it that way

## Context

Forty-five files under `shared/src/jvmTest` built a real database themselves: a
temporary directory, a path inside it, `AppDatabaseFactory.build(createSqlDriver(),
path)`, and a `tearDown` that closed the database and deleted the directory. The
copies had drifted — some built in `@BeforeTest`, some in a `lazy`, some named the
file `singularity.db` and some `tasks.db`, some closed the database and some did
not, and at least one left a directory behind on every run.

The fixture from the previous piece (`DatabaseTestFixture`) was the shape the tests
should move to, but nothing in the tree said so. A new test copied the nearest
example, and the copies grew back.

## Idea

- One fixture owns both questions — is the database in a file or in memory, and who
  closes it — and the tests ask it instead of answering for themselves.
- A test that checks database behaviour takes `inMemory()`; a test whose subject is
  the file (a migration, a restart, WAL, "the file exists") takes `onDisk()`.
- A lexical architecture gate states the rule mechanically. It reads sources, not
  compiled classes, because the rule is about where a decision is written down.
- The gate names the fixture as the owner by **path**, so "the fixture was copied"
  is reported as a copy, separate from "the fixture is wrong".

## Decision

`DatabaseTestFixture` lives at
`shared/src/jvmTest/kotlin/com/singularity/todo/core/database/DatabaseTestFixture.kt`
and is the only file allowed to open a test database or own a temporary database
directory. It offers `onDisk()`, `inMemory()`, `database(name)`, `path(name)`,
`reopen(name)`, `connect(name)` and `directory()`, and `close()` closes everything
it handed out.

`DatabaseTestFixtureHasOneOwnerTest` (`:shared:archTest`, fast-tagged) checks three
things over `jvmTest` **and** `commonTest` (the latter executes inside
`shared:jvmTest`):

- no file outside the owner opens a database (`AppDatabaseFactory`,
  `databaseBuilder`, `buildInMemoryAppDatabase`, `createSqlDriver`);
- no file outside the owner manages a temporary directory that also references a
  database (the anchor is a database reference, not a `.db` spelling, so renaming
  the file does not hide it);
- exactly one `DatabaseTestFixture.kt` exists, at the owner path, and the matchers
  do match it (the positive control).

## Rationale

The alternative was a compiler-based rule (Konsist or a detekt rule). It would see
through aliases and helpers, but it would also need type resolution for test
sources, and every source-reading gate in this repository is lexical on purpose:
cheap, deterministic, and readable in a minute. The guard's limits are written down
instead of hidden: it cannot see an alias (`val f = AppDatabaseFactory`), a wrapper
in another test file, or an inference. That is accepted, because the common case is
a copied block of boilerplate, and the gate catches exactly that.

The owner path is pinned, not the file name. Name-pinning was the first version's
bug: a second copy in another package was exempt from both rules, which is the
failure the gate exists to report.

## Consequences

- The `slow` suite no longer pays for a file per test where the file was not the
  subject; the migration of the first 45 files cut the local suite to under half a
  minute.
- A test that needs a second database, a restart, or a raw connection has a named
  way to ask for it, so those needs do not justify a new copy.
- A legitimate new use (a second owner, a different lifecycle) now has to change
  the gate and the fixture together. That is the point.
- `AppDatabaseFactoryJvmTest` was renamed to `JvmDatabaseSeamTest` so the factory's
  own test no longer needs a path exemption from the banned-token rule.
- The guard strips comments before matching, so a KDoc that mentions a forbidden
  call is not a finding.

## Links

- #1899, #2503
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/DatabaseTestFixture.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/DatabaseTestFixtureHasOneOwnerTest.kt`
- `docs/architecture/tests.md`
