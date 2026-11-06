---
name: documentation-create-concept
description: Create a new knowledge concept with sources and snapshots.
---

# Create a knowledge concept

1. Pick a path under `knowledge/` (architecture/domain/modules/engineering).
2. Define `canonical_for` IDs and add them to `knowledge/facts.yaml`.
3. List explicit `sources` (only these feed the digest).
4. Write the body.
5. Run `python3 scripts/documentation/check.py stamp <concept>` then `stamp ... --verified --by human:<name>`.
6. Run `verify`. Do not edit state fields by hand.
