# Phase 0 Audit — Documentation System (OKF/OpenSpec/ADR)

Read-only audit. No repo changes beyond this file.

## 1. Python inventory
- Local: Python 3.14.8 (`/usr/bin/python3`). CI gate suite: 3.12 (`.github/actions/setup-python-tools`, default); some jobs 3.11.
- Deps: no pinned Python package at root; scripts installed via `scripts/src/pyproject.toml` (sgate). `scripts/pyproject.toml` carries ruff config only.
- Test layout: `scripts/tests/test_*.py`, fixtures in `scripts/tests/fixtures/`, shared `scripts/tests/git_sandbox.py`.
- Lint: ruff (line-length 100, target py312, rules I/D100/ICN) + `check-script-conventions.py`.
- Windows: not verified here; scripts use subprocess/Git; no platform-specific code paths observed in sampled scripts.

## 2. YAML library decision
- PyYAML 6.0.3 available locally and in CI tooling (yamllint installed).
- ruamel.yaml: NOT installed locally; installable via `extra-packages` in setup-python-tools. Not currently a dependency.
- Decision for v1: start with PyYAML for parsing/validation; round-trip editing for `stamp` requires preserving unknown fields/order/comments. PyYAML is not a round-trip editor. Options: (a) add ruamel.yaml as a pinned dependency via extra-packages and use it for stamp writes, or (b) text-based targeted frontmatter edit. Recommendation: add ruamel.yaml (pinned) — matches plan's preferred fallback, avoids fragile text editing. Gate: verify round-trip preservation in fixtures before Phase 1.

## 3. Git blob behavior
- Git 2.56.0; `core.autocrlf=input` (Linux). `.gitattributes` has merge=union entries only; no `text`/`eol` filters on source paths observed.
- `git rev-parse HEAD:<path>`, `git hash-object --path=...`, `git cat-file -p`, `git diff <oid> <oid>` all available.
- Windows behavior: not tested (Linux only). Must be validated in CI (windows runner) before Phase 1 gate.
- Rule v1: stamp forbidden if any source has staged/unstaged changes.

## 4. OKF CLI / schema
- No OKF CLI found on system or in repo. Decision: Variant B — no independent oracle; document a project-specific OKF-compatible profile validated by the Python checker. Conformance checked relative to that profile; state honestly in ADR.

## 5. Index generation
- No `knowledge/index.md` exists (no `knowledge/` dir). If Python generates it, generator is in scope (not free). Recommendation: defer index generation to Phase 3; Phase 1 focuses on status/show/affected/stamp/verify.

## 6. CI / merge policy
- Static gates via `scripts/ci/static-gates.sh` (generated from registry). Branch protection: single "CI gate" check. Advisory jobs possible.
- Merge queue: not confirmed present. Recommendation: require up-to-date branch or merge queue before enforcement (Phase 4b).

## 7. CI dependency
- ruamel.yaml pinned in `scripts/documentation/requirements.txt`; CI must install via setup-python-tools `extra-packages` (or `pip install -r`).

## 7. Spec Kit / OpenSpec
- `specs/` contains numbered product specs (001-...). `openspec/changes/`, `openspec/specs/`, `openspec/config.yaml` exist.
- `.specify/templates/engineering/` — to be checked for concept_impact insertion point (Phase 5). Not modified now.

## 8. Existing doc checks
- `check-doc-dead-refs.py`, `check-navigation-doc-sync.py` exist — reuse patterns for new checker.
- No `scripts/documentation/` yet.

## Decisions gating Phase 1
1. Round-trip YAML: add ruamel.yaml (pinned) — pending verification.
2. Blob OID: Git blob from commit tree; validate Windows in CI.
3. Conformance: project-specific profile (Variant B).
4. No OKF CLI oracle.

## Open questions
- Windows runner availability for blob tests.
- Whether to add ruamel.yaml to `scripts/src/pyproject.toml` or a separate doc-tooling package.
- Exact `concept_impact` insertion point in engineering templates (defer to Phase 5).