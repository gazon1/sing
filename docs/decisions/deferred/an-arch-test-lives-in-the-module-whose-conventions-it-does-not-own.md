---
title: "An Arch Test Lives In The Module Whose Conventions It Does Not Own"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** #301
**Supersedes:** #186 (closed — remaining decision captured in #301)

**Found in:** 2026-10-05, closing #153 — the duplicated-test-helpers issue whose
premise turned out to be false, so the real defect had to be looked for.

**Situation.** `ViewModelTestCoverageTest` lives in `:shared` and reads
`:desktopApp`'s test sources through `System.getProperty("desktopAppJvmTest.root")`,
passed from `shared/build.gradle.kts`. Unlike the test that was moved out in
`08f6cd1a`, this one is *legitimate*: it matches a production ViewModel against
every test class that mentions it, so it genuinely aggregates across modules and a
relative path is not available to it.

**What is left.** The property is a string path between modules that neither
Gradle nor the compiler knows about. A rename breaks it at runtime with
`desktopAppJvmTest.root is not set`, and the comment naming the one remaining
reader is the only thing keeping that honest. `check-test-task-inputs.py` covers
the *staleness* half of the hazard (the tree is a declared task input, so a
desktopApp edit invalidates `:shared:jvmTest`) — it does not cover the *naming*
half.

**Already ruled out.** `testFixtures` is not the answer, for the same reason it was
not the answer in #153: this is a file scan, not a shared declaration. Making the
property a Gradle-projected value would be the alternative, and it is real work
for one remaining reader.

**Try next:** only if a second cross-module scan appears — which #154's tag
unification would likely produce. Then a small shared scan-root provider in
`jvmTestFixtures` pays for itself. With one caller it is ceremony.

---
