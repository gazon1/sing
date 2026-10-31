# Android shell bar and selection mode

The Android shell owns the single TopAppBar. Screens publish through the
platform-aware `TopAppBar` wrapper.

- Normal mode: `TopAppBar` publishes `actions` to `ScreenActionsHolder`; the shell
  renders title/back/search button plus those actions.
- Selection mode: pass `selectionTitle` and `onExitSelection` to `TopAppBar`. The
  shell then shows "N selected" with an exit-selection back button and hides the
  search button. Desktop renders its own bar from the `title`/`navigationIcon`
  params, so the same composable works on both platforms.

Do not reintroduce the old `override`/`title`/`navigationIcon`/`colors` slots on
`ScreenActionsHolder`.