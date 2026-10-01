---
title: "Caller analysis — «subject»"
date: 2026-10-01
tags: [architecture, caller-analysis]
status: template
---

# Caller analysis — «subject»

## Item under evaluation

«What is being evaluated for extraction/split/refactor.»

---

## 1. Caller enumeration

List every call site (file:line) that uses this item.

| Caller | File | Line | Usage |
|--------|------|------|-------|
| «Caller name» | «file.kt» | N | «how it's used» |

---

## 2. Capability set per caller

For each caller, what capabilities does the item provide?

| Caller | Capabilities used |
|--------|-------------------|
| «Caller» | «list of capabilities» |

Identify **overlap** between callers — do they use the same subset?

---

## 3. Interface Segregation Principle (ISP) analysis

Can callers be split into groups with non-overlapping needs?

**Group A** (uses «capability X»):
- «Caller 1»
- «Caller 2»

**Group B** (uses «capability Y»):
- «Caller 3»

If callers fall into **≥3 non-overlapping groups** → strong case for ISP split.

If callers share **≥80% of capabilities** → strong case for keeping unified.

---

## 4. Duplication ratio

```
Total callers:        N
Unique call patterns: M
Ratio (M/N):          X%
```

High ratio (>70%) suggests abstraction is justified.
Low ratio (<30%) suggests mechanical duplication, not abstraction opportunity.

---

## 5. Effort estimate

| Aspect | Estimate |
|--------|----------|
| Extract interface(s) | «N» lines |
| Update call sites | «N» lines |
| Write tests | «N» hours |
| Migration risk | Low / Med / High |

---

## 6. Decision

| Option | Rationale |
|--------|----------|
| **Split** — extract separate interfaces | «when ≥3 non-overlapping groups» |
| **Keep unified** — not enough variance | «when callers share most capabilities» |
| **Defer** — wait for more callers | «when borderline and not blocking» |

### Decision

«Pick one. Include reasoning tied to the data above.»

### Rationale

«Why this is the right call given the ISP analysis and duplication ratio.»

---

## Related

- `docs/decisions/templates/caller-analysis.md` (this template)
- ADR «YYYY-MM-DD-<slug>.md» for the item being evaluated
