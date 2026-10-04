# crash-reporting

## ADDED Requirements

### Requirement: REQ-4 A deliberate continuation is recorded when it is taken

Where a component proceeds on failure rather than stopping, it SHALL leave a record that
distinguishes the degraded outcome from the healthy one.

The record SHALL accompany the failure report rather than replace it. What is missing is not the
fact that something failed — that is already reported — but the fact that the component chose to
continue regardless.

This applies most strongly where continuing is the correct choice, since a component that fails
open is indistinguishable from a healthy one by inspection of its output.

#### Scenario: A mandatory configuration cannot be read

- **Given** a component that cannot read configuration it needs in order to decide whether to
  admit the user
- **When** it admits the user anyway, on default configuration
- **Then** a record notes that it proceeded, so a period in which everyone was admitted is
  distinguishable from a period in which everyone was legitimately admitted

#### Scenario: The component stops instead of continuing

- **Given** a component that cannot read a configuration it requires
- **When** it declines to act rather than proceeding
- **Then** no continuation record is written, because nothing was continued
