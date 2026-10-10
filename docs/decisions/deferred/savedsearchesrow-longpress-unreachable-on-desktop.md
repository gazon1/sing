---
title: "Savedsearchesrow Longpress Unreachable On Desktop"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** #404

**Found in:** PR #402 (`:fix/search-viewmodel-test-33-35-73-83`), while writing
`SavedSearchesRowUiTest`.

`SavedSearchesRow` has a long-press context menu (rename, delete) implemented with
`combinedClickable` inside a `LazyRow`'s `DropdownMenu` popup. The desktop Compose
test API cannot reliably address nodes inside a `DropdownMenu` popup rendered by
`LazyRow` — the popup is in a separate layer that `onNodeWithText` and `performClick`
cannot reach. The test was omitted from the PR rather than shipped broken.

**Already ruled out — measured, not inferred.** Direct `performClick` on the chip
works correctly (covered by the PR's tests). The long-press path is the gap.

**Try next, in this order.**

1. **Accept the gap (Android/Maestro tier only).** The long-press rename/delete is
   reachable via Maestro on Android. No code change.
2. **Rewrite context menu as inline UI.** If the menu were rendered as a permanent
   inline UI element (e.g., a separate column or a dialog) instead of `DropdownMenu`,
   it would be addressable by the desktop test API.
3. **Investigate desktop PopupLayer API.** `DropdownMenu` in desktop Compose uses a
   `PopupLayer`; there may be a way to traverse it with the test API that was not
   explored during this PR.

---
