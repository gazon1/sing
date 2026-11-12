# SingularityApp vs Singularity Todo — project navigation UX, measured on one device

Status: research recorded 2026-11-12, revised after the import and after the platform critique.
Workspace `gazon1/compare-interface`. No source was changed by this research.

## What the first revision got wrong

It compared **desktop CSS against Android dp**. SingularityApp's `24px` indent, `6px` padding and
`20px` emoji came from the Electron build (`app.asar`); Singularity Todo's numbers came from Compose
on a 320-dpi phone. Those are not comparable, and the conclusions that rested on the comparison
(the indent step, the emoji size, the connector line) have been re-derived below from the Android
build of SingularityApp on the same phone. The desktop values are kept only as a secondary source.

## How the numbers were taken

| | |
|---|---|
| Device | `0O65321F32204DD5`, 720×1600 px, **density 320 → 1dp = 2px** |
| Singularity Todo | `com.singularity.todo` 0.1.0+g29ccc0964fb7, `dumpsys` + `uiautomator dump` bounds |
| SingularityApp | `com.sibirix.singularityapp` **10.23.0**, `uiautomator` `content-desc` + screenshot pixel measurement (PIL) |
| Data | the same account: 132 projects, 12,027 tasks imported |

`uiautomator` gives clickable-row bounds in physical pixels; every dp below is px ÷ 2. Emoji boxes
were measured from the screenshot by bounding non-background pixels.

Screenshots are in `docs/research/assets/project-navigation-comparison/`:

| File | What it shows |
|---|---|
| `m_tree2.webp` | our menu tree, imported data, three levels, long names |
| `m_rows.webp` | the same tree at the scroll position used for the pixel measurements |
| `m_menu2.webp` | our menu, Projects section folded |
| `m_picker.webp` | our task-editor project picker |
| `sg_menu2.webp` | SingularityApp Android menu with the project section |
| `sg_picker_top2.webp` | SingularityApp Android picker, scrolled to the top |
| `sg_picker.webp` | SingularityApp Android picker mid-list, selected row |

## Import verified

`todo.db` pulled via `run-as`: `projects 132, tasks 12027, notes 4268, tags 277`. The device shows
the same project names SingularityApp's Android build shows, so the two apps are being compared on
the same real data, not on samples.

The shape of the imported tree (recursive query over `projects.parent_id`). 132 rows in the table,
**122 open** (`is_deleted = 0 and closed_at is null`) — the ones the menu can draw:

| Depth | Projects |
|---|---|
| 0 (root) | 20 |
| 1 | 84 |
| 2 | 16 |
| 3 | 2 |

So the profile has **four levels and 20 roots**. That is what makes the two menus diverge:
SingularityApp's mobile menu lists only the roots, ours draws all 122 open rows inline once the
section is unfolded. `m_tree2.webp` is one screenful of that list.

## Singularity Todo, measured

### Row heights, re-measured from pixels

An earlier revision of this document read the row heights off `uiautomator` clickable bounds. That
was wrong, and the critique that caught it was right: the bounds **overlap**. A parent reported
`756 → 852` and its child `822 → 877` — 30px of shared area, which no real layout can have. The
cause is visible in the numbers: the parent's *visual* row is 66px, but the row contains an
`IconButton`, whose 48dp minimum touch target inflates the clickable node to 96px.

So the measurement was redone from two independent sources: the ink bands of the screenshot
(`m_rows.webp`: text pixels in x 185–690, icon pixels in x 130–180) and the row tag boundaries,
whose *starts* are contiguous even when their ends overlap.

| What | Measured | Note |
|---|---|---|
| one-line row pitch | **66px = 33dp** | identical for a parent and a leaf (Kaggle → Свобода → ПНД: 746 → 812 → 878, Δ66) |
| row with a wrapped name | **103–110px = 51–55dp** | two lines of text; `m_rows.webp` |
| text ink per line | 26–28px = 13–14dp | one line of `bodyMedium` |
| icon ink | 34×36px = 17×18dp | matches `ProjectIcon(size = 18.dp)` |
| "18dp row" from the first revision | **artifact** | that row was clipped by the sheet's top edge, its ink band was cut in half |
| "48dp parent rows" from the first revision | **artifact** | 48dp is the expander's touch target, not the row |

Corrected conclusion: the tree is **not** ragged between parents and leaves — both are 33dp. It is
uneven only where a name wraps (33dp → 51–55dp).

### The overlap is a real hit-testing defect, not just a reporting artifact

The inflated touch target is 30px (15dp) taller than the row it belongs to, and that extra area
lies on top of the next row. Tested on the device: with `Свобода от армии` at `762–828` and its
child `ПНД` at `828–883`, a tap at `(400, 835)` — 7px inside the child — opened **Свобода от
армии**. The first 15dp of every child row belongs to its parent.

That is the strongest argument for the 48dp row: the row and its touch target should be the same
rectangle. A 33dp row with a 48dp target will keep stealing taps. (A control tap in the middle of a
child row was not completed: the menu's scroll position changed between attempts, and the tap landed
on the Today list. The defect itself is demonstrated by the tap above.)

Also visible in `m_tree2.webp`:

- long names wrap to two and three lines inside the tree (no `maxLines`), which is what produces the
  51–55dp rows;
- emoji and Material folder icons are drawn at the same 18dp box, so a colour emoji and a grey
  folder read as different weights side by side;
- depth is 16dp per level plus the 48dp expander column, so the third level starts at 96px (48dp).

Task-editor picker (`m_picker.webp`), row bounds:

| Element | Bounds (px) | Height |
|---|---|---|
| sheet title "Project" | `128,933 → 608,971` | 19dp text |
| "No project" | `120,1045 → 624,1083` | row pitch **52dp** |
| project rows | `128,1141 …` then every 104px | **52dp** pitch |

The picker is a flat A→Z list (`AB тест…`, `Airflow 2.0`, `Books`, `Canvas recsys`,
`Competetive ranking PLP`), every project drawn with the same grey `Folder`, no search, no
"create project", no hierarchy, and names that wrap because the label has no `maxLines`.

## SingularityApp Android, measured

Menu (`sg_menu2.webp`): a bottom sheet, not a drawer.

| Element | Bounds (px) | Height |
|---|---|---|
| account row (email + gear) | `0,504 → 720,584` | 80px = **40dp** |
| destination "Сегодня" (with count 113) | `0,584 → 720,680` | 96px = **48dp** |
| "Мои проекты и списки" header (+ button, collapse chevron) | `0,970 → 720,1082` | 112px = **56dp** |
| project "Kaggle" | `0,1082 → 720,1178` | 96px = **48dp** |
| project "Свобода от армии" (chevron) | `0,1178 → 720,1274` | 96px = **48dp** |
| project "Чтение" (chevron) | `0,1274 → 720,1370` | 96px = **48dp** |

Two facts matter here:

1. **The mobile menu shows root projects only, each 48dp, with a `›` chevron for a project that has
   children.** There is no inline expandable tree in the mobile menu at all — the hierarchy is
   entered by drilling into a project. The three-level tree with connectors belongs to the desktop
   build, and it is not what this app does on a phone.
2. **Every row is 48dp.** Parents, leaves and destinations: one height. Their bounds are contiguous
   (`1082 → 1178 → 1274`), so here — unlike our tree — the reported bounds really are the rows: no
   icon button inflates a touch target past the row.

Picker "Перенести в" (`sg_picker_top2.webp`), bounds:

| Element | Bounds (px) | Height |
|---|---|---|
| search field "Начать поиск по проектам" | `16,552 → 704,632` | 80px = **40dp** |
| "Добавить проект" (accent, circled +) | `16,672 → 704,752` | 80px = **40dp** |
| "Входящие" | `16,768 → 704,848` | 80px = **40dp** |
| "Без проекта" | `16,864 → 704,944` | 80px = **40dp** |
| header "Мои проекты" | `32,968 → 211,1008` | 40px = **20dp** |
| project "Kaggle" | `0,1008 → 720,1104` | 96px = **48dp** |
| project "Свобода от армии" | `0,1104 → 720,1200` | 96px = **48dp** |
| child "Получить диагноз…" | icon `84,1227 → 125,1268`, text at x=160 | **48dp** row, **24dp** indent |

- **Hierarchy exists in the picker and is carried by a 24dp indent.** Root text starts at x=113
  (56dp), child text at x=160 (80dp) → 47px ≈ 24dp per level. No connector lines.
- **Emoji measured:** Kaggle 🥇 28×45px, Свобода 🕊 43×38px, Чтение 📔 39×45px. A colour emoji sits
  in a ~20–22dp box inside a 48dp row; children without an emoji show a coloured progress ring of
  ~20dp instead.
- **System destinations are a separate block above "Мои проекты"**: create, Inbox, no project —
  each 40dp, left-aligned at 8dp, separated from the project list by the section header.
- **The search field is part of the picker**, 40dp tall, directly under the title.
- **Selection is a filled rounded row** (`sg_picker.webp`, "Финансы"); the row the task is in now is
  highlighted, not marked with a trailing tick.
- **A secondary value on the right**: completion percentage (`88%`, `26%`, `0%`), which the list
  shows because it is the same list as the projects screen.

## Desktop CSS — secondary, and why it is not translated to dp

Kept for the record only: `.tree-list-item` 6px padding, 12px/20px text, 22px icon column;
indent 24px capped at 132px in the sidebar and 240px in the picker; `.search-popup-project-info`
max-width 300px; `.depth-line` connector. The Android build has **no connector line, no 132px cap,
no 12px text** — so none of these numbers become acceptance criteria. What survives from the desktop
is concept, not measurement: uniform rows, emoji as identity, system destinations above the list,
search in the picker.

## Comparison, mobile to mobile

| Characteristic | Singularity Todo | SingularityApp Android |
|---|---|---|
| Menu project row height | **33dp**; 51–55dp when the name wraps | **48dp** uniform |
| Picker row height | 52dp pitch | **48dp** |
| Menu hierarchy | inline tree, expanded by default | roots only, drill-in chevron |
| Picker hierarchy | none | 24dp indent per level |
| Connector lines | none | none |
| Emoji in menu | 18dp box, 13.5sp glyph | ~20–22dp box in 48dp row |
| Emoji in picker | not shown (grey folder) | shown |
| Long names | wrap (menu and picker) | one line, fade/ellipsis |
| Search in picker | absent | 40dp field under the title |
| Create project in picker | absent | 40dp row above the list |
| Inbox / no project | "No project" only | separate 40dp rows |
| Selected row | trailing tick + primary text | filled rounded row |
| Extra column | none | completion % |
| Empty tree state | nothing drawn | (not observed) |

## Mobile menu: inline tree (A) or roots only (B)?

The open profile is **122 projects with 20 roots**; a one-line row is 33dp today and 48dp after the
row-height fix. That makes the two architectures comparable in numbers:

| | A — inline tree (today) | B — roots only (SingularityApp mobile) |
|---|---|---|
| rows in the menu | 122 | 20 |
| height at 48dp | ~5,860dp ≈ 7.4 screens | ~960dp ≈ 1.2 screens |
| hierarchy visible without a tap | yes | no |
| reaching a level-3 project | one tap, after scrolling | three taps (project → sub-projects → child) |
| parity with Desktop | yes | no; Desktop keeps the tree |
| new work | none | only the menu's drawing changes |

B is cheaper than it looks, because the drill-in already exists: the project detail hero renders
`"N sub-projects"` (`ProjectDetailHero.kt:191-196`) and opens `ChildProjectsSheet`, which navigates
to a child. Nothing in the domain model changes.

**Recommendation (opinion, not a measurement): B on Android, A on Desktop, recorded as its own
ADR — `docs/decisions/2026-11-12-android-menu-roots-only.md` (accepted).** It does not cancel
`2026-11-10-the-menu-projects-section-is-folded-by-default` — with B the section opens onto a
20-row list, and the fold still protects the destinations above it. But B makes the fold less
necessary, so the two decisions should be read together rather than patched one at a time.

The cost of B is honest: three taps to a level-3 project instead of one. On a phone that is the
normal trade, and the picker — not the menu — is where a deep project is usually chosen.

## Emoji: what they run, and what ours can already do

### SingularityApp

Neither build has a hand-written emoji list; both use a picker library over the full Unicode set.

| Build | What it uses | Evidence |
|---|---|---|
| Desktop (Electron) | **`emoji-picker-react`** — 1,898 emoji, categories, localized search, recents, skin tones | `build/js/emoji-picker.*.chunk.js`: `.EmojiPickerReact`, `emojiStyle`, `suggestedEmojisMode: RECENT`, `skinTonePickerLocation: PREVIEW` |
| Desktop appearance | **Apple emoji sprites**, not the system font | `build/resources/emoji-sprite/apple-emoji-64.png`, 4092×4092, 4.5 MB; `emojiStyle: isMobile ? NATIVE : APPLE` |
| Android (Flutter) | **`emoji_picker_flutter`** (MIT) + bundled Noto Color Emoji | `assets/flutter_assets/NOTICES.Z`; `google_fonts/NotoColorEmoji-Regular.ttf`, 23.7 MB, registered in `FontManifest.json` |
| Localization | names and search keywords translated into ru/en/es/uk/de/it/pl/fr/pt | `emoji-data-<locale>.chunk.js`, 1,898 entries per locale; e.g. ru `"smileys_people": "тело людей"` |
| Storage | the **unified** codepoint sequence, not the glyph | `onEmojiClick` returns `{unified, native}`. In the 2026-11-08 export 35 of 132 projects carry one: `1f4fa`, `1f54a-fe0f`, `1f9d8-200d-2642-fe0f`, `1f9d1-200d-1f4bb`, `1f1ec-1f1e7` (flag) |

So "красивые и разнообразные" is two separate things: the **set** (full Unicode, 1,898 in their data,
with categories, search and recents) and the **look** (Apple sprites on desktop; a bundled Noto font
on Android so the OEM cannot substitute it).

### Singularity Todo

- The "pack" is 20 literal strings, `COMMON_EMOJIS` in `IconPickerSheet.kt:36-57`, next to the 18
  Material icons from `ProjectIconRegistry` (the ADR budget). Extending it is editing a list —
  there is nothing else to extend.
- **Arbitrary Unicode already works end to end.** `projects.icon` is `TEXT`; `resolveProjectIcon`
  treats any string that is not a registry key as emoji (`ProjectIconResolver.kt:19-24`); the
  "Custom emoji" field stores whatever is typed (`IconPickerSheet.kt:160-176`). The importer keeps
  multi-codepoint sequences whole and has a test for it (`Mapper.emojiCodepointToIcon`,
  `MapperTest.a multi code point emoji survives whole`: `1f9d1-200d-1f4bb` → 🧑‍💻).
- The real limits are presentation, not storage:
  - emoji are drawn at `size * 0.75` inside an 18dp box (`ProjectIcon.kt:43-58`), so the tree shows
    a 13.5sp glyph where SingularityApp shows ~20–22dp;
  - the box does not clip, so a sequence the device font does not compose falls back to several
    glyphs and spills over the name;
  - the field `trim()`s but does not validate, so a word typed there renders as text in the icon
    slot;
  - there is no browse, search, recents or skin-tone choice.
- Desktop draws emoji with the host font. This machine has Noto Color Emoji; a host without an
  emoji font would draw tofu, where the Electron build always has its sprites.

### What "extend the pack" can mean, cheapest first

1. **No pack at all (works today).** Android's own keyboard has every emoji; the "Custom emoji"
   field accepts it, and on desktop the same field accepts a paste from emojipedia. Zero code.
   The cost is discoverability: nobody finds the field, and there is no search.
2. **Curated row + recents (small).** Keep `COMMON_EMOJIS`, add a persisted "recently used" strip
   (the same shape as `ProjectsTreeSettingsRepository`, one small store), enlarge the box to 20dp
   and make the glyph one line. This is the step that changes what a person sees for the least code.
3. **A real picker (a screen, not a line).** The data is not the hard part — emoji-picker-react's
   data is ~290 KB per locale for 1,898 entries, and `emoji_picker_flutter` ships its own. The hard
   part is that on Compose Multiplatform there is no equivalent in the project today: it is a new
   component (categories, search over localized names, recents, skin tones) or a new dependency.
   Worth doing only if search over the full set is a real need; the tree does not currently show
   emoji at a size where the set matters.
4. **Apple sprites on desktop.** Matching the Electron look means an image-based renderer plus a
   4.5 MB sprite, and the sprite is Apple's artwork. Not recommended; the platform's own emoji are
   the honest choice on a cross-platform app.

### Answer to the three questions

- **"Can arbitrary Unicode emoji be inserted, like on emojipedia?"** Yes — already. The model, the
  resolver and the custom field accept any sequence; the only thing to fix is that such an emoji is
  drawn at 13.5sp in an 18dp box.
- **"How do I extend the pack?"** There is no pack to extend in any meaningful sense: the OS font
  already has the full set. Extend the *discovery* — a recents row first, a picker screen later —
  and keep the icon as a free-form string, which is what makes arbitrary emoji work.
- **"Why does theirs look better?"** On Android it is the same Noto Color Emoji, only bundled
  (23.7 MB) so it does not vary by phone, and presented at ~20dp in a 48dp row with categories and
  search. On desktop it is Apple's artwork. Bundling the font is a size decision; the presentation
  is the part worth copying.

## Answers the critique asked for

**"Inbox and No project are different destinations — check our domain first."** In our model they
are not even the same axis. `AgendaPresets.Inbox` is the agenda of tasks **with no due date**
(`Selector.DateBucket(RelativeBucket.NoDate)`); "No project" is `projectId == null`. A task can have
a project and be undated, or a due date and no project. Putting "Inbox" in the project picker would
make one control set two independent fields. Recommendation: the picker gets "Add project" and
"No project"; no Inbox row. If "Inbox" is wanted as a shortcut, it belongs to a date control, not
this sheet.

**"Confirm row heights with a Compose test, not by reading source."** Agreed, and now they are
measured on the device; the test below asserts them rather than the code shape.

**"Don't move desktop CSS values into Compose."** Done — the revised proposals use only Android
measurements: 48dp, 40dp, 24dp, 52dp.

**"Check the Desktop side, the picker is shared."** `ProjectPickerSheet` is `commonMain`, drawn by
`TaskEditorContent` → `TaskEditorSheetsHost` on both platforms; there is one implementation and no
platform fork to keep in sync. The existing `ProjectPickerDismissTest` is a Desktop test, so the
desktop path is already exercised.

## Proposals, revised

Each item is a difference measured above. Nothing is implemented.

### P0 — uniform row height in the menu tree

- **Now:** 33dp for a one-line row, 51–55dp when the name wraps (measured from pixels). The row's
  `IconButton` carries a 48dp touch target, so the top 15dp of every child row answers to its
  parent — demonstrated by a tap at `(400, 835)` opening the parent.
- **Theirs:** 48dp for every row, and the touch target is the row.
- **Change:** give `ProjectTreeRow` a fixed `height(48.dp)` and `maxLines = 1` on the name, so the
  visual row and the touch target are the same rectangle at every depth.
- **Reuse:** nothing new.
- **Tests:** a Compose test that measures a parent row and a leaf row (both 48dp) and one that taps
  the top edge of a child row and asserts the child opens, not the parent — the regression this
  whole measurement was for.
- **Risk:** a 48dp row makes the 132-row list ~1.5× taller; the section is folded by default, so it
  is opt-in. `MENU-07` screenshots need re-recording.
- **Acceptance:** every row on a branch measures 48dp, and the first pixel of a child row belongs to
  the child.

### P0 — one project representation, three layouts

- **Now:** three visual languages — menu 18dp tinted icon, card 32dp colour circle + 20dp icon,
  picker a grey `Folder` (`ProjectIcon.kt:31-60`, `ProjectCard.kt:53,103,111`,
  `ProjectPickerSheet.kt:154`).
- **Theirs:** the same emoji in the menu, the picker and the task row (~20–22dp), with the name
  beside it.
- **Change:** define the contract once — `ProjectIcon(icon = …, size = 20.dp, tint = …)` plus the
  name, one line, ellipsis; `ProjectIcon` and `resolveProjectIcon` already implement the resolution
  (Material key vs. emoji vs. folder). Do **not** merge the three layouts: a card may keep its
  32dp colour circle.
- **Reuse:** `ProjectIcon`, `resolveProjectIcon`, `ProjectIconRegistry`.
- **Tests:** one test per surface that the project's stored emoji is rendered, not a folder.
- **Risk:** the card's colour circle is the only place colour carries meaning — keep it there, do
  not add colour to the menu.
- **Acceptance:** the same project shows the same glyph in menu, picker and task editor.

### P0 — the picker, rebuilt on the measured pattern

- **Now:** flat A→Z, grey folders, no search, no hierarchy, 52dp pitch, labels wrap.
- **Theirs:** title, 40dp search, 40dp system rows (create / inbox / no project), `Мои проекты`
  header, 48dp project rows, 24dp indent, emoji, one-line labels, filled selection.
- **Change:** sort by the tree, use `ProjectTreeBuilder` for depth, draw `ProjectIcon`, single line,
  add the "Add project" row. Add search only in the P1 step below.
- **Reuse:** `ProjectTreeBuilder` (pure, tested), `ProjectIcon`, `TaskEditorSheetHost`.
- **Tests:** hierarchy (a child follows its parent at 24dp more), emoji rendered, "Add project"
  creates and selects, dismissal unchanged (`ProjectPickerDismissTest`).
- **Risk:** the picker is shared with Desktop — the sheet is one composable, so both platforms
  change together; the desktop test is the guard.
- **Acceptance:** on the imported profile, a three-level branch is visible as indent, emoji are
  shown, rows are 48dp, and a long name stays on one line.

### P1 — 24dp indent and one-line names in the menu

- **Now:** 16dp step, names wrap (that is where the 33dp rows come from).
- **Theirs:** 24dp in the picker; the menu has no tree at all.
- **Change:** `24.dp` step plus `maxLines = 1`; no cap needed until the 132-project run shows the
  deepest branch leaving the sheet.
- **Reuse:** nothing.
- **Tests:** a long-name row measures 48dp; `MENU-07` screenshots re-recorded.
- **Risk:** the 132-project run is what decides whether 24dp leaves room for names; check it before
  merging.
- **Acceptance:** no row taller than 48dp with names of 40+ characters.

### P1 — search in the picker, reusing the list's search

- **Now:** absent; 122 open projects in a flat list, 20 of them roots.
- **Theirs:** a 40dp field directly under the title; the result list is the same tree, filtered.
- **Change:** filter project names and keep each match's ancestors as context rows, so the result
  keeps its indent. Reuse the query state pattern from `ProjectsScreen`; do not add a second search
  engine.

The six questions the critique asks, answered as a specification (none of it is measured — this is
the behaviour to implement and then verify):

| Question | Behaviour |
|---|---|
| Only parents, or siblings too? | Only the matched projects and their ancestor chain. Siblings are noise: the user is choosing one project, not browsing. |
| Can a found branch be expanded? | No expanding while filtering — ancestors are already drawn expanded. Collapsing a branch mid-search would hide the match. |
| What happens when the query is cleared? | The full tree returns with the per-project fold state it had before the search (`ProjectsTreeSettingsRepository`), and the list stays scrolled to the selected project if there is one. |
| How is the match highlighted? | The matched substring is emphasised in the name; the currently selected project keeps its filled row. Two different signals, so a match never looks selected. |
| How is the path shown? | Under the name, one dimmed line: `Разработка → Android → Интерфейс`, built from the ancestor chain. Truncate at the **start** (ellipsis on the left) so the leaf — the thing being chosen — stays visible. |
| How does it behave over 122 projects? | Filtering 122 strings is nothing; no debounce needed. The ancestor lookup is what `ProjectTreeBuilder.build` already does when it builds the tree — filter the built nodes by name and keep the ancestors of each hit. |

- **Tests:** a level-3 match comes back with its two ancestors and depth 2; clearing the query
  restores the pre-search fold state; a query with no match shows the empty state rather than the
  full list.
- **Risk:** the ancestor rows are tappable projects, not decoration — choosing a parent from a
  result must work, or the user will read the path as a label.
- **Acceptance:** typing three letters finds a level-3 project, shows `A → B → C` beneath it, and
  clearing the field brings back the tree exactly as it was.

### P2 — empty tree state

- **Now:** unfolding with no projects draws nothing (seen before the import).
- **Change:** one dimmed "No projects yet" row.
- **Acceptance:** the fold control changes something visible on an empty profile.

### Dropped

- **Connector lines.** The Android build has none; the indent carries the hierarchy. Not proposed.
- **Inbox in the picker.** Different axis, see above.
- **Replacing the menu tree with a roots-only drill-in list is no longer "dropped".** It moved to
  its own section above: it is a real, measurable option (20 rows instead of 122) and needs its own
  ADR rather than a line in a research note. What is still dropped is doing it *without* that ADR.
- **A roots-only menu that is also the only way to a child.** If B is adopted, the picker keeps the
  full tree; the menu must not become the only route to a nested project.

## Still unverified

- A control tap in the middle of a child row after the mis-tap test. The mis-tap is demonstrated;
  the control was not completed because the menu's scroll position moved between attempts.
- Whether a fixed 48dp row plus one-line names keeps depth-3 names readable on a 360dp phone. The
  profile reaches depth 3 (two projects): 3 × 24dp of indent plus a 24dp expander leaves ~260dp of
  name width — verifiable in one screenshot after the change.
- Emoji alignment across scripts at 20dp — the import contains emoji from several sets, and the
  mobile SingularityApp ships its own emoji font.
- SingularityApp's empty states and its picker's behaviour while typing (needs a session with the
  other app's UI, not a static screenshot).
