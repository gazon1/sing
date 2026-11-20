# PR review — Share E2E device bridge across Maestro JS scripts (PR #3064)

- Repository: gazon1/sing
- Target: PR #3064 — https://github.com/gazon1/sing/pull/3064 (branch `gazon1/maestro-share-the-e2e-device-bridge-across-javas`, no separate PR body changes beyond title)
- Base → Head: main (`e2bf3381e760c853c65813c3852f60cf9f298bc3`) → `d67b0ad06c24512f524f66b690442640ca40e996`
- Reviewed at: 2026-11-20T09:20:00Z (UTC, manual capture)
- Scope (changed files, from `gh pr view 3064`): `Maestro/flows/auth/02-signin-wrong-password.yaml`, `Maestro/flows/auth/03-signup-toggle.yaml`, `Maestro/flows/auth/06-signup-unconfirmed.yaml`, `Maestro/flows/auth/12-unreadable-store-recovers.yaml`, `Maestro/helpers/scripts/damage-store.js`, `Maestro/helpers/scripts/device-bridge.js` (added), `Maestro/helpers/scripts/set-stub-flag.js`, `Maestro/helpers/scripts/verify-session.js`, `Maestro/helpers/scripts/verify-store.js`.

## Verdict

**APPROVE.** Independent review via 10x-chat (ChatGPT, `--provider chatgpt`, fresh conversations, role-isolated prompts) found no blocking/major/minor defects in the extracted Maestro E2E bridge helper, flow init ordering, preservation of `shell`/`runAs` semantics and error reporting, or scope boundaries (no Python bridge/policy/runner contract changes). Local inspection matches: duplicated HTTP+`runAs` logic moved to `output.deviceBridge`, consumers load `device-bridge.js` before use (including auth flows in `onFlowStart` and `onFlowComplete`, and store flow before `damage-store.js`).

## Checks

| Check | Command | Result | Notes |
|---|---|---|---|
| Local git state | `git status --short` (worktree at review time) | PASSED | Clean after commit `d67b0ad06c2` on branch; this report file added in a follow-up commit (see this PR history). |
| PR metadata / changed files | `gh pr view 3064 --repo gazon1/sing --json ...` | PASSED | Matches scope above. |
| 10x-chat CLI available | `10x-chat --version` | PASSED | `0.12.0`. |
| 10x-chat login status | `10x-chat login --status` | UNAVAILABLE (attempted) | Repeated attempts timed out in this session; ChatGPT runs were executed anyway via `10x-chat chat --provider chatgpt ...` (sessions launched) and completed with model `Pro`. Treat as: login precheck not conclusively read; execution succeeded for the two review runs. |
| Role A (correctness, regressions, security, edge cases), ChatGPT Pro, fresh chat | `10x-chat chat --provider chatgpt --new-chat --timeout 1800000 -p "…Role A prompt (PR #3064 coords)…"` | PASSED (review output) | Session `e2648b90-3a6e-4e62-b9bb-632a7e1383e1` (see saved CLI logs under `/tmp/commandcode/shellout/2026-11-20T09-17-28-962Z-s0l4afce.log`). Output concluded: no findings; preserved HTTP/request/response/error behavior and `runAs` construction; init order OK; allowlist/runner contract unchanged; verdict “No changes requested.” |
| Role B (architecture, maintainability, compatibility, scope discipline), ChatGPT Pro, fresh chat | `10x-chat chat --provider chatgpt --new-chat --timeout 1800000 -p "…Role B prompt…"` | PASSED (review output) | Session `caae52b8-715b-41d8-8ad6-612f1051a609` (log `/tmp/commandcode/shellout/2026-11-20T09-17-33-389Z-s13yoa68.log`). Output concluded: no blocking/major issues; refactor matches architecture (shared helper via `output`, Python bridge/policy/runner unchanged, scoped init, preserved behavior); verdict “No actionable…” findings. |
| Maestro flows executed on device/emulator (sanctioned runner) | — | SKIPPED / NOT RUN in this session | Issue/PR intent lists running `auth/02-signin-wrong-password.yaml`, `03-signup-toggle.yaml`, `06-signup-unconfirmed.yaml`, `auth/12-unreadable-store-recovers.yaml` via repo’s sanctioned runner (Maestro 2.10.0) with evidence recorded per project convention; not performed by this agent run. |

## Findings

No findings reported by Role A or Role B. No additional defects identified during local review of the diff (`git show --name-only d67b0ad06c2` plus file reads).

| # | Severity | Location | Problem | Impact | Evidence | Status |
|---|---|---|---|---|---|---|
| — | — | — | None. | — | 10x-chat Role A/B transcripts in CLI logs; local diff inspection. | — |

## Acceptance criteria (from issue context reflected in PR)

| Criterion | Status | Evidence |
|---|---|---|
| Exactly one Maestro-side HTTP command-bridge client + one `runAs` wrapper for these scripts | MET | `Maestro/helpers/scripts/device-bridge.js` defines `output.deviceBridge.shell/runAs`; consumers (`verify-*`, `damage-store`, `set-stub-flag`) no longer define duplicates. |
| Shared functions initialized before first use in every affected flow | MET | 02/03/06: `device-bridge.js` then flag script in `onFlowStart` and in `onFlowComplete`; 12: `device-bridge.js` before `damage-store.js`. |
| Missing `E2E_BRIDGE_URL` still fails explicitly (no silent skip) | MET (code path) | `bridge.shell` throws when unset (preserved from prior scripts). Not runtime-tested here. |
| HTTP parsing/error reporting preserves 400 vs 500 distinction and returns stdout on success | MET (code path) | Preserved in `device-bridge.js`. |
| `set-stub-flag.js` still writes/removes flag per `VALUE` | MET (code path) | Logic unchanged aside from using `output.deviceBridge.runAs`. |
| `damage/verify-store/verify-session` behavior retained | MET (code path) | Only transport call sites moved to `output.deviceBridge`. |
| No changes to Python bridge/endpoint/allowlist/runner contract | MET | No changes under `scripts/src/emulator/maestro/_bridge.py` in this change set (per commit contents). |
| Affected flows pass via sanctioned runner with device evidence | UNVERIFIED | Not run in this session (see Checks). |

## Not verified

- End-to-end execution of the four affected Maestro flows via the sanctioned runner on a device/emulator with evidence recorded (project convention). Requires environment + runner.
- Live behavior when `E2E_BRIDGE_URL` is absent at runtime for these flows (static inspection only).

## Reviewers

| Role | Provider | Verdict | Notes |
|---|---|---|---|
| Correctness / security (Role A) | chatgpt (Pro) | No findings / No changes requested | Fresh `--new-chat`; reviewed via GitHub coordinates in prompt. |
| Architecture / scope (Role B) | chatgpt (Pro) | No actionable findings | Fresh `--new-chat`; reviewed via GitHub coordinates in prompt. |

## Remaining risks

- **Untested on-device runs:** the refactor is behavior-preserving by inspection; regressions would surface only in real Maestro runs (flag setup/teardown, store damage/verify sequencing). Mitigation: run the four flows with the sanctioned runner before/around merge as per project practice.
- **`output.deviceBridge` contract:** any new consumer must load `device-bridge.js` first (now documented in scripts/comments). Risk is localized to future edits; current affected flows are covered.
