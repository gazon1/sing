# Tasks — the-next-layer-of-contracts

**status:** accepted

---

## Phase 1 — The task and the project learn of a rewrite

- [x] `TaskDetailDraft` and `ProjectDetailDraft` carry `revision: Long = 0`
- [x] The seed is no longer idempotent: it initialises once, ignores the echo
      of our own save, refuses to touch a dirty draft, and takes a rewrite
      from outside
- [x] A rewrite from outside advances the revision
- [x] A write the repository accepted marks the draft clean, so the next
      rewrite is not refused as "the user is typing"
- [x] The baseline moves only when the draft still holds what was written
- [x] `TaskTitleRow`, `TaskDescriptionField` and `ProjectHeroSection` take the
      revision and pass it to `rememberBridgedTextFieldState`
- [x] `TaskDetailUi` and the project screen carry the revision to the field

## Phase 2 — The contract checks that the token moves

- [x] The registry names the file that declares the counter, per field site
- [x] The test fails when a claimed revision is a constant
- [x] The test fails when the named counter does not declare a revision
- [x] The skill's rewrite table carries the counter column
- [x] The draft-state tests pin the rule: a rewrite moves the revision, a
      keystroke does not, the echo of a save does not

## Phase 3 — The watcher is tested

- [x] A direct test of `NoteRewriteWatcher`: a rewrite of a clean draft is
      reported, a dirty draft is not, the echo of a save is not, a foreign id
      is ignored, a null body is converted from markdown
- [x] The draft is read live, not snapshotted

## Phase 4 — The toolchain has one source

- [x] `config/toolchain-versions.env` holds the four pins
- [x] `scripts/ci/setup-local-tools.sh` reads it
- [x] `.github/actions/setup-python-tools/action.yml` reads it
- [x] `scripts/tests/test_toolchain_pins.py` fails when a pin is written
      anywhere else

## Phase 5 — The preflight runs in CI

- [x] The "Static gates" job runs `scripts/ci/preflight-gate-tools.sh` after
      the tooling is installed and before the gate suite
- [x] The tests job does not run it, and says why: it installs a smaller
      toolset on purpose

## Phase 6 — A waiver points at something that resolves

- [x] Every exemption carries a `tracked_by` naming the file that does the
      proving
- [x] Every `Parity` whose scope is not `both` carries a `tracked_by` naming
      where the asymmetry is visible
- [x] `check-waiver-references.py` resolves both and fails when they do not

## Phase 7 — The conventions gate is green and proven

- [x] The argparse rule is read from the AST, not from a substring
- [x] `check-log-interpolation-allowlist.py` uses Click's `hidden=True`
- [x] `check-gate-registries.py` is migrated to Click
- [x] The "script conventions" exemption is replaced by a sabotage control
- [x] The wiring twin carries the new control

## Phase 8 — The doubles live with the other doubles

- [x] `MapFileSystem` and `FakeSecureStorage` move to `test/fakes/`
- [x] `NoSuchFileException` moves with the double that throws it, and the
      port's KDoc says what the platform actually does
- [x] The two baseline exemptions for the doubles are gone
- [x] The skill and the architecture notes point at the new paths
