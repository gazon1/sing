# Stage 5 evidence

Protocol: `--no-build-cache` on every run; a settle build, then one file edited, then only
`:shared:compileKotlinJvm` with `-Pkotlin.build.report.output=file`; two runs per scenario.
See `../stage5-benchmark.md` for the interpretation.

| Scenario | Wall (r1 / r2, median) | Room KSP | `:shared` compile | Kotlin IC |
|---|---|---|---|---|
| `ui` (`TaskCreateScreen.kt`) | 85 / 72 s, 78.5 s | `UP-TO-DATE` | executed | `KT-62686` |
| `vm` (`TaskCreateViewModel.kt`) | 71 / 61 s, 66 s | `UP-TO-DATE` | executed | `KT-62686` |
| `repo` (`TaskRepositoryImpl.kt`) | 62 / 68 s, 65 s | `UP-TO-DATE` | executed | `KT-62686` |
| `entity` (`Entities.kt`) | 104 / 41 s | executed | r1 executed, r2 `UP-TO-DATE` | `KT-62686` in r1 |

The committed evidence is:

- `task-outcomes.txt` — per-run wall time, Gradle task outcomes, `REBUILD_REASON` and
  `Classpath changes`.
- `summary-<scenario>-r<n>.txt` — the extracted `REBUILD_REASON`, Kotlin task time,
  `Source changes` and `Classpath changes` from the Build Report.

The per-run Gradle logs (`<scenario>-r<n>.log`) are kept locally (the repository ignores
`*.log`), as are the full Kotlin Build Reports (`<scenario>-r<n>.txt`, several megabytes each)
in `full/`, which is gitignored. Both are reproducible from the protocol above.
`entity-r2` has no full report because `:shared:compileKotlinJvm` was `UP-TO-DATE`.
