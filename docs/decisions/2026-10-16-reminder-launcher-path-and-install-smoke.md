---
title: "The reminder launcher path is /opt/<packageName>/bin/, and a package is proven by installing it"
date: 2026-10-16
status: accepted
deciders: max
tags: ["release", "packaging", "reminders", "ci"]
---

## Context

Two claims about the packaged desktop app were wrong, and both were believed by tests.

**1. The reminder unit ran a path no package creates.** `JvmReminderScheduler` armed
reminders with

```
systemd-run --user --unit=… --on-calendar=@… -- /usr/bin/singularity-todo fire-reminder <id> <userId>
```

`JvmReminderSchedulerLauncherPathTest` asserted that constant equalled
`/usr/bin/${packageName}` read from `desktopApp/build.gradle.kts`, and its docstring said
it matched "what the Deb actually installs". It never read a Deb. Reading the published
`v0.2.1-rc.1` artifacts on 2026-10-16:

```
$ dpkg-deb -c singularity-0.2.1-rc.1-linux-x64.deb | grep bin/singularity
-rwxr-xr-x root/root 17688 ./opt/singularity-todo/bin/singularity-todo
$ rpm -qlp singularity-0.2.1-rc.1-linux-x64.rpm | grep bin/singularity
/opt/singularity-todo/bin/singularity-todo
```

No `/usr/bin/singularity-todo`, and no symlink. Every reminder on every installed machine
failed to start. The test compared a constant with a constant, both derived from the same
false premise — which is why it kept passing.

**2. The binaries reported 0.1.0 inside a 0.2.1 package.** `nativeDistributions` takes
`packageVersion`; `-Dsingularity.version` came from a separate `desktopAppVersion`
property that the release workflow never set. So `dpkg -f` said `0.2.1rc.1` while
`appVersion().name` — what the UI shows and what the update gate compares against the
releases feed — said `0.1.0`, forever.

Neither was findable by the release pipeline, because it verified artifacts by reading
headers and nothing else. Both of the release checks that had ever fired were wrong about
correct artifacts: `keytool -printcert -jarfile` reads only a v1 signature an
`minSdk 26` APK does not have, and an RPM check compared a version against jpackage's
own `RELEASE=1`.

## Idea

Two independent fixes: state the real path, and prove packages by using them.

## Decision

1. `REMINDER_LAUNCHER_PATH = "/opt/singularity-todo/bin/singularity-todo"`. The
   expectation in `JvmReminderSchedulerLauncherPathTest` is now `/opt/<packageName>/bin/
   <packageName>`, plus an assertion that the constant is *not* the `/usr/bin` location
   nothing installs — so the false premise cannot return unnoticed.
2. `desktopAppVersion` and `desktopAppVersionCode` are now passed by `release.yml` from
   `meta`'s outputs, alongside `packageVersion`.
3. `singularity-todo --version` prints the running version and exits 0, before Koin,
   before the database, before AWT.
4. A new blocking step, *Install and smoke the packaged launcher*, installs the `.deb` in
   `ubuntu:24.04` and the `.rpm` in `fedora:41` and asserts: the package manager installs
   it; the launcher is listed by the package database at the path above and is
   executable; `--version` exits 0; and it prints the version the tag named. It lives in
   `scripts/release/install-and-smoke.sh`, runs in a container, and mounts only the
   package and itself — never the Gradle tree.

## Rationale

`--version` is what makes a headless smoke possible at all. The app's other headless entry
point, `fire-reminder`, reaches the database, so its failure mode would say nothing about
packaging. The flag must return before anything else in `main`: a `--version` that had to
start the application would be useless in a container, because its failure would be
indistinguishable from the container having no display.

Container rather than the runner, so the check is about the package. The script has to
create `/usr/share/desktop-directories` and a non-empty `XDG_DATA_DIRS` first: jpackage's
`postinst` calls `xdg-desktop-menu`, which exits 3 without them and fails the whole
install. That "menu directory" is the `desktop-directories` directory, not
`applications` — the obvious guess is wrong and fails identically. The workaround is in
the script rather than fixed in the package because the underlying defect is real and
tracked separately (#686): headless Debian, which is exactly where a reminder-only
install belongs, cannot install this `.deb`.

PATH is deliberately **not** asserted. jpackage creates no `/usr/bin` symlink, so nothing
is on PATH, and asserting it would assert a packaging decision nobody made. What is
asserted is that the path the code depends on is the path the package installs.

The header checks stay. They are cheap and catch a mis-staged or mis-tagged artifact
early; the install check proves something they cannot.

## Consequences

- A reminder on a packaged install now has a path that exists. Anyone who already
  installed `0.2.1-rc.1` had no working reminders; there is nothing to migrate.
- Two version properties, not one. `packageVersion` answers "what does the package call
  itself" and must satisfy rpm *and* deb; `desktopAppVersion` answers "what does the code
  inside report". They can still diverge — and now the smoke step fails if they do.
- Each release run pays one container image pull and one install per format.
- `check-gate-wiring`'s premise audit is the general lesson here: a control whose two
  halves come from the same unverified belief certifies nothing while reading as coverage.

## Links

- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/reminders/JvmReminderScheduler.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/reminders/JvmReminderSchedulerLauncherPathTest.kt`
- `scripts/release/install-and-smoke.sh`, `.github/workflows/release.yml`
- `desktopApp/src/main/kotlin/com/singularity/todo/main.kt` (`--version`)
- `docs/decisions/2026-10-07-desktop-reminders-systemd-user-timers.md` — recorded the
  `/usr/bin` premise this corrects; left unmodified, as a closed ADR is a record of what
  was believed then
- #664, #686