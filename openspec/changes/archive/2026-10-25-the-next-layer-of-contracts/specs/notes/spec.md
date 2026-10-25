# notes — Observable Behavior

**capability:** `notes` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-NOTES-REWRITE-3

The note editor's rewrite watcher SHALL report a replacement, and only a
replacement.

The watcher decides, from the note the repository holds and the draft the user
is editing, whether the content was replaced from outside. Its decisions SHALL
be:

- a note whose content differs from a **clean** draft's SHALL be reported as a
  replacement;
- a note whose content differs from a **dirty** draft's SHALL NOT be reported
  — the user's unsaved edits win;
- a note whose content equals the draft's SHALL NOT be reported — that is the
  echo of the editor's own save;
- a note whose id differs from the draft's SHALL NOT be reported;
- a note with no HTML SHALL be reported with the markdown converted to HTML,
  and with both absent, with an empty body.

The draft SHALL be read **live** when the watcher decides, not snapshotted when
the watcher started: a snapshot cannot tell the user typing from somebody else
rewriting, which is the whole question.

These decisions SHALL be pinned by a direct test of the watcher, driving a live
draft. A rule that only exists in a KDoc is not a rule the next reader can
rely on.

#### Scenario: A note rewritten under a clean editor

- **Given** the editor holds a note and the draft is clean
- **When** the note is rewritten from outside
- **Then** the watcher reports the rewrite

#### Scenario: A note rewritten under a dirty editor

- **Given** the editor holds a note with unsaved edits
- **When** the note is rewritten from outside
- **Then** the watcher reports nothing

#### Scenario: The editor's own save

- **Given** the editor holds a note and the draft is clean
- **When** the note is saved and the repository echoes it back
- **Then** the watcher reports nothing

#### Scenario: A note with no HTML

- **Given** the editor holds a note whose body is markdown and whose HTML is
  absent
- **When** the watcher observes the note
- **Then** the watcher reports the note with the markdown converted to HTML
