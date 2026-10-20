# Tasks — text-field-state-migration

The code change is implemented in the working tree. The implementation tasks are marked done;
the verification tasks are marked by what has actually been run.

## Phase 0 — the decision

- [x] **docs/decisions/**: record the decision, the alternatives that were rejected, and the
      server-driven renderer, which was the documented exception until it was migrated.
      File: `docs/decisions/2026-10-19-text-field-state-migration.md`.
      The ADR is `open`, not `accepted`: the outstanding unit tests in Phase 4 are the reason.
- [x] **docs/decisions/**: agree the field shapes — local, bridged, anchored — and the rule
      for choosing between them. Recorded in the ADR and in `design.md`.

## Phase 1 — the shared helpers

- [x] **shared/**: a composable helper that owns a field's text and keeps a view-model-owned
      string in agreement with it, pushing the field's text upstream and pulling an upstream
      rewrite back into the field.
      Test: the two guard predicates are pure functions over strings, extracted for that
      purpose — see the outstanding unit test in Phase 4.
- [x] **shared/**: the filtering and validation rules as plain string functions, so the rules
      are reachable from a test rather than buried in a transformation.
      File: `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldTransformations.kt`.
- [x] **shared/**: a shared state helper for the bridged shape, so the reconciliation is one
      call rather than a copy at every site.
      File: `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
      — used at 33 call sites.

## Phase 2 — the migration

- [x] **shared/**: migrate every value-based text field to the state-based API — 51 call sites
      across `shared/src/commonMain`.
- [x] **shared/**: move the filtering that lived in the change callbacks into the fields' input
      transformations, so an invalid character is rejected at the point of entry and the field
      never holds it.
      Covers: digits-only fields, the numeric range checks, and the maximum-length guard.
- [x] **shared/**: render secrets with the masked field, masked by default; the provider API
      key toggles between masked and revealed on an explicit request.
- [x] **shared/**: give the chat input bar a field state instead of a value and a change
      callback.
- [x] **shared/**: delete the form-state classes and the helper component that existed only to
      carry text for a value-based field, rather than migrating them.
      Deleted: `feature/auth/MagicLinkFormState.kt`, `feature/auth/ServerConfigFormState.kt`,
      `core/ui/detail/InlineTextField.kt`.
- [x] **shared/**: drop the email and the password from the login form state now that the
      fields own their text — which also keeps the plaintext password out of the saved
      instance state.
- [x] **shared/**: migrate the server-driven renderer's text field to the state-based API. It
      uses the same bridge, with the surface's data model as the external value, so the
      conflict rule it needed is the bridge's rule.
      File: `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/render/material3/input/TextFieldRenderer.kt`

## Phase 3 — enforcement

- [x] **detekt-rules/**: a rule that fails the build on a value-based text field, with the
      two alternatives named in its message.
- [x] **detekt-rules/**: exempt test sources — a test that pins the shape of a component is not
      shipping a screen. The server-driven renderer was exempt by path while it was value-based;
      that exemption was removed together with its migration.
      Test: `detekt-rules/src/test/kotlin/com/singularity/todo/detekt/NoValueBasedTextFieldRuleTest.kt`
      — flags the value-based shape, does not flag the state-based shape, does not flag a
      qualified call to a data class of the same name, honours the test-source exemption, and
      reports the value-based shape in the server-driven renderer, which is not exempt any more.
- [x] **detekt-rules/**: register the rule in the shared rule set, enable it in the lint
      configuration, and regenerate the rule table from the rule's own source.
- [x] **detekt-rules/**: remove the `readOnly` / `onValueChange` exemption from the
      empty-click-lambda rule. It existed because the value-based API still required the
      parameter on a read-only field; the state-based API does not, so the exemption has no
      live reason to exist.

## Phase 4 — verification

- [x] **shared/**: unit tests for the pure predicates — the two bridge guards and the
      filtering and validation rules.
      Tests: `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridgeTest.kt`
      and `.../TextFieldTransformationsTest.kt`.
- [x] **desktopApp/**: a test that a bridged field and a lagging view model do not revert the
      user's typing, and that clearing a field clears the value the view model holds. This needs
      a composed screen, so it lives in the UI-test layer.
      Test: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridgeUiTest.kt`.
- [x] **desktopApp/**: a test per migrated field, driving the real app — the task title, the
      project name, the note title, the project search, the agenda name.
      Test: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/TextFieldBridgeFlowTest.kt`.
      The chat input is covered one level down, in the bridge test above: sending a message in
      the desktop harness starts a real model call, and the Compose clock never goes idle while
      that call is in flight.
- [x] **shared/** + **detekt-rules/**: run the full test set and the lint pass on the migrated
      tree, so the migration is verified rather than assumed.
      `detekt` is clean on `shared`, `desktopApp`, `detekt-rules` and `mcp-server`; the test
      floors are met (`shared:jvmTest` 3117 tests, `desktopApp:test` 155 tests, 0 skipped).
      The failures that remain — seven in the sync push engine, one in the test-tag catalog's
      dynamic-function count, and one in the saved-agenda create flow — fail identically on the
      tree before this change, so the migration adds none of them.

---

## Phase 6 — the follow-ups

The migration shipped; these are the gaps it left behind, closed afterwards. The decision
behind them is recorded in
`docs/decisions/2026-10-19-text-field-bridge-guards-and-field-shapes.md`.

- [x] **shared/**: close the bridge's stale-overwrite hole. The pull guard now recognises
      an upstream that hands back a value the field pushed earlier — an upstream that is
      *behind* — and does not overwrite the field with it. The seed still counts as a value
      the field held, so an upstream that clears its own copy still clears the field.
      Test: `shared/src/commonTest/.../TextFieldStateBridgeTest.kt` (three new cases) and
      `desktopApp/src/jvmTest/.../TextFieldStateBridgeUiTest.kt` (one new case).
- [x] **shared/**: name the three field shapes in code. The anchored shape — a field that
      shows a value from outside and cannot be edited — gets a helper,
      `rememberAnchoredTextFieldState`, and the three hand-rolled call sites use it.
      File: `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldShapes.kt`.
- [x] **shared/**: move `ObserveTextFieldEdits` into its own file next to the
      `fieldTextFlow` primitive it shares with the bridge, so the two agree by construction
      on what "the text changed" means.
      File: `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldEdits.kt`.
- [x] **detekt-rules/**: add the counterpart rule, `NoTextFieldStateInViewModel` — a
      `TextFieldState` in a ViewModel or a UI state is a second source of truth for the
      same text, and the ADR that rejected the placement is not a rule.
- [x] **shared/**: fix the projects list's `Empty` state, which carried no query. The
      screen handed the search field an empty string, the field answered with an empty
      string, the bridge treated it as an echo and never pushed it, and the list could
      never come back. `ProjectsUiState` now carries the query and the sort order on
      every variant.
- [x] **shared/**: fix the note editor's dependency wiring. `RewriteNoteUseCase` takes the
      `TypedTool` *interface*, and `factoryOf` resolves a constructor parameter by its
      declared type — which erases to the raw interface, for which nothing is bound. The
      definition therefore threw every time the note editor was opened, and `getOrNull`
      does not swallow that: it swallows a missing *definition*, not a definition that
      fails to be created. The note editor was broken on every platform.
      Files: `shared/src/jvmMain/.../AiToolsModule.jvm.kt`, `shared/src/androidMain/.../AiToolsModule.android.kt`.
- [x] **desktopApp/**: make the chat screen and the note editor testable.
      - The desktop harness binds a fake `TextGenPort`, so a test never waits for a model
        call that will not come.
      - The failure path is bounded: a semantics dump that never returns is worse than no
        dump, and a test that hangs in its own diagnostics reports nothing.
      - The diagnostics handle a scene with more than one root. A `Popup` is a root of its
        own, and `onRoot` insists on exactly one — so the bundle that was supposed to
        explain a failure explained nothing.
      - The "all available tags" listing actually lists them: it looked for a node tagged
        `*`, and `onAllNodesWithTag` matches the tag value exactly.
      - `assertTagTextEquals` compares a field's `EditableText`, not its plain text: a
        state-based field's label and placeholder are `Text`, and Compose's
        `assertTextEquals` requires the node's whole text list to match.
      Tests: `desktopApp/src/jvmTest/.../feature/ai/chat/ChatInputFieldTest.kt` and
      `.../feature/flows/notes/NoteEditorFieldFlowTest.kt`.
- [x] **docs/**: the procedure, as a skill —
      `.agents/skills/singularity-todo-text-field-states/SKILL.md`.

---

**Verification command:**

```bash
./gradlew :shared:jvmTest :detekt-rules:test
```

**Note on what is verified so far.** The rule's own tests exist and cover the shape it
enforces. The unit tests for the bridge guards and the filtering rules do not exist yet — the
helpers were written to be testable and are `internal` precisely so that a test can reach them,
but writing the test is Phase 4 work and is not done. Nothing in the task list above should be
read as "the migration is green"; the migration is written, and the checks that would say so
have not all been run.
