# Documentation system

Canonical locations: Product SDD → `specs/` (Spec Kit), Engineering SDD → `openspec/changes/`, current architecture → `knowledge/`, decision rationale → `docs/decisions/`, human instructions → `docs/`.

## Knowledge concepts

A concept is a Markdown file under `knowledge/` with OKF frontmatter:

- `type`, `title`, `description`
- `canonical_for`: list of fact IDs from `knowledge/facts.yaml`
- `sources`: list of `{id, resource: repo:/path}` — only these files feed the digest
- `generated`, `verified`: snapshot lists (never edit by hand)

## Commands

```
python3 scripts/documentation/check.py status <concept>
python3 scripts/documentation/check.py show <concept>
python3 scripts/documentation/check.py affected <path...> [--git-diff REV]
python3 scripts/documentation/check.py stamp <concept>
python3 scripts/documentation/check.py stamp <concept> --verified --by human:<name>
python3 scripts/documentation/check.py verify
```

## Rules

- `stamp` is forbidden on dirty sources.
- `stamp --verified` requires `--by human:...` and an explicit confirmation.
- Agents must run `status`/`show` before relying on a concept; stale concepts are not verified facts.
- Do not edit `generated`/`verified` state fields by hand.

## OpenSpec integration

Engineering changes must include `concept_impact` (status: created|updated|not_required) before archive. See `.specify/templates/engineering/spec-template.md`.