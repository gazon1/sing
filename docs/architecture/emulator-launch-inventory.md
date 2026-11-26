# The emulator launch inventory — every path that can boot an AVD

## What this document is

Issue #3238 asked for a discovery pass before the emulator guard was built: what actually
launches an Android Emulator on this host, who calls it, what the real paths are, and what
the host can and cannot enforce.

This is that answer. It is an inventory, not a design. The guard itself is four other
issues — #3239 (dedicated agent UID), #3240 (protected install and KVM), #3241 (execution
confinement), #3242 (launcher service); this document is the evidence those four are written
against, and it is the list of paths that #3244 has to route.

**Everything here was read or measured on the host on 2026-11-26.** Where a fact is about
this machine rather than about the repository, it is marked *host fact*. Facts that could not
be measured are listed in [What this document does not claim](#part-8--what-this-document-does-not-claim).

## The one-line answer

There is exactly **one** place in the repository that builds an emulator command line:
`start_avd()` in `scripts/src/emulator/_startup.py`. Everything that boots an AVD in-repo
reaches it through `acquire_device()` in the same file. Two kinds of path do not: the raw
`emulator -avd …` (or the equivalent `emulator @AVD_NAME`) recipe quoted in the emulator-launch
skill and two ADRs, and an external MCP tool the skill mentions. Neither is reachable from
the repository's own code.

## Part 1 — Host facts

### 1.1 Distribution and kernel

| Fact | Value | How it was measured |
|---|---|---|
| Distribution | Fedora Linux 44 (Workstation Edition) | `cat /etc/os-release` |
| Kernel | Linux 7.2.10-200.fc44.x86_64 | `uname -r` |
| CPUs / RAM | 16 cores / 62 GiB | `nproc`, `free -g` |
| Python | 3.x with `scripts/src` as the project root | `scripts/src/pyproject.toml` |

### 1.2 Mandatory access control — this is the load-bearing fact

| Question | Answer | Evidence |
|---|---|---|
| Is AppArmor available? | **No.** | `/sys/module/apparmor` does not exist; `/sys/kernel/security/apparmor` does not exist; `aa-status`, `aa-enforce` and `apparmor_parser` are not installed |
| Is SELinux available? | **Yes**, and it is already enforcing | `sestatus` → `Loaded policy name: targeted`, `Current mode: enforcing`, `MLS status: enabled`, `Max kernel policy version: 35` |
| SELinux tooling present | `semanage`, `audit2allow`, `restorecon`, `setsebool`, `runcon`, `chcon` | `command -v` |
| `seinfo` | not installed (part of `setools-console`) | `command -v seinfo` |
| Current agent domain | `unconfined_u:unconfined_r:unconfined_t:s0-s0:c0.c1023` | `id` |

**This decides issue #3241.** Its scope says "prefer AppArmor when available; evaluate
SELinux where appropriate". On this host the AppArmor branch does not exist — there is no
AppArmor in the kernel at all, so no AppArmor profile could be loaded, let alone enforced.
The only MAC that can carry a mandatory boundary here is SELinux, in targeted policy, and
it must be written against the Fedora policy that ships with the distribution.

### 1.3 systemd, cgroups, KVM

| Fact | Value | Evidence |
|---|---|---|
| systemd | 259 (259.9-1.fc44), `+PAM +AUDIT +SELINUX -APPARMOR +IMA +IPE` | `systemctl --version` |
| User manager | `user@1000.service` running, `RuntimePath=/run/user/1000` | `systemctl --user is-system-running`, `loginctl show-user "$USER"` |
| cgroups | cgroup v2 (`cgroup2fs`), no memory or CPU limit on `user@1000.service` | `/proc/self/cgroup`, `memory.max`, `cpu.max` |
| `/dev/kvm` | `root:kvm`, mode **0666** | `ls -l /dev/kvm`, `getfacl /dev/kvm` |
| `/dev/kvm` usable by uid 1000 | **Yes** — opened `O_RDWR` successfully | direct open test |
| Display | `DISPLAY=:0`, `WAYLAND_DISPLAY=wayland-0`, `XDG_RUNTIME_DIR=/run/user/1000` | environment |

`/dev/kvm` being mode 0666 is why issue #3240 has real work to do: today the device is
readable and writable by *every* local account, not just the emulator owner. Restricting it
to the trusted launcher account and administrators is a permission change, not a policy
change.

### 1.4 Privilege escalation available to an agent — and not available

| Mechanism | State | Consequence for the guard |
|---|---|---|
| `sudo` | installed; `sudo -n true` → `sudo: a password is required` | an unattended agent **cannot** escalate. A guard may not rely on an agent running `sudo` |
| `pkexec` | installed | polkit needs an interactive authentication; unusable from an unattended agent |
| `docker` group | uid 1000 is in group `docker` (gid 969) | **root-equivalent.** See below |
| `wheel` group | uid 1000 is in group `wheel` (gid 10) | root-equivalent through `su`/`sudo` with the password |
| `kvm` group | uid 1000 is in group `kvm` (gid 36) | direct KVM access today |

**The `docker` group is the most important host fact in this section.** Membership in it is
root-equivalent: `docker run -v /:/host --privileged` reads and writes every file on the
host, including anything a root-owned protection scheme puts in place. Any plan that
protects the emulator install with file permissions is void for an agent that can run
`docker`. The dedicated agent UID of #3239 therefore has to be created **without**
`docker`, `wheel` and `kvm` membership, and the migration has to remove those groups from
the agent's supplementary group set — not merely add a new account.

### 1.5 `heavy` — the installed version, and its pool/slot/lock configuration

`heavy` is the machine-wide counting semaphore the repository uses for builds. Verified
against the **installed** binary, not assumed from the README.

| Fact | Value | Evidence |
|---|---|---|
| Installed version | `heavy 0.1.2` | `heavy --version` |
| mise pin | `mise.toml` declares `"github:mokevnin/heavy" = "latest"`; mise resolves it to `0.1.2` | `mise ls github:mokevnin/heavy` |
| Install path | `~/.local/share/mise/installs/github-mokevnin-heavy/0.1.2/` | `ls` |
| CLI flags | `--pool --slots --dir --no-skip-ci --timeout --on-timeout --log --status` | `heavy --help` |
| Env equivalents | `HEAVY_POOL`, `HEAVY_SLOTS`, `HEAVY_DIR`, `HEAVY_NO_SKIP_CI`, `HEAVY_TIMEOUT`, `HEAVY_ON_TIMEOUT`, `HEAVY_LOG` | `heavy --help` |
| Default pool | `default` | `heavy --status` |
| Slot directory | `$TMPDIR/heavy/<pool>` — on this host `/tmp/heavy/default` | `heavy --status` |
| Lock mechanism | an exclusive `flock` on a slot file in that directory | upstream README, `strings` on the binary shows `flock` |
| Default slot count | a quarter of the cores, at least one → **4** on 16 cores | `heavy --status` lists slots 0–3 |
| Config file | **none.** There is no `~/.config/heavy/` and no per-pool config file; pools are names, slots are `--slots`, and the directory is `--dir`/`HEAVY_DIR` | `ls ~/.config/heavy/` → does not exist |
| CI behaviour | when `CI` is set the command runs **immediately, with no slot** | `heavy --help` |
| Nesting | a command started inside a held slot sees `HEAVY_HELD` and runs at once | `heavy --help` |
| Timeout behaviour | `--timeout` then `--on-timeout fail` (exit 75) or `--on-timeout run` (run without a slot) | `heavy --help` |

`heavy --status` on this host at the time of writing:

```
pool 'default' in /tmp/heavy/default
slot 0: held by pid 1061480 for 6s: ./gw :shared:jvmTest …
slot 1: held by pid 1011104 for 666s: ./gw :shared:archTest …
slot 2: held by pid 1051389 for 143s: ./gw :shared:detekt
slot 3: free
```

**Two consequences for issue #3243** (a host-wide slot for the full emulator lifetime):

1. **The emulator is not under a slot today.** `heavy` wraps `./gw` (`gw:84-87`), the hk
   ktlint and compile steps (`hk.pkl:13,25`), `scripts/lint-changed.py:194-196` and
   `scripts/compile-changed.py:258-268`. The emulator is started by the Maestro path
   (`scripts/maestro-flows.py`, `.just/tests/mod.just`), which never mentions `heavy`. The
   emulator outlives every slot that is currently held.
2. **The slot directory follows `TMPDIR`, and the agent harness sets `TMPDIR` per
   project.** The repository's own `AGENTS.md` does **not** mention `TMPDIR`; the instruction
   to use a private `~/.cache/<project>-tmp` comes from the agent harness configuration that
   is loaded into every session, not from this checkout. `heavy` puts its slot files in
   `$TMPDIR/heavy/<pool>`, so two agents with different `TMPDIR` values use *different slot
   files* and do not exclude each other at all — the semaphore stops being machine-wide
   exactly when it matters. A guard that wants one machine-wide emulator slot has to pin the
   directory explicitly (`heavy --dir …`, or `HEAVY_DIR`) instead of relying on the default.

   **Measured on this host:** the running agent processes carry no `TMPDIR` in their
   environment (`/proc/<pid>/environ` of the live `minimax-code` processes has no `TMPDIR`
   entry), so today they all fall back to `/tmp` and share `/tmp/heavy/default`. The risk is
   therefore conditional, not observed: it appears the moment an agent or a wrapper starts
   exporting a per-project `TMPDIR`. The pinning in the sentence above is what makes the
   guard independent of that.

## Part 2 — The Android SDK paths

All of these are *host facts*; they are what a guard has to protect, and they are what an
agent can currently reach. Every one of them lies under the developer's home directory, so
the paths are given **relative to `$HOME`**, with the environment variable that resolves
them in the note column. `$HOME/Android/Sdk` is the measured location — the developer
directory itself is not published, but nothing about *where inside it* is hidden, so the
paths stay concrete enough to act on. The **measured** facts are all kept: the versions,
the uid and group ids, the file modes, the SELinux state and the AVD names. No credential,
token, key or password appears anywhere in this document.

| Thing | Path (relative to `$HOME`) | Note |
|---|---|---|
| SDK root | `Android/Sdk` | `$ANDROID_HOME` is set to it in the environment |
| Emulator (active) | `Android/Sdk/emulator/emulator` | version 37.2.13.0, build 16740729 |
| Emulator (second install) | `Android/Sdk/emulator-2/emulator` | version **37.1.11** — a second, older copy of the whole emulator package |
| Bundled QEMU | `Android/Sdk/emulator/qemu/linux-x86_64/` | `qemu-system-x86_64` and `qemu-system-x86_64-headless`, plus aarch64, armel, i386 variants |
| System QEMU | `/usr/bin/qemu-system-x86_64`, `/usr/bin/qemu-system-i386` | Fedora's QEMU 10.2.2 — **cannot boot an AVD**: `-avd` is rejected as an invalid option |
| adb | `Android/Sdk/platform-tools/adb` | 1.0.41 / 37.0.1-15733141 |
| Command-line tools | `Android/Sdk/cmdline-tools/latest/bin/` | contains `avdmanager`; `sdkmanager` is found by `find` under `cmdline-tools` |
| AVD home | `.android/avd` | `ANDROID_AVD_HOME` is unset on this host, so the default `$HOME/.android/avd` is used |
| AVDs present | `Medium_Phone`, `capture_9x16`, `suite_pixel6`, `suite_pixel6_sweep`, `suite_pixel6_sweep2`, `suite_pixel6_sweep3`, `suite_pixel6_sweep4` | `emulator -list-avds` |
| System images | `Android/Sdk/system-images/android-36/google_apis_playstore/x86_64/` and `android-36.1/…` | readable by uid 1000 |
| Platforms | `android-36`, `android-37.0`, `android-37.1` | |
| Build tools | `Android/Sdk/build-tools/` | |
| On `PATH` | `Android/Sdk/platform-tools` and `Android/Sdk/emulator` | so the bare name `emulator` resolves to the SDK binary |

`Medium_Phone` is the default AVD of the launcher (`scripts/src/emulator/_types.py:33`,
`AVD` env var, default `Medium_Phone`). `capture_9x16` is the capture device, and the
`suite_pixel6*` family is the Maestro sweep set.

**Ownership is the finding here.** The whole SDK — including `emulator/` and
`emulator-2/` — is owned by the developer (uid 1000), and the agents run as uid 1000. So today the
agents are the owner of the protected files: every permission-based isolation in #3240
starts from zero, and the SDK has to be re-rooted or moved to a root-owned location.

## Part 3 — Agent execution identity

### 3.1 How the agents are launched, and as whom

| Agent | Launcher | Effective UID | Groups | SELinux domain |
|---|---|---|---|---|
| Terminal coding agents (the `minimax-code` sessions on this host) | the desktop app's per-PTY daemon, which spawns the agent binary and gives it a shell | **1000 (the developer)** | **10 (`wheel`), 36 (`kvm`), 969 (`docker`), 1000 (the developer's own group)** | `unconfined_t` |
| The AFK runner's sessions (`docs/architecture/afk-runner.md`) | the `pi-afk-runner` extension, one issue → one worktree → one session | 1000 (the developer) — the runner runs as the developer | same as the developer | `unconfined_t` |
| In-app ACP agents (Gemini CLI / Claude Code / Codex bridges) | `SubprocessAcpAgentLauncher` in `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentLauncher.kt:78-99`, spawning `gemini --experimental-acp` / `npx @agentclientprotocol/claude-agent-acp` / the Codex bridge (registry in `AcpAgentProfile.kt`) | 1000 (the developer) — a child of the desktop app | same as the developer | `unconfined_t` |

Verified on the running processes:

```
Uid:    1000  1000  1000  1000
Gid:    1000  1000  1000  1000
Groups: 10 36 969 1000
```

and the cgroup of an agent process:

```
/user.slice/user-1000.slice/user@1000.service/orca_ptys_orca_daemon_….slice/orca_pty_….scope
```

### 3.2 What that means for the guard

- **Agents share the developer's UID.** There is no boundary between an agent and the human
  at the DAC layer today, and no boundary at the MAC layer either (`unconfined_t` is
  permitted to do everything the policy allows the developer to do). Every guarantee in
  #3239, #3240 and #3241 therefore starts from "the agent is the developer".
- **The agent is a member of `docker` and `wheel`.** As in §1.4, that is root-equivalent and
  defeats file-permission isolation on its own.
- **The agent is a member of `kvm` and `/dev/kvm` is 0666.** KVM access is available twice
  over.
- **The agent runs in `unconfined_t`.** For SELinux to confine it, it must run in a
  confined domain, and the agent UID must *not* be allowed to transition back to
  `unconfined_t` — otherwise the confinement is a suggestion the agent can decline. The
  standard Fedora mechanism for that is a dedicated confined domain entered by a login
  (`semanage login`) or a `runcon`/`setexeccon` transition for the dedicated UID, with
  `unconfined_login` handled so the interactive developer stays unconfined.
- **The cgroup is per-PTY**, one scope per agent session. That is convenient for #3242: a
  launcher service can put the emulator into its own scope and stop the whole tree, and
  agent processes are already separable by scope.

## Part 4 — The launch-path inventory

Every row was read in this checkout on 2026-11-26. "Kind" is one of:

- **boot** — starts an AVD process
- **reuse** — drives an already-running device, never boots one
- **provision** — creates the AVD, does not boot it

### 4.1 The canonical launch site

`scripts/src/emulator/_startup.py:241-253` builds the only argv in the repository:

```python
emulator_args = [
    "emulator", "-avd", _emu.cfg.AVD,
    "-no-snapshot",
    "-no-boot-anim",
    "-camera-back", "none",
    "-camera-front", "none",
    "-no-audio",
    *_emu.cfg.EXTRA_FLAGS,
]
if is_emulator_serial(pin):
    emulator_args += ["-port", pin.removeprefix("emulator-")]
```

Effective command shape:

```
emulator -avd "$AVD" -no-snapshot -no-boot-anim -camera-back none -camera-front none \
         -no-audio $EMULATOR_EXTRA_FLAGS [-port N]
```

It is executed through `_emu.popen` → `scripts/src/emulator/_device.py:93-103`
(`subprocess.Popen(args, stdout=fd, stderr=STDOUT, start_new_session=True)`), with stdout
and stderr going to `$EMULATOR_LOG` (default `/tmp/singularity-emulator.log`, previous run
rotated to `*.prev`, `scripts/src/emulator/_startup.py:228-231`). Up to three start attempts
are made, retried when the log contains `Failed to get` (`:255-256`, `:276-281`).

`EMULATOR_EXTRA_FLAGS` is appended **after** the hard-coded flags, so it can add `-gpu`,
`-no-window`, `-prop …` — or override them.

### 4.2 Paths that boot an AVD

| # | Path | Command / argv | Where | Caller | Must route through the launcher? |
|---|---|---|---|---|---|
| 1 | `start_avd()` | the argv in §4.1 | `scripts/src/emulator/_startup.py:185-299` | `acquire_device()` `:386` | yes — this **is** the launcher |
| 2 | `acquire_device()` | orchestrates #1 under the startup lock | `scripts/src/emulator/_startup.py:357-403` | `ensure_device()` `:406-414`; the Maestro CLI and runner | yes |
| 3 | `ensure-emulator` CLI | `ensure-emulator` → `emulator._cli:main` | `scripts/src/emulator/_cli.py:104-172`; console script at `scripts/src/pyproject.toml:29` | `python3 -m emulator`, `./rm emulator`, CI, skills | yes |
| 4 | e2e runner's device shim | `subprocess.run([sys.executable, "-m", "emulator"], env={SERIAL, PYTHONPATH=scripts/src})` | `scripts/run-maestro-e2e.py:84-112`, called at `:496` | `.github/workflows/e2e.yml` (shard job) | yes — it already is a launcher call |
| 5 | instrumentation runner's device shim | `subprocess.run([sys.executable, "-m", "emulator"], …)` | `scripts/run-android-ui-tests.py:203-238`, called at `:369` and re-acquired at `:445` | `.github/workflows/android-device-tests.yml` | yes |
| 6 | `./rm emulator` | `./rm emulator` → `ensure-emulator` | `rm:66-79` | CI `store-screenshots.yml`, the emulator-launch skill | yes |

### 4.3 Paths that reach the launcher indirectly

| # | Path | Where | Caller |
|---|---|---|---|
| 7 | Maestro main acquisition | `scripts/src/emulator/maestro/_cli.py:420` | `python3 -m emulator.maestro._cli`, `run-maestro` |
| 8 | Maestro recovery after an install failure (re-boots the AVD) | `scripts/src/emulator/maestro/_cli.py:114` | same |
| 9 | Maestro recovery after a device is lost mid-flow (re-boots the AVD) | `scripts/src/emulator/maestro/_runner.py:297` | same |
| 10 | `scripts/maestro-flows.py` | `scripts/maestro-flows.py:110-126` | hk `device` hook, `hk.pkl:231` |
| 11 | `just ui-maestro` | `.just/tests/mod.just:551-560` | a developer |
| 12 | `just gate-maestro` | `.just/tests/mod.just:565-579` | a developer |

### 4.4 Paths that only reuse a device

| Path | Where | Note |
|---|---|---|
| `scripts/android-instrumentation.py` | `:72-80`, `:111-120` | picks a serial, then runs Gradle `connectedDebugAndroidTest`; skips when no device is attached |
| `just device test` | `.just/android/device.just:28-36` | runs `./gw :androidApp:connectedDebugAndroidTest` against an already-attached device; boots nothing |
| Maestro bridge | `scripts/src/emulator/maestro/_bridge.py:99-101` | runs only allowlisted `adb shell` command shapes |
| Maestro flow engine | `scripts/src/emulator/maestro/_engine.py:244-257` | `maestro test <flow> --device <serial>` against a running device |
| `scripts/store-capture.py` | `:29-32` | `adb devices` only; the docstring says it does not boot |
| `just android::store-capture` | `.just/android/capture.just:9-20` | a thin wrapper over `scripts/store-capture.py`; boots nothing |
| `scripts/verify-large-restore.py` | whole file | adb only |
| `scripts/ci/setup-local-tools.sh` | whole file | installs the `ensure-emulator` console script into a venv; boots nothing |

The build and tooling files were checked too, not only the scripts: `hk.pkl`,
`mise.toml`, `androidApp/build.gradle.kts`, `shared/build.gradle.kts` and
`desktopApp/build.gradle.kts` contain no emulator invocation. The only hits are
comments (`androidApp/build.gradle.kts:89` mentions the emulator snapshot,
`shared/build.gradle.kts:103` mentions that Robolectric runs without one) and
the `ensure-emulator` / `run-maestro` console-script declarations in
`scripts/src/pyproject.toml:29-30`. The Gradle task
`:androidApp:connectedDebugAndroidTest` runs against whatever device is
attached — it never starts one.

### 4.5 Paths that provision an AVD without booting it

`scripts/ci/provision-avd.sh`:

```bash
sdkmanager "emulator" "platform-tools" "$SYSTEM_IMAGE"                        # :79
avdmanager create avd --force --name "$AVD_NAME" --package "$SYSTEM_IMAGE" \
          --device "$DEVICE"                                                  # :83-84
"$emulator_bin" -list-avds | grep -qx "$AVD_NAME"                             # :91-92
```

`ANDROID_AVD_HOME` defaults to `$HOME/.android/avd` (`:39`); `GPU_MODE=host` writes
`hw.gpu.enabled=yes` / `hw.gpu.mode=host` into the AVD's `config.ini` (`:116-133`).
Callers: `.github/workflows/_setup-avd.yml:181`, `.github/workflows/e2e.yml:324-331`,
`.github/workflows/android-device-tests.yml:246-253`,
`.github/workflows/store-screenshots.yml:151`.

**Provisioning is not launching**, but `sdkmanager "emulator"` *writes into the SDK tree*,
so it is a path by which an agent could restore the executable that #3240 protects.
Issue #3244 must route provisioning as well, or an agent that may provision may also write
the emulator package back.

### 4.6 The GPU policy, which a launch-time allowlist would have to reconcile

The GPU mode is deliberately split (ADR `2026-10-18-emulator-gpu-strategy-split`):

| Surface | GPU flag | Where |
|---|---|---|
| Maestro E2E | `-gpu ${{ env.EMULATOR_GPU_MODE }}` | `.github/workflows/e2e.yml:341-348` |
| Instrumentation | `-gpu swiftshader_indirect` | `.github/workflows/android-device-tests.yml:163-165` |
| Capture | `-gpu swiftshader_indirect`, with `GPU_MODE=host` in the AVD profile | `.github/workflows/store-screenshots.yml:173-185` |

Software rendering is a startup crash on this host — the render thread dies before the
window opens (ADR `2026-09-28-emulator-gfxstream-colorbuffer-segv`). Any guard that
rewrites or filters `-gpu` has to keep these three apart.

## Part 5 — Which paths must be routed, and which are intentionally unsupported

### 5.1 Must be routed through the launcher

| Path | Why |
|---|---|
| #1, #2, #3 — the launch core | the launcher *is* the place the guard hooks |
| #4, #5 — the runner shims | they call the launcher already; they need the launcher to become the guarded entry point |
| #6 — `./rm emulator` | same |
| #7, #8, #9 — the Maestro acquisition and recovery paths | they call `acquire_device()`, so they inherit the guard for free — but they are also the paths that **re-boot** a device mid-run, so they must not have a private way to do it |
| #10, #11, #12 — the developer entry points | they reach the launcher through #7 |
| §4.5 — provisioning | it can rewrite the SDK tree that the guard protects |
| `scripts/android-instrumentation.py` | reuse only, but it must be able to *ask* for a device when the instrumentation surface needs one |
| `just device test` (`.just/android/device.just:28-36`) | the Gradle instrumentation task on an attached device; reuse only, and it must reach the same guarded device selection as `scripts/android-instrumentation.py` |
| `just android::store-capture` (`.just/android/capture.just:9-20`) | reuse only — it drives `scripts/store-capture.py`, which refuses to boot and requires a named device |

### 5.2 Intentionally unsupported

| Path | Why it stays unsupported |
|---|---|
| The raw `emulator -avd …` (or `emulator @AVD_NAME`) recipe in the emulator-launch skill and in ADR `2026-09-29-emulator-launch-recipe.md` | it is a **documentation** recipe, not code. It cannot be routed; it has to be corrected or withdrawn, because an agent that reads it and types it bypasses everything. See §6.1 |
| The external MCP tool `mcp__android_emulator__android_start_emulator` | it is not part of this repository and it is not on this host. The skill already notes that it times out here. If it is ever connected, it becomes a first-class bypass and #3246 has to cover it |
| The bundled `qemu-system-*-headless` binaries | they are part of the protected emulator package and are covered by the same protection as `emulator` — not a supported entry point |
| `/usr/bin/qemu-system-x86_64` | it cannot boot an AVD (`-avd` is an invalid option), but it *can* boot an Android system image by hand with `-kernel`/`-initrd` if the agent has KVM and the readable system images. It is a residual risk, not a supported path |

## Part 6 — Bypasses and drift found during the inventory

### 6.1 The skill's launch recipe has drifted from the code

`.agents/skills/singularity-todo-emulator-launch/SKILL.md:93-97` (and ADR
`2026-09-29-emulator-launch-recipe.md:40-45`) quote:

```bash
emulator -avd Medium_Phone -no-snapshot-load -no-boot-anim \
  -camera-back none -camera-front none -no-audio > /tmp/emu.log 2>&1 &
```

The code uses `-no-snapshot` (`scripts/src/emulator/_startup.py:243`), not
`-no-snapshot-load`. The recipe also omits `EMULATOR_EXTRA_FLAGS` and the `-port` pinning,
and it hand-rolls a log path. The skill's own header says "what ensure-emulator executes —
do not retype it", and then it retypes it, wrongly. A guard that is enforced by the
filesystem would not stop an agent that types this, and the flag drift is proof that the
text is not kept in step with the code.

The same bypass exists in a second spelling: `emulator @AVD_NAME` is accepted by the
emulator binary as an alternative to `emulator -avd AVD_NAME` — verified on this host, both
spellings resolve the AVD the same way (`emulator @NoSuchAvdXYZ` and
`emulator -avd NoSuchAvdXYZ` produce the identical "Unknown AVD name" error). So any
allowlist that only covers the `-avd` spelling can be walked around with the `@` one. No
occurrence of the `@` spelling exists in this repository today — the finding is about the
shape of the guard, not about an existing path.

### 6.2 `EMULATOR_EXTRA_FLAGS` is an argument-injection channel

`scripts/src/emulator/_types.py:41-43` splits the env var with `shlex` and appends the
result to the argv after the hard-coded flags (`_startup.py:248`). An agent that can set
the environment can therefore add `-gpu host`, `-no-window`, `-prop …`, or anything else
the emulator binary accepts. Whatever the guard validates about the argv, it has to
validate this channel too — or drop the channel entirely.

### 6.3 The second emulator installation

`$ANDROID_HOME/emulator-2/` holds a complete, older emulator (37.1.11). A guard
that protects `$ANDROID_HOME/emulator/` alone leaves a fully functional emulator
behind it. The protection has to cover the whole SDK's emulator packages.

### 6.4 The MCP tool mentioned by the skill

`.agents/skills/singularity-todo-emulator-launch/SKILL.md:11` and
`.agents/skills/singularity-todo-adb-screen-unlock/SKILL.md:78` mention an external
`mcp__android_emulator__android_start_emulator` that "launches a virtual device". It is
not present on this host, and the skill records that it times out. It is listed here so
that #3244 and #3246 do not have to rediscover it: if that MCP server is ever connected,
it is a launch path that this repository does not control.

### 6.5 What already exists as a guard, and what it actually guards

| Mechanism | Where | What it does | Does it prevent a launch? |
|---|---|---|---|
| Physical-pin hard stop | `scripts/src/emulator/_startup.py:67-73`, used at `:370-375` | a `SERIAL` that is not `emulator-…` names a physical device; the launcher refuses to boot an AVD for it | **yes** — the only mechanism in the repository that stops a boot |
| `startup_lock` | `scripts/src/emulator/_startup.py:78-103` | an exclusive `flock` on `$TMPDIR/ensure-emulator.lock`, 60 s timeout; held across the re-check and the start | no — it serialises restarts |
| `_avd_is_running` / `_kill_avd` / `clear_stale_emulator` | `:108-158` | scans `/proc/*/cmdline` for `qemu-system` + `-avd` + the AVD name; kills a stale qemu and removes `*.lock` from the AVD directory | no — it is recovery |
| `DeviceLock` | `scripts/src/emulator/maestro/_lock.py` | one Maestro run per device serial, PID-owned, stale-lock takeover | no — it prevents concurrent runs on one device |
| `Policy` + `config/maestro/bridge-policy.yaml` | `scripts/src/emulator/maestro/_policy.py`, enforced at `_bridge.py:100` | a fail-closed allowlist of `adb shell` command shapes for the bridge | no — it limits what may run *on* a device |
| `scripts/check-workflow-env-context.py` | registered in `scripts/ci/static-gates.sh:709` | every `${{ env.NAME }}` in a workflow must name something the file defines | no — it prevents the "emulator binary not found" class of failure |

`Maestro/README.md:69-91` states that `SERIAL=$(ensure-emulator)` is the only supported way
to get a device. That is a documented convention, not an enforced gate.

## Part 7 — Validating the proposed enforcement against this distribution

Issue #3241 asks for the host's "supported mandatory access-control mechanism", preferring
AppArmor. Validated against what is actually installed:

| Proposed mechanism | Valid on this host? | Evidence and consequence |
|---|---|---|
| AppArmor profile on the agent tree | **No** | no AppArmor in the kernel (`/sys/module/apparmor` absent), no `aa-*` tooling. The AppArmor branch of #3241 is not implementable here |
| SELinux confinement of the agent tree | **Yes** | SELinux is enforcing, targeted policy, `semanage`/`audit2allow`/`setsebool`/`runcon` are installed. The agent must be moved out of `unconfined_t` into a confined domain, and the agent UID must not be permitted to return to `unconfined_t` |
| SELinux confinement of the launcher service | **Yes, with work** | the service needs its own domain: it must execute the emulator, open `/dev/kvm`, write the AVD directory, and hold an ADB port. `semanage` is present, so the type can be declared locally and tested with `audit2allow` |
| systemd service with a dedicated `android-emulator` UID, a Unix socket under `/run/android-emulator/`, and its own cgroup | **Yes** | systemd 259 with `+SELINUX`, `user@1000.service` running, `RuntimePath=/run/user/1000`, `systemctl --user` works, cgroup v2 with no limits that would block it. `RuntimeDirectory=` gives `/run/<name>` with the right ownership |
| A dedicated `agent` UID with no `docker`/`wheel`/`kvm` membership | **Yes** | `useradd`/`usermod`/`newgrp` are installed; group management is straightforward. The migration must **remove** `docker`, `wheel` and `kvm` from the agent's supplementary groups — group *removal* is the part that is easy to forget and it is what makes the file-permission isolation in #3240 mean anything |
| File-permission isolation of the emulator install and `/dev/kvm` | **Yes** | `/dev/kvm` is 0666 today, and the SDK is owned by the agent's own UID. Both must change; `restorecon` is available to keep the labels right afterwards |
| `sudo`-based or `pkexec`-based escalation from an agent | **No** | `sudo -n true` demands a password and `pkexec` needs polkit authentication. An unattended agent cannot use either, so no guard step may depend on it |
| A container boundary for the agent (no KVM, no protected SDK tree) | **Tooling only — not validated** | `podman`, `docker`, `bwrap`, `systemd-nspawn` and `unshare` are installed, so the *mechanism* is available. That is all this row claims: no container was created and no isolation was tested (§8). Installed binaries are not evidence of a working boundary — the agent is in the `docker` group today, so `docker` in particular must not be read as a fallback. Whether an unprivileged agent can build a container that maps neither `/dev/kvm` nor the SDK tree, and whether the runtime's defaults deny that, is untested and is #3241's question |
| `heavy` as the host-wide slot for the emulator's lifetime | **Partly** | `heavy 0.1.2` has no config file: pools are names, slots are `--slots`, the directory is `--dir`/`HEAVY_DIR`, and the lock is a `flock` in `$TMPDIR/heavy/<pool>`. The directory has to be pinned explicitly, because the agent harness sets a per-project `TMPDIR` and that silently splits the slots. On this host the live agents carry no `TMPDIR`, so the split is a latent risk rather than an observed one (§1.4) |

**The one hard limit.** `docker` group membership is root-equivalent. Any MAC or file
permission scheme is defeatable by an agent that can run `docker --privileged -v /:/host`.
So the ordering is not negotiable: the agent UID has to lose `docker` (and `wheel`, and
`kvm`) **before** the emulator install and `/dev/kvm` are protected, or the protection
measures nothing.

## Part 8 — What this document does not claim

- **No emulator was booted and no test was executed.** Everything about runtime behaviour —
  the flag merging, the flock timeout, the readiness probes, whether the guarded paths
  still work — is read from source and from the host's own state.
- **The CI side is not the host side.** CI runs on GitHub-hosted runners with a different
  distribution, a different SDK layout and no KVM-in-a-`kvm`-group. The inventory in §4
  covers CI because CI is a caller of the launcher, but the host facts in §1 and §2 are
  about this machine only.
- **`unconfined_t` is a starting point, not a design.** §1.2 and §3.2 say the agent runs
  unconfined; what the confined domain should be, and how the transition is triggered for
  every entry point in §3.1, is #3241's decision.
- **The MCP tool was not exercised** — it is not installed on this host.
- **This document does not choose between the guard issues.** It supplies the evidence for
  them and classifies the paths. Where it says "must route", that is a statement about
  which paths reach an AVD, not a decision to change them.
- **The completeness of this inventory is not enforced by anything.** The tables in §4 are
  maintained by hand and the commands in §9 are instructions for a maintainer, not a gate:
  a new launch path can be added to the repository without this document changing, and
  the document will still claim that `start_avd()` is the only launch site. Nothing in
  `hk.pkl`, `mise.toml` or the CI workflows checks the tables against the source. That is
  a deliberate scope decision for #3238 — a discovery issue that asks for the inventory,
  not for a generator — and it is the reason #3244 has to re-derive the paths from the
  source rather than trust this document.

## Part 9 — How to re-verify this inventory

```bash
# the launch core and the only argv in the repository
grep -rn "emulator_args" scripts/src/emulator/
grep -rn "acquire_device\|ensure_device" scripts/src/emulator/ scripts/ .github/ .just/ hk.pkl

# every path that mentions an emulator, an AVD or a qemu, in code *and* in config
# (`emulator @` is the second spelling of a launch: the AVD name after an @)
# (*.kt and *.java are in the list because the in-app ACP agent launcher spawns
#  processes from Kotlin — a launch could be written there)
grep -rn --include='*.py' --include='*.sh' --include='*.yml' --include='*.yaml' \
  --include='*.just' --include='*.md' --include='*.kts' --include='*.gradle' \
  --include='*.pkl' --include='*.toml' --include='*.properties' \
  --include='*.kt' --include='*.java' \
  -E 'emulator-headless|emulator @|qemu-system|avdmanager|ANDROID_AVD_HOME|-avd ' \
  scripts/ .github/ Maestro/ .just/ docs/ .agents/ androidApp/ shared/ desktopApp/ hk.pkl mise.toml

# the repository-root wrappers (./rm emulator is launch path #6) — no extension,
# so they are named explicitly rather than matched by a filter
grep -n -E 'emulator|avd' rm gw

# the host facts of §1 and §2
heavy --version; heavy --status
sestatus; getsebool -a | grep -iE 'android|virtual'
systemctl --version; systemctl --user is-system-running
ls -l /dev/kvm; getfacl /dev/kvm
cat /etc/os-release; uname -r
ls -l "$ANDROID_HOME/emulator/emulator" "$ANDROID_HOME/emulator-2/emulator"
"$ANDROID_HOME/emulator/emulator" -list-avds

# the agent identity of §3
ps -eo user,pid,cmd | grep -E 'minimax-code|gemini|claude|codex'
for p in $(pgrep -x minimax-code | head -1); do grep -E '^(Uid|Gid|Groups)' /proc/$p/status; cat /proc/$p/cgroup; done
id
```

**Re-verification is cheap.** Every row of the inventory is a `grep` over a directory that
does not change often, and every host fact is a single command. When a row stops matching,
this document is wrong and says so — which is the point of writing the command that proves
it.
