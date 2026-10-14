---
title: "Measurement determinism: display-wait contract, tag-execution gates, and the main breakage they uncovered"
date: 2026-10-14
status: accepted
tags: ["testing", "desktop-compose", "detekt", "ci", "flaky-tests"]
---

# Measurement determinism: display-wait contract, tag-execution gates, and the main breakage they uncovered

## Context

Epic "Детерминизм измерений" (#569 flaky desktop Compose UI tests, #362
TestTagCoverageTest checked annotations but not the execution engine, #471
EOFException in jvmTest executor workers). While verifying it, three layers of
pre-existing breakage on `main` (3c3175ce) surfaced, all confirmed against a
stashed clean tree before being touched:

1. `single<SessionStore>` was commented out inside e05a68ad (an unrelated scripts
   PR), so `HlcFactory(get(), get(), get())` at `CoreDiModule.kt` missed its
   dependency and Koin's compile-time KOIN-D003 deferred validation failed every
   `:androidApp`/`:desktopApp` compile — locally and in CI (run 38440016884).
2. `NonTaggedMaestroElementsProvider` was never listed in
   `META-INF/services/dev.detekt.api.RuleSetProvider`, so detekt failed *config
   parse* ("invalid config property") for **every** module that loads
   `config/detekt/detekt.yml`. Its rule also had four latent bugs its own tests
   had never caught (the suite was red on main).
3. The rule, once it could actually run, reported **578** untagged interactive
   composables across `feature/`.

## Idea

Three separable contracts were conflated: *presence* of a test node, *display*
of a test node, and *executability* of a test by the tag filter. Each needs its
own primitive, and each needs a gate that measures the thing it claims to
measure.

## Decision

### 1. Presence and display are different waits (#569)

`awaitTag(...)` stays **presence-only** — `clickTagScrolled`/`scrollToTag`
deliberately start from below-the-fold nodes that have zero bounds and are
therefore "present, not displayed". Display waiting lives only in the
`assertTagDisplayed` / `assertTextDisplayed` /
`assertContentDescriptionDisplayed` helpers (DesktopAssertions.kt): they wait
until the node is laid out inside **its own window root** (`isDisplayedInItsRoot`
— dialogs/popups are separate roots with their own bounds) and fail with a
message that names the distinction ("present is not shown").
`awaitTag(X).assertIsDisplayed()` as a one-shot pattern is migrated; the single
negative control in `CreateTaskFlowTest` stays.

### 2. The tag-annotation gate now validates the engine too (#362)

`TestTagCoverageTest` gained two tests: every `@Tag` **value** in the codebase
must be in the CI-selectable vocabulary (`fast`, `slow` — aliases resolved via
`aliasTagName`), and no tag-filtered source set may contain JUnit4/Vintage
runners (the `--tests` filter never applied to them, which is how a "selected"
class can silently not run). The annotation→execution chain is documented in
the class KDoc: source-set gate → `failOnNoDiscoveredTests` →
`scripts/check-test-runs.py` cross-referencing `@Tag("fast")` classes against
XML.

### 3. `--no-parallel` in pre-push (#471)

The pre-push hook runs the full `jvmTest`; with `org.gradle.parallel=true` from
`gradle.properties` two forks can collide on shared executor state and produce
EOFException. The hook now passes `--no-parallel` (CLI overrides the property).
CONTRIBUTING.md documents the failure mode and the local workaround.

### 4. Main breakage repaired as drive-bys (each proven pre-existing)

- **SessionStore binding restored** in `CoreDiModule.kt`:
  `single<SessionStore> { get<SecureSessionStore>() }` — the KEYCHAIN store, never
  the plaintext one. `PlaintextTokenIsolationTest` scans source for token
  *writes*; it does not forbid this binding.
- **RuleSetProvider registration**: added
  `NonTaggedMaestroElementsProvider` to the ServiceLoader file.
- **Rule repaired to its own KDoc**: `.testTag(...)` call selectors are
  `KtCallExpression` (was matched only as a name reference, so every tagged
  composable read as untagged); pattern 2 is scoped to public composables in
  `feature` packages (was: all of `core/`); the handler gate delegates to the
  canonical `NoEmptyOnClickLambdaPolicy.isHandlerParameter`; the finding message
  names the offending handler parameters. Three of the rule's four pre-existing
  test failures were the tests acting as spec; the fourth (`onValueChange`)
  was resolved in favour of the canonical policy — typed input is
  Maestro-interactable.
- **Ratchet, not sweep**: `non-tagged-maestro-elements` is `active: false` in
  `detekt.yml` with the debt written down. 578 findings is tagging work for the
  Maestro tranche; activating the rule before the surface is tagged would make
  the detekt gate permanently red.

## Rationale

Presence-vs-display is the race the issue describes ("the node is in the tree,
but not rendered"): dialogs and popups are separate windows whose bounds are
zero until layout. A wait that only proves presence cannot prove display, and
the failure message must say which one failed or the next reader re-litigates
the same bug. Every gate added here measures a property the previous gate only
*implied*: the tag gate measured annotation, not engine; the detekt services
list was assumed, not registered; the SessionStore binding was commented out
"deliberately" with no gate noticing the compile break until CI went red.

## Consequences

- Flow tests migrate to `assertTagDisplayed`; `awaitTag` keeps its scroll
  contract; new display waits must use the three helpers.
- `TestTagCoverageTest` fails if a third tag value appears without a CI wiring
  change, or if JUnit4 ever lands in a filtered source set.
- The Maestro tranche owns the 578-finding ratchet: tag the surface, then flip
  `active: true`.
- Main's remaining redness (TestTagsCatalog categories, Maestro flow ids/paths,
  and the ~33 other static gates) is tracked by its owners; this pass fixed
  exactly one static gate (`detekt rule registry`) and proved the rest
  pre-existing by stash-diff.
- **Always** keep `awaitTag` presence-only; display waits go through the `assert*Displayed` helpers, never a bare `assertIsDisplayed` after a presence wait.
- **Always** keep `single<SessionStore> { get<SecureSessionStore>() }` bound in `CoreDiModule.kt` — `HlcFactory` resolves it for the device id, and the plaintext store must never take its place.
- **Always** list every `RuleSetProvider` in `META-INF/services/dev.detekt.api.RuleSetProvider` — an unlisted provider makes its config key an "invalid config property" and kills *all* rules in the shared yml.
- **Never** enable `non-tagged-maestro-elements` in `config/detekt/detekt.yml` before the tagged surface exists — the rule is repaired and registered, but the code has 578 findings to clear first.

## Links

- Issues: #569, #362, #471
- `docs/decisions/archive/2026-09-30-post-mr-2-findings.md` (EOFException
  diagnosis)
- ADR `2026-10-04-configuration-cache-hardening` (pre-push hook shape)
- Skills: `singularity-todo-desktop-compose-ui-tests`,
  `singularity-todo-test-tag-strategy`, `singularity-todo-detekt-rules-authoring`
