---
name: documentation-update-concept
description: Update an existing knowledge concept after source or body changes.
---

# Update a knowledge concept

1. Run `status <concept>` first.
2. Edit body or sources as needed.
3. If sources changed, ensure they are committed (stamp forbids dirty sources).
4. Run `stamp <concept>` (generated), review diff, then `stamp --verified --by human:<name>`.
5. Run `verify`.
