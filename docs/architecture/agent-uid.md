# The agent account — how a coding agent runs as somebody else

## What this document is

Issue #3239: every coding agent in scope runs under a dedicated unprivileged UID, so that
the rest of the emulator guard (#3240 the emulator install and `/dev/kvm`, #3241 execution
confinement, #3242 the launcher service) has a boundary to build on. Without it, an agent
*is* the developer — same UID, same `wheel`/`kvm`/`docker` groups, same `unconfined_t` —
and a file permission that protects the emulator from the developer's own UID protects
nothing.

This is the **setup and migration procedure**, the **rollback**, and the honest limits.
The evidence it is written against is
[`emulator-launch-inventory.md`](emulator-launch-inventory.md); the decision is
`docs/decisions/2026-11-26-agents-run-under-a-dedicated-uid.md`.

**Everything here was measured on the host on 2026-11-26.** Nothing in this procedure was
executed: `apply` needs root, and `sudo -n true` on this host demands a password, so the
live run is the human's step (§4). What *was* executed is the plan (`--dry-run`) and the
verdict (`check`) against an unprovisioned host, and the test suite in
`scripts/tests/test_agent_account.py`.

## The one-line answer

`scripts/agent-account.fish` creates a user `agent` (no password, real shell, in no
privileged group), a group `sing-dev` holding the developer and `agent`, ACLs that let
`agent` traverse the developer's home and read the SDK and the toolchain but write only the
worktrees it was given, and one sudoers rule that lets the *developer* re-execute
`/usr/bin/env` as `agent`. Agents start through `scripts/agent-account.fish run -- …`, and
`check` turns each acceptance criterion of #3239 into PASS/FAIL.

## Part 1 — What the account is, and what it deliberately is not

### The acceptance criteria, one row each

| #3239 says | State | Where it is held |
|---|---|---|
| Every agent in scope runs with UID `agent` | **Partly.** The transition exists and is verified; two launchers that must use it live outside this repository | §3, §5 |
| Agents can read/write their authorized worktrees, including multiple Git worktrees | Held — an ACL on each root plus a default entry, and on the clone's shared `.git` | §2 |
| Agents cannot read unrelated private files in the developer home | **Partly.** `~/.ssh`, `~/.gnupg` (0700/0600) and any 0700 directory stay shut, and the home cannot be listed; the world-readable dotfiles under it remain readable | §2, §5 |
| Agents cannot obtain a shell or arbitrary command execution as the emulator service account | Not applicable yet — the emulator service account is #3242 and does not exist. What is held today is the inverse: `agent` holds **no** sudo rule, and `check` fails if one appears | §1, §4.1 |
| Git, Gradle and required Android SDK build tools work under the agent UID | **Unverified on this host.** The grants and the environment are what those tools need; `apply` needs root, which an agent session cannot supply, so the live build is a human step nobody has run yet | §2, §3 |
| The setup and migration/rollback procedure are documented | This document, plus `apply --dry-run` / `check` / `rollback` as the executable form of it | §4 |

| Thing | Value | Why |
|---|---|---|
| User | `agent`, uid auto | The boundary itself |
| Password | none | No login. A password is a login an agent could use, and nothing needs one |
| Shell | `/bin/bash` | A coding agent runs commands; `nologin` would make the account useless |
| Groups | `sing-dev` (and its own primary group) | `wheel`, `docker` and `kvm` are root-equivalent or own the device — §1.4 of the inventory. A group held as the **primary** gid counts too, and `apply` reconciles it with `usermod --gid`, not only with `gpasswd` |
| Home | `/home/agent`, 0700 | The agent's Gradle home, AVD home and cache live here and nowhere else |
| sudo | **nothing** | An agent that can escalate is not sandboxed. The only rule is the developer's |
| SELinux domain | unchanged, still `unconfined_t` | Confinement is #3241's scope. This issue gives it a UID to confine; it does not confine it |
| Collaboration group | `sing-dev` = the developer + `agent` | The narrow seam for worktrees. It grants nothing by itself |

**The one sudoers rule, and why it is written this way.** `agent` gets no rule. The
developer gets:

```
devel ALL=(agent) NOPASSWD: /usr/bin/env
```

That is what `run` uses to become the agent. It is the *trusted* identity re-executing a
fixed binary as the sandboxed one — the direction that keeps the sandbox a sandbox. The
inverse rule (the agent may run something as the developer) is the escalation #3239
forbids, and `check` fails if one exists.

**What `apply` reconciles on an account that already exists.** It does not assume the
account was created by this script. It refuses a `nologin` shell, locks a usable password
(`passwd --lock` when `passwd -S` says `P`), moves the primary group off `wheel`/`docker`/`kvm`
if the account holds one of them there, drops those groups from the supplementary list, and
resolves the home through the same `agent_home` helper `run` uses — so the state it creates is
the state the transition looks for.

**What is not shared.** The developer's Gradle home is not shared with the agent, and
`run` points `GRADLE_USER_HOME` at `/home/agent/.gradle`. The consequence is stated rather
than hidden: the first build under the agent downloads the Gradle distribution and the
toolchain JDK into the agent's home. That is the honest cost of "an agent cannot write what
the developer's next build reads". Sharing the cache read-only was considered and rejected:
Gradle writes into its own cache during a build, so the first write fails with EACCES —
which a reader diagnoses as a permission bug, not as a policy.

**What is not moved.** The Android SDK (7.8 GB) stays where it is, in the developer's home,
and is read from there. Re-rooting it and protecting it is #3240. Until then the SDK is
reachable through an ACL (§2), and that is the cost §5 states.

## Part 2 — The grant, path by path

`scripts/agent-account.fish apply --dry-run <worktree…>` prints exactly this, and the plan is
produced by the same code path that executes it.

| Path | Grant | Who reads it |
|---|---|---|
| `$HOME` | `user:agent:--x` — traverse, **never** a listing | Reaching anything below it, including the SDK |
| `$HOME/Android/Sdk` | `rX` + default `rX`, recursive | `emulator`, `adb`, `sdkmanager`, the system images |
| `$HOME/.local/share/mise` | `rX` + default `rX`, recursive | `java`, `node`, `heavy`, `ktlint` — the toolchain every build calls |
| `$HOME/.local/bin` | `rX` + default `rX`, recursive | `mise` itself |
| each authorized worktree | `group:sing-dev:rwX` + default `rwX`, recursive | The sources, the build outputs, the worktree's `.git` file |
| the worktree's common git dir | same grant, resolved per worktree | Objects and refs — **one** store shared by every worktree of the clone |
| the clone's common `.git` | `group:sing-dev:rwX` + default `rwX`, recursive | Objects, refs, the index — a worktree an agent cannot commit in is not a worktree |

**Why a default ACL and not `setgid`.** `setgid` propagates the group to new files; it does
not give the group a permission bit the umask would otherwise take away. `git` writes
`.git/objects/xx/…`, `git gc` rewrites refs, Gradle writes `build/` — all of them at modes
the developer's umask decides. The default ACL is the entry that applies to whatever those
tools create next, which is the requirement "agents can read/write their authorized
worktrees, including multiple Git worktrees".

**Why the common `.git` is granted, and what that means.** This clone has 74 worktrees and
**one** object store, 2.8 GB. Granting the worktree alone would let an agent read its
sources and fail on `git status`. Granting the common dir means an agent with one authorized
worktree can read the objects of every branch of every worktree in the clone. That is a
deliberate, bounded exception: branches are not secrets (they are pushed to the same remote),
and the alternative — a separate clone per agent — is exactly what #3239 puts out of scope.

## Part 3 — How each supported agent starts under the UID

`run` is the transition, and it needs no root from its caller:

```bash
scripts/agent-account.fish run -- ./gw :shared:compileKotlinJvm
scripts/agent-account.fish run -- bash -lc 'cd ~/work/wt && git status'
```

### The script is fish, and how it is started

`scripts/agent-account.fish` is fish because the reader is a fish user, and the three ways to
start it from a terminal all work — each verified on this host, including the exit code:

```bash
./scripts/agent-account.fish check ~/work/wt1      # the shebang; needs the script executable
fish scripts/agent-account.fish check ~/work/wt1   # names the interpreter explicitly
fish -c 'source scripts/agent-account.fish check ~/work/wt1'   # injection into another fish
```

The shebang is `#!/usr/bin/env -S fish --no-config`, and both words are load-bearing:

- **`-S`** is how `env` passes a flag to the interpreter at all (`env -S fish --no-config`), so
  the script is exec'd directly with its config suppressed.
- **`--no-config`** matters because the script runs `setfacl`, `usermod` and `sudo` **as root**,
  and a fish config is code that runs before the script does. Measured: with a config defining
  `function setfacl`, the run printed `HIJACKED setfacl` where the real tool belonged. A
  provision script must not inherit the caller's shell environment.

Two consequences, both real:

- `#!/usr/bin/env fish` alone is **not enough**, and not because of style: `sudo` runs with its
  own `secure_path`, where a developer-installed (or mise-installed) fish does not exist —
  `env: 'fish': No such file or directory`. So the root half never relies on the shebang:
  `as_root` re-executes the script with the interpreter it resolved itself,
  `sudo -- (status fish-path) --no-config <script> …`. That is also why `apply` works from a
  shell whose `PATH` a root process would not share.
- A mise **shim** for `fish` is not the interpreter. The shim needs mise's environment and
  fails with `No version is set for shim: fish` under a hermetic `PATH`; `(status fish-path)`
  answers with the real binary, which is what both `as_root` and the tests use.

It executes `sudo -n --user=agent -- /usr/bin/env -i HOME=… PATH=… GRADLE_USER_HOME=… "$@"`.
The `PATH` it sets is `$HOME/.local/share/mise/shims:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin`
— the shims first, because `java`, `node` and `heavy` are **shims** mise generates there, not
files in `~/.local/bin`, so a `PATH` without that directory cannot find the toolchain the build
calls.
`-i` matters: without it, every token and credential path in the developer's shell rides
into the agent, which is the opposite of what a dedicated UID is for. `check` asserts that
the variable it plants in the caller's environment does not arrive.

| Agent | How it starts today | What has to change | Where |
|---|---|---|---|
| Terminal coding-agent sessions (the desktop app's per-PTY daemon) | the daemon spawns the agent as the developer, in `user@1000.service` | the daemon spawns `agent-account.fish run -- <agent>` instead of the agent directly | **outside this repository** — the desktop app's PTY daemon |
| The AFK runner's sessions (`docs/architecture/afk-runner.md`) | the `pi-afk-runner` extension, as the developer | one issue → one worktree → one session, each wrapped in `run` | **outside this repository** — `gazon1/pi-afk-runner` |
| In-app ACP agents (Gemini CLI / Claude Code / Codex) | `SubprocessAcpAgentLauncher` (`shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentLauncher.kt:78`), a child of the desktop app, as the developer | not changed here: the application starts agents on the user's behalf in the user's session, and #3239's scope is the agents that drive the emulator | this repository |

The two launchers outside this repository are the reason the acceptance criterion "every
agent in scope runs with UID `agent`" cannot be closed by this change alone: what this
repository can do is make the transition a one-line command and prove the boundary holds.
The desktop app's daemon is the one that matters for the emulator, because it is what a
coding agent in *this* checkout runs under.

**Why the in-app ACP launcher is left alone.** It spawns an agent the user selected, in the
user's own desktop session, to talk to it over a protocol — it is the application's feature,
not a coding agent working on this repository. Changing it would mean the app cannot connect
to a user-installed Gemini CLI that lives in the user's home, and the emulator guard gains
nothing: the app does not drive the emulator. If a later issue decides otherwise, the seam is
one file (`AcpAgentLauncher.kt`) and one DI binding (`AcpDiModule.jvm.kt:35`).

## Part 4 — Setup, migration, rollback

### 4.1 Setup, on a host that has no account

```bash
# 1. The plan. No root, nothing happens.
./scripts/agent-account.fish apply --dry-run ~/work/wt1 ~/work/wt2

# 2. Apply. One password prompt; the script re-executes itself under sudo.
./scripts/agent-account.fish apply ~/work/wt1 ~/work/wt2

# 3. The verdict: every acceptance criterion, one line each.
./scripts/agent-account.fish check ~/work/wt1 ~/work/wt2
```

Prerequisites: `fish` (pinned in `mise.toml` — `mise install` puts it on `PATH`) and
`acl` (`setfacl`/`getfacl`, present on Fedora by default). `apply` is idempotent: it creates
what is missing and reconciles what is there. Before the first write it validates every root:
a directory that is not a git worktree is refused with **nothing created**, because a host
that is half-provisioned is worse than a refused run. `rollback` is the exception — it accepts
a worktree that was removed, since the grants it cleans up live on the developer's paths and
outlive the worktree. The worktree paths are the **authorized** ones — the roots an agent may
write. Passing one more is how a new worktree group is opened; nothing has to be re-run for a
worktree created inside an authorized root later, because the default ACL is what covers it.

**Where the worktrees should live.** Outside the developer's home. A path inside it works —
`--allow-in-home` opts in — but it is the worktree that makes the `--x` traverse grant
load-bearing rather than incidental, and the script says so at the top of its output.

### 4.2 Migration of the agents that exist today

Nothing about the running sessions changes by itself: a process that is already the developer
stays the developer until it restarts. The order that does not leave a window where an agent
is unguarded *and* trusted to write:

1. `apply` for the worktrees the agents use.
2. `check` — all green before any launcher changes.
3. Restart the launchers (§3) so new sessions start through `run`. Existing sessions finish
   as the developer; kill them if the boundary has to hold immediately.
4. Re-run `check` with a fresh agent session, and confirm the UID from inside it:
   `scripts/agent-account.fish run -- id` → `uid=<agent's uid>(agent)`.

### 4.3 Rollback

```bash
./scripts/agent-account.fish rollback --dry-run ~/work/wt1   # what goes
./scripts/agent-account.fish rollback ~/work/wt1
```

It removes **only the entries this script wrote** — `setfacl -x u:agent` and
`-x d:u:agent`, which name one subject and leave a foreign default entry alone. A bare
`--remove-default` would take every default entry with it, and would silently change the
modes of files nobody here wrote (measured on a throwaway tree: a `docker` default entry
disappeared with the agent's). It then removes the sudoers rule, the account and the group. It does **not** touch the worktrees, their branches or the git data:
undoing the boundary must never be a reason to lose work. `/home/agent` goes with
`userdel --remove`, which means the agent's Gradle home and its AVD home go too — that is
cache, and the next `apply` rebuilds it.

## Part 5 — What this does not do, and what it costs

| Limit | Consequence |
|---|---|
| The SELinux domain is still `unconfined_t` | The UID boundary is DAC. An agent is now somebody else, but it is not confined. #3241 owns that, and this issue's job was to give it a UID to confine |
| `$HOME` is traversable | `agent` can open any file under the developer's home whose own mode allows it — `.gitconfig`, `.envrc`, the mode-0644 dotfiles. It cannot *list* the home. The home itself is 0700, `~/.ssh` is 0700 and `~/.gnupg` is 0700, so the secrets stay shut — but a file that was unreachable from outside the home becomes reachable through the granted path, and that is new. This goes away when the SDK and the toolchain stop living in the developer's home (#3240) |
| The common `.git` is writable by `sing-dev` | An agent may read every branch in the clone, and write objects any worktree would trust. Bounded by the group: one developer, one agent account |
| The developer's Gradle cache is not shared | The first build per agent downloads the distribution and the JDK: minutes and gigabytes, once |
| The launchers are outside this repository | Two of the three cannot be switched from here (§3). The criterion is closed for the agent that drives the emulator when the desktop app's daemon is changed |
| `check` cannot read another user's sudo rules without root | It reports `NOTE … unverified` instead of a PASS. A check that could not run is not a clean bill of health |
| No adversarial test | #3246 owns bypass and recovery tests. `scripts/tests/test_agent_account.py` covers the plan, the opt-in and the environment the `run` transition hands over — not what a determined agent does with the UID |

## Part 6 — Re-verifying this document

```bash
# the plan and the verdict, which is the whole procedure
./scripts/agent-account.fish apply --dry-run <worktree>
./scripts/agent-account.fish check <worktree>

# the account, as the host holds it
getent passwd agent; getent group sing-dev
id agent                       # no wheel, no docker, no kvm
getfacl -p "$HOME" | sed -n '1,10p'          # --x, and no read grant
getfacl -p "$HOME/Android/Sdk" | sed -n '1,10p'
sudo -n -l -U agent            # nothing
sudo -n -l | grep -A2 'agent'  # the one /usr/bin/env rule, the developer's

# the transition
./scripts/agent-account.fish run -- id

# the test suite
python3 -m unittest discover -s scripts/tests -p 'test_agent_account.py'
```
