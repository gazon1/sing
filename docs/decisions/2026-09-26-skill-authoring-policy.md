---
title: Skill Authoring Policy
date: 2026-09-26
status: accepted
---

# Skill Authoring Policy

## Context

The project has accumulated skills of varying quality: some are concise decision trees, others are verbose tutorials. The `check-skill-frontmatter.sh` script enforces minimum frontmatter but does not govern content quality or the decision to create a skill versus an ADR.

## Decision

### Skill vs ADR decision tree

```
Does this knowledge have executable content?
  (template, checklist, decision tree, code generator, workflow script)
  YES → Create a SKILL
  NO  ↓
Is this a one-time architectural decision with a clear winner?
  YES → Create an ADR
  NO  ↓
Is this a process/policy that applies to multiple future situations?
  YES → Create an ADR (process ADR)
  NO  → Write in prose docs (ARCHITECTURE.md, AGENTS.md)
```

### Skill frontmatter requirements

```yaml
---
name: skill-slug          # kebab-case, unique, used for /<skill-name> invocation
description: One line describing when to use this skill. 10-15 words.
# status: draft           # omit when published (defaults to published)
---
```

**Required fields:** `name` + `description`
**Forbidden fields in published skills:** `status`, `date` (these are for ADRs only)

### Skill size limits

| Category | Max size | Rationale |
|---|---|---|
| Decision tree / checklist | 8 KB | Scannable in one read |
| Template / generator | 12 KB | Includes inline examples |
| Tutorial / guide | 15 KB | Longer but must have TOC |
| **Absolute max** | 20 KB | Hard cap; split if exceeded |

### Skill structure (required sections)

```
# Skill Name

## When to use
One paragraph. Trigger conditions under which an agent should invoke this skill.

## Prerequisites
Bullets: required knowledge, skills, or tools.

## Step-by-step
Numbered steps. Each step is one atomic action.

## Decision tree (optional)
If the skill branches based on conditions, include an ASCII/text decision tree.

## Examples (optional)
One or two concrete examples with file paths.

## Common pitfalls
Bullets of frequent mistakes.
```

### Skill naming conventions

- **Slug:** kebab-case, e.g. `vm-migration-playbook`, `feature-scaffold`
- **Display name:** title-case in frontmatter `name`, e.g. `vm-migration-playbook`
- **Invocation:** `/<slug>` from agent chat, e.g. `/vm-migration-playbook`

### Deprecation

A skill is deprecated by adding:
```yaml
status: deprecated
Alternative: skill-name-that-replaces-this  # slug of replacement, if any
```

The skill file is kept (not deleted) so old links continue to work.

## Consequences

- `check-skill-frontmatter.sh` validates all skills in `.agents/skills/`
- Skills exceeding 20 KB fail CI (hard cap enforced by `wc -c` check in CI)
- Skills without `name` + `description` fail CI
- `just docs-audit` checks skill size limits
- Skills are referenced in ADRs by slug: `skill \`singularity-todo-vm-migration-playbook\``
