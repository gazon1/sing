# offline-sync — amendment

## ADDED Requirements

### Requirement: REQ-OS-032 A payload that cannot be read is skipped and recorded

A pulled event whose payload was present but could not be turned into a document SHALL
NOT be applied to local data, SHALL advance the download position past the event, and
SHALL be recorded in the dead-letter store with the payload and the reason the read
failed.

Advancing the position is not optional. The server sends the same bytes on every
delivery, so a position held on one unreadable row would stop the account
synchronising anything else, permanently, over a change that can never apply anyway.

Recording it is the other half of the same rule, and it answers a different question:
the position says the account keeps working, and the record says that a change the
server holds is not on this device. A payload that is dropped without a trace is a
divergence between the server's state and this device's state that nobody can find.

The record SHALL belong to the account that received the event, so that an account
scoped erase takes it with the rest of that account's work.

A payload that was never sent, an event written by a newer protocol version, and a
document written by a client that predates a model this client has SHALL NOT be recorded
as unreadable. In each of those cases nothing was misread — the payload is absent, or
it is readable by a client that understands it — and recording them would fill the
shelf with rows that describe nothing.

The requirement is about the record only. Whether a recorded event may be retried, and
whether the shelf is shown to the user, are separate questions and are not answered
here.

#### Scenario: A payload that does not decode

- The server sends a task event whose document cannot be decoded into a task
- The device does not write the row, and the download position advances past the event
- The event is counted as dropped
- The dead-letter store holds a row for the event, carrying the payload as it arrived
  and the reason the decode failed
- The row belongs to the account that received the event
- The next cycle reads the events after it, and this account keeps synchronising

#### Scenario: The same event arrives again

- The server delivers the same unreadable event again
- The dead-letter store still holds one row for it, not two

#### Scenario: A payload the server never sent

- The server sends an event with no document
- The device does not write the row, and the download position advances past the event
- The dead-letter store holds no row for it, because there were no bytes to read

#### Scenario: An event written by a newer client

- The server sends an event whose protocol version is newer than this client supports
- The device does not write the row, and the download position advances past the event
- The dead-letter store holds no row for it, because the payload was readable by a
  client that understands it

#### Scenario: A record that cannot be written

- The dead-letter store cannot be written to
- The download position still advances past the event, and the account keeps
  synchronising
