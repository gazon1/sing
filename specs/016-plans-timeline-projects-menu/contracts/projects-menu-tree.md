# Contract: The Projects Tree in the Menu

**Feature**: `specs/016-plans-timeline-projects-menu`
**Date**: 2026-10-26

What the app menu promises once it carries projects and the Calendar, on both platforms.

---

## Shape

The menu gains a section and an entry.

```text
┌─────────────────────────────────┐
│ Account                         │
│   Profile & sync                │
│   Profiles                      │
├─────────────────────────────────┤
│ Search                          │
│   Quick search                  │
├─────────────────────────────────┤
│ Destinations                    │
│   Statistics                    │
│   Notes                         │
│   AI Chat                       │
│   Search                        │
│   Archive                       │
│   Calendar            ← moved in │
├─────────────────────────────────┤
│ Projects                        │
│   ▾ Product            ← root   │
│       Website            ← child │
│       Mobile            ← child │
│   ▸ Research            ← root   │
│   All projects        ← the list │
└─────────────────────────────────┘
```

A **project node** is a row with the project's name, an expander when it has children, and an
indent that grows with its depth. Tapping the name opens the project; tapping the expander shows
or hides the children.

The **Projects** section ends with an entry into the full project list — the screen that searches
across projects, sorts them and manages the closed ones.

---

## Guarantees

1. **One tree, two drawings.** The Android sheet and the Desktop drawer render the same tree from
   the same composable. The same projects appear in the same order, at the same depths.
2. **The hierarchy is visible.** A project nested under another is indented under it, not listed
   beside it. A flat list of names would lose the one thing a tree shows.
3. **A root is a root.** A project with no parent is at the top level. A project whose parent is
   gone is also at the top level — it is not dropped.
4. **No expander without children.** A project with no children offers nothing to expand.
5. **Closed projects are not in the tree.** They are managed from the full project list.
6. **The tree is live.** A project created, renamed, moved or deleted elsewhere shows up here
   without the menu being closed and reopened in between.
7. **No cycles.** A project hierarchy with a cycle in it does not hang the menu.
8. **Tapping a project opens it.** The tree is a way into the project, not a summary of it.
9. **The Calendar is reachable here.** It is an entry of the menu on both platforms, and it is no
   longer one of the tabs.
10. **The other entries are unchanged.** Everything the menu held before, it still holds.

---

## Producers

- The **project repository** produces the projects, with their task counts.
- The **tree builder** arranges them into roots and children, using the repository's depth-first
  traversal — which is what turns a cycle into an error instead of a hang.
- The **menu model** decides where the section sits and which entries surround it.

## Consumers

- The **Android bottom sheet** renders the menu.
- The **Desktop navigation drawer** renders the same menu.
- The **project screen** is opened by tapping a node.
- The **Calendar screen** is opened by choosing its entry.
- The **full project list** is opened by choosing the entry at the end of the Projects section.

---

## Rejected alternatives

- **A tree per platform.** Two implementations of one feature, and the parity requirement would
  depend on somebody noticing the difference.
- **Every project as a navigation destination.** That would put project ids into the navigation
  key space, where the destination set is a closed, serialisable type.
- **The full project list replaced by the tree.** The list searches, sorts and manages the closed
  projects; the tree does none of those, and removing the list would remove them.
- **Projects as a tab again.** The tab bar has no room for a hierarchy, and the request was to
  move the projects into the menu.
