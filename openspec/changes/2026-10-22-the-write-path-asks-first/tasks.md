## 1. The tool

- [x] A new tool, `write_task_proposal`, proposes a change and applies nothing
- [x] It answers with the proposal it made, and the line the user will read
- [x] It says "proposed", not "changed"
- [x] The reason is part of the line the user reads

## 2. The vocabulary

- [x] The operation names live in the domain, not in the tool
      (`ProposalOperation`)
- [x] Every operation maps to exactly one kind of proposal
- [x] The tool's description lists the operations it accepts

## 3. The refusals

- [x] An operation that is not one of ours is refused, and the answer says which
      operations exist
- [x] A missing value is refused
- [x] A missing item is refused
- [x] A task that does not exist is refused

## 4. The proposal is the app's proposal

- [x] The proposal is stored in the proposal repository, as a pending proposal with the
      agent as its source
- [x] The proposal carries a fingerprint, so a change the user rejected is not
      proposed again
- [x] The item's human summary is the line the confirmation screen shows

## 5. The copy-paste

- [x] The four delete tools build their proposals through `ProposalBuilder`
- [x] The delete proposals carry a fingerprint, which they did not before
- [x] `ProposalBuilder` has a general form for the targets that are neither a task
      nor a note

## 6. The proposals of a note are visible too

- [x] The proposal repository can watch the proposals of a note
      (`watchProposalsForNote`)
- [x] The note editor's screen shows the changes waiting for a word
- [x] The card is the proposals' own — the same card the task detail shows
- [x] The dead `applyProposal` dependency is gone from the note editor; the
      application goes through the proposals' own use case

## 7. The wiring

- [x] The tool is in the app's tool list
- [x] The tool is in the server's catalog
- [x] The contract version is unchanged — a new tool is not a changed field
