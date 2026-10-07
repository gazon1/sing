# A cycle drains the feed, or says it could not

**capability:** `offline-sync` | **status:** proposed

**Issue:** #176

---

## ADDED Requirements

### Requirement: REQ-OS-015

A download cycle SHALL keep reading until the feed reports that it has no more, so that a
backlog larger than one page is applied in one cycle. A cycle SHALL report how many
changes it received in total, across every page it read.

#### Scenario: A backlog larger than one page is applied in one cycle
- More changes are waiting than fit in a single page
- The cycle applies all of them
- The stored position is the end of the last page, not the end of the first

#### Scenario: A backlog that fits one page does not cost an extra request
- The whole backlog fits in a single page
- The cycle asks once
- A short page is taken as the end of the feed

#### Scenario: A page that comes back full is followed by one more request
- A page arrives with exactly as many changes as were asked for
- The cycle asks once more from just after the last change it read
- An empty answer ends the cycle

#### Scenario: The total received counts every page
- The cycle read three pages
- The reported number of changes received is the sum of the three

---

### Requirement: REQ-OS-016

A cycle SHALL stop rather than ask again for a page it has already read. A feed that
answers with nothing past the position asked from SHALL end the cycle, and the cycle
SHALL still report what it read.

The position stored SHALL be the last change that was dealt with, so a cycle that stops
early leaves the rest of the feed for the next one and nothing is lost.

#### Scenario: A feed that will not advance ends the cycle
- The server answers with changes at or before the position asked from
- The cycle stops instead of asking again for the same page
- What it did read is still reported, and the cycle is not reported as a failure

#### Scenario: A cycle that stopped early stores the last change it dealt with
- The cycle stopped part way through what it had read
- The stored position is the last change that was dealt with
- Everything after it is read again next cycle, and nothing is skipped
