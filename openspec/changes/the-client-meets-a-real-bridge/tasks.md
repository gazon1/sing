# The client meets a real bridge — tasks

## 1. The probe

- [x] 1.1 Spawn the bridge and drive the application's own client against it
- [x] 1.2 Copy the bridge's frames without a second reader of its standard output
- [x] 1.3 Report the verdict, the agent's name and version, the methods it used and the frames
- [x] 1.4 A Gradle task that runs it, beside `exportToolSurface`
- [x] 1.5 A script that runs the task and turns the verdict into an exit code

## 2. What the bridge sent that the fake did not

- [x] 2.1 The authentication status notification, right after the handshake
- [x] 2.2 The available-commands notification, right after the session is open
- [x] 2.3 The five permission modes and the three configuration options in the session answer
- [x] 2.4 The fake sends them, so the suite covers what the bridge sends
- [x] 2.5 A test that says the client survives a notification it has no answer for

## 3. The records

- [x] 3.1 The decision: `docs/decisions/2026-10-24-the-client-meets-a-real-bridge.md`
- [x] 3.2 The architecture document says how to run the probe
- [x] 3.3 The issue record: `.specify/issues/the-client-meets-a-real-bridge.yaml`

## 4. What was not exercised, and says so

- [x] 4.1 The prompt half needs the agent behind the bridge to be signed in
- [x] 4.2 The tool-call half needs the same credentials, a tool server and a profile
- [x] 4.3 The proposal and the design say so in words, not in a footnote
