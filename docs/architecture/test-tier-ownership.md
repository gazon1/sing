# Test tier ownership

Which tier owns a scenario that is asserted in more than one, and why.

**The rule: one owner per scenario.** The other tiers drop their copy. A scenario that
has to be asserted on two platforms is not two copies of one scenario — it is one
rule with two platform bindings, and the rule is asserted once. The archive-golden row
below is that case, and it is the only sanctioned exception.

A copy is only ever kept when its row says why it is kept. "The device tier is the
only one that observes the real thing" is such a reason; "it was written first" is
not.

## The table

| Scenario | Owning tier | Why that tier owns it | The other tier's copy | Tiers it is asserted in now |
|---|---|---|---|---|
| MCP wired toolbox == declared toolbox | desktop JVM | the catalogue is one list, and the desktop graph is the one the shipped desktop app resolves; the check compares the two by resolving the real Koin list | **kept deliberately** — the DI list is hand-maintained per platform (ADR `2026-10-21-the-toolbox-surface-from-the-compiler-down`), because the Koin compiler plugin cannot check a heterogeneous `Tool<*, *>` list and the check is paid in the test suite instead. A tool wired on one platform and forgotten on the other is invisible to a check that reads one list: the Android check is what caught the missing project tools. One rule (the catalogue), two bindings (the two DI lists), each tier asserting its own. | desktop JVM + android host |
| archive golden (crypto, chain, key) | the common rule (`ArchiveCryptoGolden`) — and both tiers execute it, which is the exception the rule above allows for | one rule in common code, two platform bindings, two tiers that run it: the fold has already happened, and what the tier names is which binding it exercises | kept — the sanctioned exception the rule above allows for | desktop JVM + android host |
| record retention == policy | desktop JVM | the window is one common constant (`ToolCallRecordPolicy.RETENTION_DAYS`) that both writers delete on, and the policy is one file; the JVM tier asserts the constant against the file, which covers the JVM writer and the Android one | **removed** — the Android copy asserted the same constant in a second tier | desktop JVM |
| notifier permission matrix | desktop JVM | the matrix is a rule, not a measurement: which cells hold is the same on every device, so the rule lives in common code (`NotificationSupport`) and the JVM tier asserts every cell | **removed** — the Android class keeps only what is about the port: that the grant is read per call, and that a denied permission enqueues nothing | desktop JVM (+ android host, for the port) |
| secure store recovery | desktop JVM | the decision — erase and reopen — is pure, and the JVM tier asserts all three of its outcomes | **kept deliberately** — the device class is not the recovery policy: it is the real `EncryptedSharedPreferences`/Keystore IO, which no fake can stand in for. The recovery in a fresh process is covered by `Maestro/flows/auth/12-unreadable-store-recovers.yaml`, because the keystore caches its keyset per process. | desktop JVM + device |
| create a task from the FAB | desktop JVM | the desktop flow test drives the FAB and the editor that opens from it, in seconds | **removed** — the device copy asserted the same scenario; the instrumented floor is updated below and the loss acknowledged. The device tier keeps the FAB's *visibility* contract in `NavigationSmokeTest` — which tab shows it — which is a different assertion about a different thing. | desktop JVM |
| shell / navigation reachability | desktop JVM | the JVM tier renders the *desktop* shell, and the reachability of that shell is what it asserts | **kept deliberately** — only a device can observe the *Android* shell (`AndroidShellNav3`), which is a different shell with a different set of destinations; the device copy is that observation, not a second copy of the desktop one. The Maestro smoke flow stays as the device-level entry check. | desktop JVM + device + Maestro |

## What was removed, and what the floors say

Three copies went, each because the scenario had an owner that could assert it:

- the Android host's **record-retention** check, now that both writers share one
  constant, and
- the **notifier matrix** out of the Android host class, now that the rule is common
  code, and
- the device tier's **FAB** flow test, whose scenario the desktop flow test owns.

The instrumented tier lost one class and three tests: `config/docs/instrumented-test-floor.txt`
records 7 classes and 23 tests, with the loss acknowledged in that file's notes
rather than regenerated silently. The android host tier lost four tests, and its six
classes deliver 13 again — the number `config/docs/test-runs-baseline.txt` records.

## The four features that had no end-to-end flow

| Feature | Flow coverage now | Why |
|---|---|---|
| AI chat | a flow exists — `Maestro/flows/menu/07-ai-chat-entry.yaml` — and its device verification is tracked by #1902 | the entry from the menu is the part a user touches; the chat screen itself is covered by the desktop compose tests |
| Proposals | a flow exists — `Maestro/flows/menu/08-proposals-entry.yaml` — and its device verification is tracked by #1903 | as above |
| WhatsNew | a flow exists — `Maestro/flows/whatsnew/01-show-and-dismiss.yaml`, `02-dismissed-payload-stays-hidden.yaml` and `03-new-payload-shows-again.yaml`; their device run is not recorded in the headers yet | the payload arrives through remote config, which the E2E build has no server for. The debug seed link (`todo-debug://seed?whatsnew=…`) now delivers one, so the sheet, its dismissal and the rule that a dismissed payload does not reappear are all reachable from a flow |
| Alarms | no flow, deliberately | the alarms feature has no user-facing surface — a reminder is scheduled by a task and what the user sees is a notification. The unit tier covers the reminder pipeline (`AlarmHandlerTest`, `ReminderFireLogicTest`, `ReminderDeliveryTest` under `shared/src/commonTest`), and `AlarmContract` — the tag format, the intent extras, the phase strings — is the shape that pipeline hands between its parts; no screen exists for a flow to drive |

## Where to look next

- `docs/architecture/tests.md` — where a test lives, and the rules that decide it.
- `docs/testing/what-each-tier-proves.md` — what each tier can and cannot see.
- `config/docs/test-runs-baseline.txt` and `config/docs/instrumented-test-floor.txt` —
  the floors that hold the tiers to what they used to deliver.
