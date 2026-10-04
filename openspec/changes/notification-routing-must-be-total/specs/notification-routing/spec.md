# notification-routing

## ADDED Requirements

### Requirement: REQ-1 Every Notification produces exactly one visible response

The application SHALL guarantee that every `Notification` it emits produces
exactly one visible response — a dialog, a snackbar, or nothing by an explicit
`Notification.None`.

A `Notification` variant SHALL NOT be constructible in a state the host cannot
render. The host SHALL NOT contain a branch that receives a value and renders no
UI for it, because a caller that takes that branch gets a success path with no
output and no signal that anything was dropped.

The guarantee SHALL be a property of the host, pinned by a test at the host, not
by one test per call site: a per-call-site test passes when a new call site is
added, and the property it needs is about the host.

**Rationale:** `NotificationHost.kt:112` routed every `Notification.Text` to
`ResultDialog`, and `ResultDialog.kt:18` began with `if (text == null) return`.
A caller writing `Notification.Text("Deleted")` got a composable invocation that
rendered nothing. No call site was broken when this was found, which is exactly
why the shape survived: the defect only appears at the next call site, and
nothing in the type system objected to writing it.

#### Scenario: A caller builds a text notification with no body

- **Given** the notification types
- **When** a caller constructs a text notification that carries no body
- **Then** the code does not compile
- **And** the compiler names the call site, rather than the failure surfacing
      as a missing dialog at runtime

#### Scenario: A caller emits a text notification with a body

- **Given** a text notification carrying a non-empty body
- **When** the host routes it
- **Then** exactly one dialog is shown

#### Scenario: A caller intends no response

- **Given** a completed action that needs no acknowledgement
- **When** the caller emits it
- **Then** it emits `Notification.None`
- **And** the registry of variants has no fourth option meaning "shown and
      invisible"

#### Scenario: A new Notification variant is added

- **Given** a new variant on the sealed type
- **When** the host's routing is exhaustive over it
- **Then** the compiler requires a branch
- **And** the host-level test asserts that branch produces a visible response
