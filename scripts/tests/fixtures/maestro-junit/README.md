# The same failure, said by two engines

The Maestro suite can be run by the Maestro CLI or by maestro-runner, and the
two describe the same failure in different words. The cause registry
(`config/docs/maestro-debt.yaml`) is keyed on the CLI's words, so a runner
report read as it stands says every cause is new — the debt did not change
kind, only the sentence that describes it.

These fixtures are that claim, in files: the same logical failure, written by
each engine, side by side.

| pair | the CLI says | maestro-runner says |
|---|---|---|
| `assert-visible` | `Assertion is false: id: task_item_buy_milk is visible` | `Element '#task_item_buy_milk' not visible within 8s (cause: timed out)` |
| `assert-not-visible` | `Assertion is false: id: task_item_buy_milk is not visible` | `Element '#task_item_buy_milk' still visible after 5s (cause: timed out)` |
| `tap-missing` | `Element not found: Id matching regex: auth_email_input` | `Element not found: handler_error: Element not found in any of 2 strategies` |
| `scroll-missing` | `No visible element found: id: task_item_r9181965460_buy_milk` | `Element not found: scrolling down made no progress after 2 scrolls (end of content?)` |

The last two are the pair the runner cannot say on its own: its message names
no element at all. The element is in its report tree, under
`runner-tree/<pair>/<attempt>/flows/flow-000.json`, which is where the
command that failed and the selector it asked for are recorded.

The `scroll-missing` pair also carries the run id: the flow asks for
`task_item_${RUN_ID}_buy_milk` and the CLI says the id the run resolved it
to. The runner's tree keeps the placeholder, so the normalisation puts the
run's own id back — the number the CLI printed is the number the run used.
