---
title: "Simplefiltersheet Modalbottomsheet Unreachable On Desktop"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** #403

**Found in:** PR #402 (`:fix/search-viewmodel-test-33-35-73-83`), while attempting to
write desktop Compose UI tests for `SimpleFilterSheet`.

`SimpleFilterSheet` is a `ModalBottomSheet`. On desktop Compose, `ModalBottomSheet`
renders its content into a **separate semantics root** — the test API (`onNodeWithText`,
`performClick`) cannot reach it. All four tests that tried to interact with the sheet's
controls (`Has description`, `Pinned` toggles; `Apply`, `Cancel` buttons) failed with
`IllegalStateException` ("expected at least one item").

**Already ruled out — measured, not inferred.** `BottomSheetScaffold` cannot work
around this: the sheet manages its own `SheetState`, and the content lives in the
scaffold's `sheetContent` slot which the test API still cannot reach. The codebase's
own `TagsMd.kt:282` already documents this limitation for the `SearchFilter` tag class.
`ModalBottomSheet` on desktop always creates a separate semantics root.

**Try next, in this order.**

1. **Accept the gap (Android/Maestro tier only).** Document the gap permanently in
   `TagsMd.kt` under the `SearchFilter` heading. The controls are reachable via
   Maestro flows on Android. No code change.
2. **Refactor to BottomSheetScaffold.** If `SimpleFilterSheet` used `BottomSheetScaffold`
   directly instead of `ModalBottomSheet`, it would render in the same semantics root.
   This is a product/UX decision about the sheet's dismissal model (swipe-to-dismiss
   vs. tap-outside-to-dismiss), not a test infrastructure decision.
3. **Screenshot-based testing.** A screenshot test would capture the rendered sheet
   and could assert on pixel values. This tests appearance, not behaviour.

---
