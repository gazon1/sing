# Maestro → Allure golden fixtures

Taken from the nightly full Maestro run
[42787254172](https://github.com/gazon1/sing/actions/runs/42787254172) (main,
2026-11-10, head `41c66556`), artifacts `maestro-junit-full-1-*` and
`maestro-junit-full-2-*` — a subset of two shards: passing flows, failing flows,
and the evidence of one failing flow per shard.

Sanitised: device serial (`emulator-0000`), APK build id (`deadbeef`), logcat
truncated to the last 60 lines with credential-shaped lines removed, screenshots
downscaled to 160 px wide. Failure text and tags are unchanged — they are what
the mapping contract is about.

`run-summary.json` is trimmed to the flows present here, so the fixture is
self-consistent; missing-flow handling is covered by a unit test, not by this
tree.

## What the artifacts actually look like (measured, not assumed)

- `<testcase file>` is already **repo-relative** (`Maestro/flows/attachments/…`).
- `id`/`name`/`classname` are the flow's `name:` field (`attachments-add-url`),
  **not** the file stem — so they cannot identify a testcase; `file` is the key.
- `status` is `SUCCESS` or `ERROR`. Every non-`SUCCESS` testcase carries a
  `<failure>` child. Across 123 testcases of the four shards there is **no**
  attribute-only outcome, so no attribute fallback is needed.
- `<property name="tags" value="smoke, regression, notes"/>` carries the flow's
  tags, comma-separated; `scenario:<ID>` appears there too.
- Both `<testsuite>` and `<testcase>` carry `timestamp`, but the shared reader
  (`junit_xml.TestCaseResult`) does not expose it, and the adapter does not
  parse the XML a second time: Allure `start`/`stop` come from the report's
  mtime and the testcase's `time`. Extending the reader once is the follow-up if
  wall-clock precision is ever needed.
- `environment.json` here is the **old** schema (`kind, api, device, serial,
  apk`): these artifacts predate `f114ee3f7` ("the environment metadata has one
  name per meaning"). The current runner writes `apk_commit_sha, commit, shard,
  shards`. Identity for a run therefore comes from the workflow context, not
  from this file.
- No `evidence/` index is present in these artifacts, so the adapter must not
  depend on it: the flow-stem directory is the evidence rule.
