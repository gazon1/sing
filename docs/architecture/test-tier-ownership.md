# Test tier ownership

Which tier owns each scenario that currently lives in two or three tiers.

| Scenario | desktop jvmTest | androidHostTest | androidTest | Maestro |
|---|---|---|---|---|
| MCP wired toolbox == declared toolbox | owner | owner | - | - |
| archive golden (crypto, chain, key) | owner | owner | - | - |
| record retention == policy | owner | owner | - | - |
| notifier permission matrix | owner | owner | - | - |
| secure store recovery | owner | - | owner | - |
| create a task from the FAB | owner (CreateTaskFlowTest) | - | owner (CreateTaskFlowTest) | - |
| shell / navigation reachability | owner (NavigationFlowTest) | - | owner (NavigationSmokeTest) | owner (smoke) |

Rule: one owner per scenario; the other tiers drop their copy. Fold candidates: archive golden first (one parameterized test over both implementations).