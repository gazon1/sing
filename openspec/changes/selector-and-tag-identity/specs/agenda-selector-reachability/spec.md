# agenda-selector-reachability

## ADDED Requirements

### Requirement: REQ-1 A selector the engine supports is either reachable from the editor or documented as not being

Every `Selector` variant the engine can evaluate SHALL either have a
`SelectorTemplate` that makes it constructible from the agenda editor, or SHALL
be declared engine/preset-only in the editor's own copy and in the template
registry.

A variant with neither is a gap the user cannot see: the engine accepts it, a
preset may use it, and the editor offers nothing.

The declaration SHALL live with the template registry rather than in prose
elsewhere, so that adding a variant without a template and without the
declaration is a compile error or a check failure.

**Rationale:** `SelectorTemplate.kt` ships `Fixed`, `ByTags`, `ByProjects`,
`ByPriority` and `ByStatus`. `Selector.Regexp` and `Selector.DateRange` have no
template, so `selectorOptionsFor` returns an empty list and the editor cannot
offer them — while `AgendaPresets.kt` and `SelectorSerializer.kt` both reference
them. The original backlog entry recorded all five as reachable; three are.

#### Scenario: A user wants a regex-filtered section

- **Given** `Selector.Regexp` is declared engine/preset-only
- **When** the user opens the section editor
- **Then** the editor says the type exists but is not configurable there
- **And** it names the preset or the manual route that can produce one

#### Scenario: A new Selector variant is added

- **Given** a new variant on the `Selector` type
- **When** the template registry is compiled
- **Then** it must either gain a template or an engine/preset-only declaration
- **And** the editor's coverage is not left implicit

#### Scenario: A template is added for a new variant

- **Given** a template whose parameters are not a multi-select
- **When** the configurator opens it
- **Then** it uses a control matching the parameter shape — a text field for a
      pattern, a from/to pair for a date range
- **And** it does not open a multi-select over values that do not exist
