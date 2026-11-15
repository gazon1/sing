# Link previews — Observable Behavior

**capability:** `link-preview` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-LINKPREVIEW-001

A task detail **SHALL** show a compact preview of the web address its title names,
and the title **MUST** remain the plain text the user wrote.

**Rationale:** the title is the task, and the preview is a description of something
the title points at. Rewriting the title to carry the preview would make the task
depend on a page that may not answer, and would lose the address the user chose.
When the title holds several addresses, the first web address is the one previewed;
the others stay in the title untouched.

**Test coverage:** `PreviewUrlsTest` (which address, and what it is cached under),
`TaskLinkPreviewSlotTest` (an address drives the card, an internal link does not).

#### Scenario: A title with a public link shows a card

- **Given** a task whose title is `Read https://example.com/a`
- **When** the task detail opens
- **Then** a card appears under the title with the page's title and description
- **And** the title still reads `Read https://example.com/a`

#### Scenario: A title with no address shows nothing

- **Given** a task whose title is `Buy milk`
- **Then** no preview card is drawn

#### Scenario: An internal link is not previewed

- **Given** a task whose title is `See task://abc`
- **Then** no preview card is drawn, and no request is made

---

### Requirement: REQ-LINKPREVIEW-002

A preview **MUST NOT** reach a non-public address, and **MUST** judge every redirect
target as well as the address the title names.

**Rationale:** the address is untrusted input. A page that answers with a redirect
to a loopback address is the attack this rule exists for, and judging only the typed
address lets it through.

**Test coverage:** `PublicAddressesTest` (every spelling of a private address),
`PageFetcherTest` (a redirect into the local network is refused and the target is
not requested at all).

#### Scenario: A redirect into the local network is refused

- **Given** a public page that answers with a redirect to `http://127.0.0.1/`
- **When** the preview is fetched
- **Then** the redirect target is not requested
- **And** the preview is unavailable

#### Scenario: A name that resolves to a private address is refused

- **Given** a host name that resolves to `10.0.0.1`
- **When** the preview is fetched
- **Then** no request is made, and the preview is unavailable

---

### Requirement: REQ-LINKPREVIEW-003

Preview results **MUST** be cached by the normalised address, and opening the same
task again **MUST NOT** fetch the page again while the entry is recent. Two requests
for the same address **MUST** share one fetch.

**Rationale:** the card is decoration. Paying a network request every time a task is
opened would make decoration cost more than the task, and two surfaces showing the
same task would pay it twice for one answer.

**Test coverage:** `LinkPreviewRepositoryTest` (a second refresh within the window costs
no request; two concurrent callers cost one), `LinkPreviewCacheTest` (the key is
normalised; the bound evicts the oldest).

#### Scenario: The same task opened twice fetches once

- **Given** a task whose title names a public page
- **When** the detail is opened, closed and opened again within six hours
- **Then** the page is fetched once, and the card shows the cached preview

#### Scenario: Two surfaces ask for the same address at once

- **Given** a task is open while the same address is being previewed elsewhere
- **When** both ask for the preview before either has answered
- **Then** the page is fetched once, and both receive that answer

---

### Requirement: REQ-LINKPREVIEW-004

A failed fetch **MUST NOT** prevent the title's address from being usable, and
**MUST NOT** show a broken or empty card. A picture that no longer matches the entry
**MUST NOT** be shown.

**Rationale:** a refresh may name a different picture than the previous one. A file
left over from the old entry beside the new title is a card describing two pages.

**Test coverage:** `LinkPreviewRepositoryTest` (a failed fetch is unavailable; a failed
refresh keeps the previous preview), `LinkPreviewCacheTest` (a changed image address
drops the stored file; an unchanged one keeps it), `TaskLinkPreviewSlotTest`.

#### Scenario: A page that will not load

- **Given** a task whose title names a page that cannot be reached
- **When** the detail opens
- **Then** no card is drawn, and the address in the title is still tappable

#### Scenario: An evicted entry takes its image with it

- **Given** the cache is at its bound and the oldest entry has a picture
- **When** another entry is stored
- **Then** the oldest entry and its picture are both gone, and no orphan file is shown

#### Scenario: The page names a different picture on refresh

- **Given** a preview whose page named one picture
- **When** a refresh names another
- **Then** the first picture is no longer shown or stored

---

### Requirement: REQ-LINKPREVIEW-005

The user **MUST** be able to dismiss a preview without changing the title, and the
dismissal **MUST** apply to the address rather than to the task.

**Rationale:** the card is a property of "a task whose title names this link", so the
same link in another task is what the user already dismissed. Writing the dismissal
to the task would be a second source of truth about a page nobody edited.

#### Scenario: A dismissed preview stays dismissed for the same address

- **Given** a card is showing for `https://example.com/a`
- **When** the person dismisses it
- **Then** the card is gone, the title still reads the same, and
- **When** another task names the same address
- **Then** no card is drawn there either

---

### Requirement: REQ-LINKPREVIEW-006

With link previews turned off, the app **SHALL NOT** make any request for a preview,
and an answer that arrives after the switch is turned off **MUST NOT** be shown.

**Test coverage:** `LinkPreviewRepositoryTest` (the toggle is read before the
request; the source is never asked).

#### Scenario: The switch is turned off while a page is loading

- **Given** a preview is being fetched
- **When** the person turns link previews off before it answers
- **Then** no card appears, and the answer is not shown when it lands

#### Scenario: The toggle is off

- **Given** link previews are turned off in the settings
- **And** a task's title names a public page
- **When** the task detail opens
- **Then** no card is drawn, and no request is made

---

### Requirement: REQ-LINKPREVIEW-007

The preview **MUST NOT** send page content or the address to an AI provider.

**Rationale:** #2953 makes AI summarisation opt-in and outside this change. The
preview type has no field for an inferred summary, so there is nothing to send.

#### Scenario: A preview is built without a model call

- **Given** a task whose title names a page with Open Graph metadata
- **When** the preview is produced
- **Then** the card shows only what the page said about itself, and no model is
  called
