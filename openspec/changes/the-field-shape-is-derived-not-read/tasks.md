# Tasks — the-field-shape-is-derived-not-read

**status:** accepted

---

## Phase 1 — The helpers get their own package

- [x] `TextFieldSource`, `TextFieldStateBridge`, `TextFieldShapes`, `TextFieldEdits`
      and `TextFieldTransformations` move to `com.singularity.todo.core.ui.textfields`
- [x] The package holds no field sites, so the helpers are excluded from the scan by
      their path
- [x] Every call site imports the helper it uses — the same package is no longer
      reachable
- [x] The tests move with the code

## Phase 2 — The shape is derived

- [x] `TextFieldShapeScan` reads every file's imports
- [x] An import of the bridged helper means *bridged*, of the anchored helper means
      *anchored*, of Compose's `rememberTextFieldState` means *local*
- [x] The derivation lives in one place and both contract tests use it

## Phase 3 — The shape convention test stops reading text

- [x] The scan no longer reads the source of a file
- [x] The registry is compared with the *derived* shape
- [x] The inventory table in the skill is still compared with the registry

## Phase 4 — The rewrite contract's scans are structural

- [x] The bridged sites are the files that import the bridged helper
- [x] The owner's source is a property with a type
- [x] The counter is a property with a name
- [x] The bridge's public parameters are names, and none of them is a count
- [x] The counter that moves, and the site that constructs a source, are read from the
      declarations of the file with the comments stripped
- [x] A preview is excluded by its annotation, not by the word

## Phase 5 — The documentation follows

- [x] The skill names the helpers' package and says where the shape comes from
- [x] The skill's paths point at the new package
- [x] The decision is recorded
