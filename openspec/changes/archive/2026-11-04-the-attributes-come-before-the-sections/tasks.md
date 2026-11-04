# the-attributes-come-before-the-sections — tasks

**Issue:** #1754 · **Spec:** `tasks` (Requirement: The task's attributes come before
the sections that add to it)

---

## 1. Draw the rows before the sections

- [x] Move the editor's attribute rows directly under the description and the
      caller's sections to the end of the column.
      **Test:** `desktopApp/.../TaskEditorContentOrderTest` — the rows come before
      the sections.
- [x] Keep the rows themselves untouched: the same rows, the same pickers, the same
      callbacks, the same test tags.
      **Test:** the existing task editor tests pass with unchanged expectations.

## 2. Prove it on a phone-sized screen

- [x] Assert that the priority and the due date are on the screen of a 360 dp
      window without a scroll.
      **Test:** `desktopApp/.../TaskEditorContentOrderTest` — the priority and the
      due date are on the screen without a scroll.

## 3. Say what the order is, where the code is read

- [x] Update the comments and the KDoc that described the sections as drawn under
      the fields.
      **Test:** no test — the comment and the code must agree.
- [x] Update the comments in the device flows that said the rows were below the
      fold.
      **Test:** no test — the flows still scroll into view, and the comment no
      longer says a thing that is false.

## Out of scope

- Collapsing the empty child cards into one row.
- The content of the sections themselves.
- Any change to what a row does when it is tapped.
