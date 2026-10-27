# navigation-and-menu Specification

## Purpose

The app's navigation frame: the bottom navigation bar, and the menu that opens from it.

The menu is the place the frame has room for things the bar does not — a hierarchy, and screens
that are not destinations of their own. This spec covers what the bar holds and what the menu
holds, on both platforms.

The decisions behind it live in
`docs/decisions/2026-10-26-plans-timeline-and-projects-menu.md`.

## ADDED Requirements

### Requirement: REQ-NAV-MENU-1 The Calendar is not a tab and is an entry of the menu

The bottom navigation bar SHALL NOT hold a Calendar item. The menu SHALL hold a Calendar entry
that opens the Calendar screen, and the Calendar screen's own behaviour SHALL NOT change.

**Rationale:** The bar is full, and the Calendar duplicates what the timeline now does at a
different resolution. The screen itself stays exactly as it was — it moved in the navigation, it
did not change.

#### Scenario: The bar has no Calendar button

- **Given** the app is running
- **When** the user looks at the bottom navigation bar
- **Then** there is no Calendar item among the tabs, and the other tabs are still there

#### Scenario: The menu opens the Calendar

- **Given** the app is running
- **When** the user chooses the Calendar entry in the menu
- **Then** the Calendar screen opens and the menu closes

### Requirement: REQ-NAV-MENU-2 The menu shows the projects as a tree

The menu SHALL contain a section holding the user's projects as a tree. A project with no parent
SHALL be listed at the top level. A project with a parent SHALL appear under that parent,
indented, when the parent is expanded. A project with no children SHALL show no control for
expanding it.

**Rationale:** The projects are a hierarchy, and a flat list of names loses the one thing a tree
shows: which project sits under which.

#### Scenario: Children are nested under their parent

- **Given** a project with a child project, and a project with no children
- **When** the user opens the menu
- **Then** the two are listed at the top level, expanding the first reveals the child indented
  under it, and the second shows no expanding control

#### Scenario: A project whose parent is gone is at the top level

- **Given** a project whose parent no longer exists
- **When** the user opens the menu
- **Then** the project is listed at the top level rather than being hidden

### Requirement: REQ-NAV-MENU-3 The tree shows what is being worked on

A closed project SHALL NOT appear in the tree. The tree SHALL reflect a project that was created,
renamed, moved or deleted elsewhere without the menu needing to be closed and reopened in between.

**Rationale:** The menu is opened and closed many times a day, so a tree showing what the projects
looked like when the app started would be wrong more often than right. Closed projects have left
the work, and are managed from the full list.

#### Scenario: A closed project is absent

- **Given** a project that has been closed
- **When** the user opens the menu
- **Then** the project is not in the tree

#### Scenario: The tree is live

- **Given** a project that is renamed and another that is created elsewhere in the app
- **When** the user opens the menu again
- **Then** the tree shows the new name and shows the new project

### Requirement: REQ-NAV-MENU-4 A project in the tree opens the project

Tapping a project in the tree SHALL open that project's own screen, and the menu SHALL close.

**Rationale:** The tree is a way into the project, not a summary of it. A menu that showed the
hierarchy and did not navigate would be a picture of the projects.

#### Scenario: Tapping a project opens it

- **Given** a project in the tree
- **When** the user taps it
- **Then** that project's screen opens and the menu is closed

### Requirement: REQ-NAV-MENU-5 The full project list stays reachable

The menu's projects section SHALL offer an entry into the full project list, with its search and
its sorting.

**Rationale:** The list does things the tree does not: it searches across projects, it sorts them,
and it manages the closed ones. Moving the projects out of the tab bar must not remove those.

#### Scenario: The list is still one entry away

- **Given** several projects exist
- **When** the user opens the entry at the end of the menu's projects section
- **Then** the full project list opens with its search and its sorting

### Requirement: REQ-NAV-MENU-6 Both platforms show the same menu

The menu on Android and the navigation drawer on Desktop SHALL offer the same entries, including
the Calendar and the projects section, and SHALL show the same projects in the same order at the
same depths.

**Rationale:** The two menus are drawn by different code. A feature added to one of them and not
the other is a feature half the users do not have.

#### Scenario: The two menus match

- **Given** the same profile open on an Android device and on the desktop build
- **When** the user compares the menu on one with the drawer on the other
- **Then** they offer the same entries and the same projects in the same order
