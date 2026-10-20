# tasks

## ADDED Requirements

### Requirement: A setting whose heading names a set is modelled as that set

A setting whose heading names a *set* — a working week, a set of working days —
SHALL be modelled as that set, and the section's controls SHALL cover every
member of it. The model SHALL be able to express every value the section
offers, so that no member of the set needs a special case elsewhere in the app.

The screen that offers the set and the screen that consumes it SHALL ask the
same question of the same value, and the decision SHALL live in one pure
function that both the production caller and the tests use.

When a set replaces a previous model, the values a user already chose SHALL
survive the change: the old values SHALL be read the way the *screen* read them,
not the way the old field names suggest, and the first write of the new model
SHALL make the old values inert. A value the user never chose SHALL be
distinguishable from a value the user chose to be empty.

**Rationale:** the `WorkSchedule.weekendSat` and `WorkSchedule.weekendSun`
switches — "Saturday" and "Sunday" under a section headed "Working Days" — were
persisted, rendered, and read by nobody. Two switches named after the weekend
can only say something about the weekend: the section promised a working week
and delivered a weekend. The dead-settings baseline then waived the two fields,
which was honest but left the section promising something it could not express.
The field names read the opposite way round from the switches — `weekendSat =
true` means Saturday *is* a working day — so a migration that followed the
name would move every weekend worker to the wrong week.

#### Scenario: A weekend day that is not a working day

- **Given** the user's working days are Monday to Friday
- **And** today is a Saturday
- **When** the Today agenda is built
- **Then** it shows the date-based sections
- **And** it does not show the work-day blocks

#### Scenario: A weekend day that is a working day

- **Given** the user's working days include Sunday
- **And** today is a Sunday
- **When** the Today agenda is built
- **Then** it shows the work-day blocks

#### Scenario: A weekday

- **Given** today is a Wednesday
- **And** Wednesday is one of the user's working days
- **When** the Today agenda is built
- **Then** the work-day blocks apply

#### Scenario: A working week that is not Monday to Friday

- **Given** the user's working days are Saturday and Sunday only
- **And** today is a Monday
- **When** the Today agenda is built
- **Then** it shows the date-based sections
- **And** the work-day blocks do not apply

#### Scenario: A choice made before the working week was a set

- **Given** a user who turned the "Saturday" switch on before the section held
      a set
- **When** their working days are read
- **Then** Saturday is one of their working days
- **And** Sunday is not
