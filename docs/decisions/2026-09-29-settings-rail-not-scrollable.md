---
title: "Settings nav rail was not scrollable — Backup and Account were unreachable"
date: 2026-09-29
status: accepted
tags: [ui, settings, android]
---

## Context

`SettingsNavRail` renders the eleven `SettingsTab` entries into a fixed-width
`Column`:

```kotlin
Column(modifier = modifier.width(80.dp).padding(vertical = 8.dp)) {
    SettingsTab.entries.forEach { tab -> ... }
}
```

There is no scroll. Each row is a 48dp icon circle plus a label and 12dp of
padding — roughly 76dp — so eleven tabs need about 836dp. A phone viewport is
about 800dp, and the shell's bottom bar takes more. The last two tabs, **Backup**
and **Account**, were clipped off the bottom and could not be tapped.

Nothing failed: the screen rendered, the rail looked like a normal vertical list,
and no test opened those tabs. Only driving the real UI surfaced it —
`Maestro/flows/smoke/12-settings-cycle-tabs-smoke.yaml` got through ten tabs and
then could not find `settings_tab_backup`.

## Idea

1. Add `verticalScroll(rememberScrollState())` to the rail column.
2. Switch to a `NavigationBar` / bottom bar for compact widths, the pattern the
   rest of the app already uses for its six bottom-nav destinations.
3. Move Backup and Account into a sub-screen reached from another tab.

## Decision

We did (1). The rail scrolls on every form factor; on a tablet the content fits
and the scroll never engages, so nothing changes there.

## Rationale

(1) is one line and fixes the bug without changing the information architecture.
(2) is arguably the better phone design — it matches the shell's own bottom bar
and gives the tabs room to breathe — but it is a redesign of the screen, and the
rail reads fine on the tablet this layout is presumably built for. (3) would hide
two settings one level deeper for a layout problem.

If the rail is ever found cramped on phones, (2) is the next step, not (3).

## Consequences

- `SettingsNavRail` is scrollable. A flow that reaches a lower tab must scroll the
  rail explicitly — `scrollUntilVisible` swipes the *screen centre*, which is the
  content pane, not the 80dp rail on the left edge. The smoke flow uses a fixed
  `swipe` at `x: 105` for this reason; a comment in the flow records it.
- The eleven tab tags are lower-case, because `TestTags.settingsTab()` routes
  through `slug()`. `SettingsTab.AIProvider` is `settings_tab_aiprovider`, not
  `settings_tab_AIProvider`. `Maestro/TAGS.md` records the expanded forms.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsScreen.kt`
- `Maestro/flows/smoke/12-settings-cycle-tabs-smoke.yaml`
- `Maestro/TAGS.md`
- Related: `2026-09-29-single-sealed-navkey-root.md`
