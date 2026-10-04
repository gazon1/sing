# lint-scope-policy

## ADDED Requirements

### Requirement: REQ-1 Every source set a module declares is scanned by detekt, and the scan list is verified against the filesystem

`detekt.source` for each module SHALL list every source set that directory
actually contains. A source set that exists on disk and is absent from the list
SHALL be a gate failure, not an unscanned gap: detekt reports on what it is told
to scan and is silent about what it is not.

Each entry in `source.setFrom` SHALL name a directory that exists. A typo in the
list is otherwise indistinguishable from an intentional omission — detekt ignores
it without complaint.

The check SHALL compare the declared list against the directories present under
`src/`, and SHALL name both the missing source set and the entry that resolves to
nothing.

**Rationale:** `androidApp` declared `src/main/kotlin` and
`src/androidTest/kotlin` and omitted `src/debug`, so `DebugSeedActivity.kt` had
never been linted — the only source set the module skips. The same list named
`src/androidAndroidTest/kotlin`, which does not exist and was silently ignored.
Neither defect produces a detekt finding, which is precisely why they survived:
the configuration is the thing being wrong, and the tool reports on code rather
than on its own configuration.

#### Scenario: A module gains a source set

- **Given** `androidApp` declares `src/main/kotlin` and `src/androidTest/kotlin`
- **When** a new `src/debug/kotlin` tree is added
- **Then** the scan-list check fails
- **And** it names `src/debug` as present on disk and absent from
      `detekt.source`

#### Scenario: The scan list names a directory that does not exist

- **Given** `detekt.source` contains `src/androidAndroidTest/kotlin`
- **When** the scan-list check runs
- **Then** it fails
- **And** it names the entry, because an unresolvable path and an intentional
      omission look the same to detekt

#### Scenario: The list is complete

- **Given** a module whose `detekt.source` lists every directory under `src/`
- **When** the check runs
- **Then** it passes
- **And** it is not vacuous: the test fails when a directory is removed from the
      list, which is the whole defect it exists to catch
