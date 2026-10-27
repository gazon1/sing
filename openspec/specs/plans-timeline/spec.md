# plans-timeline Specification

## Purpose
The timeline screen: what is coming, grouped by time, at a size the user chooses.

The screen answers one question — what is ahead of me — and it answers it as a list of periods,
each holding the work due inside it. The period may be a day, a week or a month.

This spec covers the screen's observable behaviour. The decisions behind it live in
`docs/decisions/2026-10-26-plans-timeline-and-projects-menu.md`.

## Requirements

### Requirement: REQ-PLANS-TIMELINE-1 The timeline groups open work by period

The screen SHALL present the open tasks that have a due date as a sequence of sections, one
section per period, each section holding exactly the tasks due inside that period that are not
overdue. The sections SHALL be ordered from the earliest period to the latest.

**Rationale:** The screen answers "what is coming". Time is what the question is about, so the
grouping is by time rather than by project. A task belongs to exactly one period, because a task
that appears twice is a row the user cannot act on once.

#### Scenario: Days appear in order with that day's tasks

- **Given** tasks are due on three different days
- **When** the user opens the timeline
- **Then** the sections appear earliest date first, each header names its day, and each section
  holds exactly the tasks due on that day

#### Scenario: A task appears once and only once

- **Given** a task due inside the horizon
- **When** the user opens the timeline at each granularity
- **Then** the task is listed exactly once at each of them

#### Scenario: A day with no work is not a section

- **Given** a day with no tasks between two days that have tasks
- **When** the user opens the timeline
- **Then** the empty day has no section, and the days that do have tasks keep their order

### Requirement: REQ-PLANS-TIMELINE-2 The period size is the user's choice

The screen SHALL let the user choose between a day, a week and a month as the size of one period,
and SHALL show which choice is in effect. A week SHALL run from Monday to Sunday, and a month
SHALL be a calendar month.

**Rationale:** A day is the right answer for today and the wrong answer for a fortnight. The
choice belongs to the user, and the screen has to say which answer it is giving.

#### Scenario: The choice regroups the sections

- **Given** tasks due in more than one calendar month
- **When** the user changes the period size to week and then to month
- **Then** the section headers become date ranges and then calendar months, and a task stays in the
  section that contains it

### Requirement: REQ-PLANS-TIMELINE-3 The horizon belongs to the period size

The day period SHALL cover the next thirty days, the week period the next twelve weeks and the
month period the next twelve months, counted from today. A task due beyond the horizon of the
period size in effect SHALL NOT be listed.

**Rationale:** "Upcoming" has to mean something. The horizon is what makes it mean something, and
it is a property of the period size: a month is asked about further out than a day.

#### Scenario: A distant task appears only at the wider period size

- **Given** a task due in forty-five days and one due tomorrow
- **When** the user changes the period size through day, week and month
- **Then** the task due tomorrow is listed at each of them, and the task due in forty-five days is
  listed only at month

### Requirement: REQ-PLANS-TIMELINE-4 Overdue work leads, and undated work stays out

The screen SHALL place tasks whose due date is before today in a leading section of their own,
ahead of the dated sections, and SHALL NOT list a task again inside any dated section. The screen
SHALL NOT list a task that has no due date.

**Rationale:** Overdue work belongs to no future day, so it gets a section of its own at the
front. Work without a date has no period to belong to, and the screen is about what is scheduled.

#### Scenario: Overdue work leads the timeline

- **Given** a task due in the past and one due today
- **When** the user opens the timeline
- **Then** the overdue section comes before the section for today, and the overdue task is not
  repeated in any dated section

#### Scenario: Undated work is not on the timeline

- **Given** a task with no due date
- **When** the user opens the timeline
- **Then** the task is not listed

### Requirement: REQ-PLANS-TIMELINE-5 The chosen period size is remembered

The screen SHALL remember the period size the user chose, per profile, and SHALL open in that
period size the next time it is opened.

**Rationale:** The screen is opened many times a day. A choice that resets every time is a choice
the user has to make every time.

#### Scenario: The choice survives leaving the screen

- **Given** the user has chosen the week period size
- **When** the user leaves the screen and opens it again
- **Then** the screen opens in the week period size

### Requirement: REQ-PLANS-TIMELINE-6 A task opens, and a new one inherits its period

Tapping a task SHALL open that task's own screen. Creating a task from a section SHALL open the
task editor with the due date already set to a date inside that section's period — today when the
section holds today, and otherwise the first day of the period.

**Rationale:** The two ways a user acts on a section both have to land inside it. A new task that
came out with no date would be work the timeline is not showing. The date may not be in the past
either: a task born overdue is a task the user did not plan, and it would land in the overdue
section rather than the one it was added from.

#### Scenario: A task opens from its section

- **Given** a task inside a section
- **When** the user taps it
- **Then** that task's own screen opens

#### Scenario: A new task inherits the section's date

- **Given** a section the user is adding a task to
- **When** the user chooses to add a task there and the editor opens
- **Then** the due date is already set to a date inside that section's period, and to today when the
  section holds today

### Requirement: REQ-PLANS-TIMELINE-7 The screen says when there is nothing scheduled

When no task falls inside the horizon, the screen SHALL show a message that there is nothing
scheduled rather than an empty list.

**Rationale:** A blank screen reads as a screen that failed to load.

#### Scenario: An empty horizon says so

- **Given** no task falls inside the horizon
- **When** the user opens the timeline
- **Then** the screen says that there is nothing scheduled
