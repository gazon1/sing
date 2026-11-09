# Tasks — genui-modal-dialog

**status:** proposed

---

## REQ-GENUI-MODAL-1 — a modal is a dialog, and the path says whether it is open

- [x] `shared/` Draw the modal's content in a dialog, and draw nothing at all while the path does
      not hold true. **Test:** `GenuiCatalogRenderTest.aModalShowsItsContentWhenItsPathIsTrue`
      and `GenuiCatalogRenderTest.aModalDrawsNothingWhileItsPathIsClosed` — a surface whose
      modal's path is true shows the content, and a surface whose modal's path was never
      written shows nothing.
- [x] `shared/` Read the path as open when it holds true — a boolean, or the text "true" — and as
      closed for anything else, including no value at all. **Test:** `ModalOpenTest` — the reading
      is asserted for a boolean, for text, for a missing value, for a null and for a value that is
      not true.
- [x] `shared/` Write false to the path when the dialog is dismissed, so the path holds the whole
      of the dialog's state. **Test:**
      `GenuiCatalogRenderTest.closingAModalWritesFalseToItsPath` and
      `GenuiCatalogRenderTest.aModalOpensAgainWhenItsPathIsTrueAgain` — closing leaves the path
      holding false, and a later true opens the dialog again.
- [x] `shared/` Keep the description the model reads in step with the behaviour: the modal is no
      longer described as an inline panel. **Test:** `CatalogPromptTest.promptDescribesTheModalAsADialog`
      — the model's instructions carry the description the declaration holds, and the wording of an
      inline panel is gone.
- [ ] `shared/` `just gate` passes end to end. **Why it is still open:** the change is
      implemented and the tests that cover it are written, but this run does not build or gate the
      tree — the repository's own CI judges it.
