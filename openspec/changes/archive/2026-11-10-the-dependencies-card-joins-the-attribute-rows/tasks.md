# the-dependencies-card-joins-the-attribute-rows — tasks

**Issue:** #2010 · **Spec:** `tasks` (Requirement: The dependencies card is drawn with
the attribute rows)

---

## 1. Move the card

- [x] Draw the dependencies card after the due-date row and before the start-date row.
      **Test:** `TaskEditorSlotsOrderTest` — the card stays after the due date when it
      appears.
- [x] Keep the rows above it where they were.
      **Test:** `TaskEditorSlotsOrderTest` — the priority and the due date have the same
      top with and without the card.

## 2. Keep its behaviour

- [x] Keep the visibility condition, the label, the callback and the test tag.
      **Test:** the dependency picker tests; the card still opens
      `TaskEditorSheet.Dependencies`.

## Out of scope

- The child sections and their order.
- The order of the other attribute rows among themselves.
