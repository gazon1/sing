# tasks

## ADDED Requirements

### Requirement: A setting that changes the agenda changes the agenda

A setting the user can change in Settings SHALL affect the screen it describes. A
switch that is rendered, persisted, and read by nothing SHALL be reported as a
defect, and the defect SHALL be fixed by wiring the value or by removing the
control — never by baselining it.

Where a setting selects between two known behaviours, the decision SHALL live in
one pure function that both the production caller and the tests use, so the
behaviour cannot drift from the setting that promises it.

**Rationale:** the `WorkSchedule.weekendSat` and `WorkSchedule.weekendSun`
switches — "Saturday" and "Sunday" under a section headed "Working Days" — were
persisted, rendered, and read by nobody. The work-day agenda (morning work,
lunch, afternoon work) was shown on a Saturday the user had explicitly marked as
*not* a working day. `AgendaPresets.workDayPreset` named the gap itself — "Weekend
handling is the caller's responsibility" — and the caller never took it.

#### Scenario: A weekend day that is not a working day

- **Given** the user has not marked Saturday as a working day
- **And** today is a Saturday
- **When** the Today agenda is built
- **Then** it shows the date-based sections
- **And** it does not show the work-day blocks

#### Scenario: A weekend day that is a working day

- **Given** the user has marked Sunday as a working day
- **And** today is a Sunday
- **When** the Today agenda is built
- **Then** it shows the work-day blocks

#### Scenario: A weekday

- **Given** today is a Wednesday
- **When** the Today agenda is built
- **Then** the work-day blocks apply
- **And** the two working-day switches do not change that
