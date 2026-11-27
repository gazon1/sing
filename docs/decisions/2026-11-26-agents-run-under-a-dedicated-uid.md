---
title: Coding agents run under a dedicated unprivileged uid
date: 2026-11-26
status: accepted
tags: [process, tooling, agent, security, host]
---

## Context

Issue #3239 asks to separate coding-agent processes from the interactive developer account.
The inventory (`docs/architecture/emulator-launch-inventory.md`, #3238) measured what the
separation has to start from:

- agents are **uid 1000**, the developer, with the supplementary groups `wheel` (10), `kvm`
  (36) and `docker` (969), in SELinux domain `unconfined_t`;
- `docker` membership is **root-equivalent** — `docker run --privileged -v /:/host` reads
  and writes every file, including anything a root-owned protection scheme installs;
- the Android SDK (7.8 GB) is **owned by uid 1000**, so agents are today the owner of the
  very files #3240 has to protect;
- `/dev/kvm` is mode 0666, and `kvm` membership is a second way in.

So every guarantee in #3239–#3242 starts from "the agent is the developer". A file-permission
scheme against an agent that owns the files, can run `docker`, and can `sudo`, measures
nothing.

## Idea

Five candidate shapes, and the reason each loses:

1. **A container per agent.** The isolation is stronger, but the agent needs the worktree,
   the toolchain, KVM and the SDK, and the container has to be built and kept in step with
   the host. The inventory records this as *tooling only, not validated*, and notes that
   `docker` today makes the boundary a suggestion.
2. **SELinux confinement alone.** The right MAC on this host (there is no AppArmor in the
   kernel), but a confined domain pinned to the developer's UID still owns the files, and
   `docker` still escapes. It is also #3241's issue, not this one.
3. **`systemd-run` per session, as the developer.** Gives a cgroup, which #3242 wants, and
   no identity boundary at all.
4. **A dedicated UID with the privileged groups removed, and a narrow group for the
   worktrees.** The smallest thing that gives DAC a boundary to enforce, and it is a
   prerequisite for 1 and 2 rather than a rival of them.
5. **Moving the developer's files, or duplicating the repositories.** Out of scope by the
   issue, and it makes the SDK's 7.8 GB a per-agent cost.

## Decision

Take **(4)**, and only (4): `scripts/agent-account.py` provisions

- a user `agent`, no password (no login), real shell, in none of `wheel`/`docker`/`kvm`;
- a group `sing-dev` holding exactly the developer and `agent` — the collaboration seam;
- ACLs: `--x` on the developer's home (**traverse, never a listing**), `rX` on the SDK and
  the toolchain, `rwX` plus a default `rwX` on the authorized worktree roots and on the
  clone's common git dir;
- one sudoers rule, written for the **developer**: `ALL=(agent) NOPASSWD: /usr/bin/env` —
  the trusted identity re-executes a fixed binary as the sandboxed one. `agent` holds no
  sudo rule at all;
- `scripts/agent-account.py run -- <cmd>` as the transition, which builds the agent's
  environment from scratch (`env -i`) instead of inheriting the caller's.

`scripts/agent-account.py check` turns each acceptance criterion into a verdict, and
`scripts/tests/test_agent_account.py` holds the parts that would be wrong before any
privileged call runs.

Three things are deliberately **not** done: the SDK is not moved or re-owned (#3240), the
SELinux domain is not confined (#3241), and the developer's Gradle cache is not shared — the
agent resolves into its own home, because a build artifact the developer's next build trusts
is exactly what must not be shared.

## Rationale

- **The boundary has to be identity, because the mechanisms above it are identity-blind.**
  File modes, ACLs and `sudo` rules all name a user. A container and a SELinux domain are
  both easier to state and to reason about once the process inside them is no longer the
  owner of what it must not touch.
- **The privileged groups come first, not last.** `docker` defeats every permission the rest
  of the guard installs, so an account that kept it would be a rename, not a boundary. This
  is why `check` fails on `wheel`/`docker`/`kvm` membership and `apply` never adds them.
- **`--x` on the home rather than `r-x`.** Traversal opens the paths below; a read grant
  would open every readable file in the home. The grant is chosen to be the minimum that
  reaches the SDK, and its cost is written down rather than discovered.
- **The sudoers rule points at the developer.** A rule for `agent` would let the sandboxed
  identity leave the sandbox — the exact escalation the issue forbids. `run` is therefore a
  developer command, which also means an agent cannot invoke it to become anything.
- **A default ACL, not `setgid`.** `setgid` propagates the group but does not add a
  permission bit the umask takes away, and the tools that matter (`git`, `git gc`, Gradle)
  write at modes the umask decides.
- **The common `.git` is in scope.** This clone carries 74 worktrees over one 2.8 GB object
  store. A writable worktree over a read-only object store is a worktree the agent cannot
  commit in, so "can read/write its authorized worktrees" would be false by construction.

## Consequences

- An agent can no longer read `$HOME/.ssh` or `$HOME/.gnupg` (both 0700), and can never list
  the developer's home. It **can** open a mode-0644 dotfile under it through the granted
  path — files that were unreachable from outside a 0700 home before this change. That is
  the stated cost of the SDK living there, and it goes away with #3240.
- An agent can read every branch of the clone (the common object store) and write objects
  any worktree would trust. Bounded to `sing-dev`, which holds one developer and one agent.
- The first build under the agent downloads the Gradle distribution and the toolchain JDK
  into `/home/agent/.gradle`: minutes and gigabytes, once per agent home.
- Agents that are already running stay the developer until restarted — the uid of a live
  process is not re-read. The migration order in `docs/architecture/agent-uid.md` §4.2 is
  what closes that window.
- The desktop app's PTY daemon and `gazon1/pi-afk-runner` must start agents through `run`;
  both live outside this repository, so this change cannot close "every agent in scope"
  alone. It makes the transition one command and proves the boundary.
- The in-app ACP launcher (`AcpAgentLauncher.kt`) is unchanged: it starts an agent the user
  selected, in the user's session, to talk to it — it does not drive the emulator. The seam
  is one file and one DI binding if a later issue decides otherwise.
- Rollback is `scripts/agent-account.py rollback`, and it does not touch branches or git
  data: undoing the boundary must never be a reason to lose work.
- The script was fish when this decision was taken and is Python now; the port and the move
  of the declared state into Ansible are
  `2026-11-27-the-agent-account-is-declared-in-ansible.md`. What did not change is the
  invariant this entry records: the boundary, the one sudoers rule and the transition.

## Links

- Issue: [gazon1/sing#3239](https://github.com/gazon1/sing/issues/3239)
- Evidence: [`docs/architecture/emulator-launch-inventory.md`](../architecture/emulator-launch-inventory.md)
- Procedure: [`docs/architecture/agent-uid.md`](../architecture/agent-uid.md)
- Script: `scripts/agent-account.py`; tests: `scripts/tests/test_agent_account.py`
- Sibling issues: #3240 (install and `/dev/kvm`), #3241 (execution confinement), #3242
  (launcher service), #3246 (bypass and recovery tests)
