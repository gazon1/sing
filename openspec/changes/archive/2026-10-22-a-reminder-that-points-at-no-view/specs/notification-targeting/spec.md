# Notification targeting — Observable Behavior

**capability:** `notification-targeting` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-NOTIF-TARGET-001

A reminder raised from a saved agenda view **SHALL** record which saved view it came
from, and a notification posted for that reminder **SHALL** carry that saved view as
its target.

**Rationale:** the delivery path already reads the reminder's saved view, hands it to
the notifier, and the notifier turns it into a target the deep-link registry can
open. Every reminder that reaches the notifier without a saved view therefore arrives
with nothing to open — the link works, and there is never a link to open. The
capability is not missing; it is unreachable.

**Test coverage:** a test that creates a reminder from a saved view and asserts the
stored reminder carries that view; a test that posts the notification for it and
asserts the target survives into the payload.

#### Scenario: A reminder set from a saved view remembers it
- The user is on a saved agenda view
- The user sets a reminder on a task shown there
- The stored reminder records that saved view

#### Scenario: A reminder set outside any saved view carries no target
- The user sets a reminder on a task from a screen that is not a saved view
- The stored reminder records no saved view
- The notification is posted without a target

---

### Requirement: REQ-NOTIF-TARGET-002

Tapping a notification that carries a target **SHALL** open the screen that target
names, in the tab that screen belongs to.

**Rationale:** a notification that opens the app at an arbitrary screen is not a
notification with a target; it is a notification with a target that was dropped
somewhere between the reminder and the payload. The rule has to hold end to end, or
the storage of the target is decoration.

**Test coverage:** the deep-link registry test for the saved-view link, plus a test
that the payload a reminder notification carries is the one the registry matches.

#### Scenario: Tapping a targeted notification opens the view
- A reminder raised from a saved view has fired
- The user taps the notification
- The saved view opens, in the tab it belongs to

#### Scenario: Tapping an untargeted notification opens the app
- A reminder raised outside any saved view has fired
- The user taps the notification
- The app opens at its usual entry point, without a failed target

---

### Requirement: REQ-NOTIF-TARGET-003

A notification whose target names a saved view that no longer exists **SHALL** open the
app without failing, and **SHALL NOT** leave the app on a screen it cannot render.

**Rationale:** reminders outlive the saved views they were raised from — a view can be
deleted while its reminders keep firing. A target that points at nothing must degrade
to "no target", not to a crash or a blank screen.

**Test coverage:** a test that posts a notification for a reminder whose saved view has
been deleted and asserts the app still opens.

#### Scenario: The saved view is deleted before the reminder fires
- A reminder was raised from a saved view
- The saved view is deleted
- The reminder fires and its notification is posted
- Tapping the notification opens the app without a dead target
