---
name: documentation-verify-concept
description: Verify a knowledge concept's currency before relying on it.
---

# Verify a knowledge concept

1. Run `python3 scripts/documentation/check.py status <concept>`.
2. If `verified` or `generated` — safe to use with caveats.
3. If `stale`/`body_stale`/`sources_stale`/`unstamped` — re-check affected sources before using.
4. Never treat stale knowledge as verified fact.
