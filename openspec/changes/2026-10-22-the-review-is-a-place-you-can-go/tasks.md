## 1. The screen

- [x] A ViewModel that observes the pending proposals
      (`feature/proposals/presentation/ReviewViewModel.kt`)
- [x] A screen that lists what is waiting, with "Apply" and "No" on each change
      (`feature/proposals/presentation/ReviewScreen.kt`)
- [x] Applying goes through the app's own use case — the one that validates before it
      claims and compares-and-sets
- [x] Turning a change down goes through the same use case's reject path
- [x] The screen says so when there is nothing to review

## 2. The entry point

- [x] A `Review` destination in the app's navigation
- [x] The destination has an icon and a title
- [x] The destination is in the menu, on both platforms
- [x] The destination is classified in `ScreenFamily` — the exhaustive `when` is the
      thing that makes a forgotten classification a compile error
- [x] The entry is in the shared entry provider, so Android and the desktop get it from
      one place

## 3. The first step

- [x] A `first_step` tool that names one physical action
- [x] The answer carries the checkable outcome that ends the step
- [x] The answer carries one sentence of rationale
- [x] The prompt states the rule: one physical action, not a thought
- [x] A model that answers in prose still produces an answer

## 4. The wiring

- [x] The tool is in the app's tool list
- [x] The tool is in the server's catalog
- [x] The Review ViewModel is in the proposals module

## 5. The prompts and the slot

- [x] The chat prompt names the four situations the app is built around — inbox
      organization, daily planning, backlog cleanup, system health check
- [x] The chat prompt names the three things to keep in mind — thinking fuel, burden
      against size, and the review as a procedure
- [x] The planning prompt asks for a plan that fits, with room in it
- [x] The project review prompt asks what is stuck and one action that would move it
- [x] `first_step` is in the task's slot API: an action of its own, a use case of its
      own, and a proposal of its own

## 6. Not in this change

- [ ] The review prompts as a screen of their own — the chat carries them for now
