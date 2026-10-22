# The review is a place you can go — Observable Behavior

**capability:** `ai/tool-registry` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-TOOLREG-016

A proposal waiting for a decision **SHALL** be visible to the user in a place the
user can go, and the user **SHALL** be able to decide each change in it.

**Rationale:** a proposal the user cannot reach is a proposal the user can only answer
by telling the assistant to stop. The answer to a proposal is "yes" or "no" about a
specific change, and it belongs next to the change.

#### Scenario: The waiting changes are listed
- A change has been proposed and not yet decided
- The Review screen lists it
- The screen is reachable from the menu

#### Scenario: The user applies a change
- The user applies a change
- The change is applied through the app's usual path
- The change is no longer waiting

#### Scenario: The user turns a change down
- The user turns a change down
- Nothing is applied
- The change is no longer waiting

#### Scenario: Nothing to review
- No change is waiting
- The screen says so

---

### Requirement: REQ-TOOLREG-017

A tool that names the first action of a task **SHALL** name exactly one action, and
**SHALL** say how that action is known to be finished.

**Rationale:** the tool exists for the task that cannot be started, because starting
it needs a decision nobody has made. A list of steps does not help; a plan is what the
user already has, in the task. What helps is the one physical move, and the sign that
it is done — because a step nobody can check is a step nobody can be finished with.

#### Scenario: The answer is one action
- The assistant asks for the first step of a task
- The answer names one action
- The answer says what finishes that action

#### Scenario: A thought is not an action
- The task begins with "figure out the pricing"
- The action is something physical, not the figuring
