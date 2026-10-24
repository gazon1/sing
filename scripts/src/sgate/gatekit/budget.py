"""sgate.gatekit.budget — the budget the project instructions have to fit in.

Why this exists
---------------
The harness reads `AGENTS.md` into the agent's context and truncates it to fit
the project-instruction budget — 32768 bytes of UTF-8. The truncation is
silent about the part that did not fit: the agent reads a file that ends in the
middle of a sentence, and it does not know that the rules it did not read were
there.

That is not a hypothetical. On 2026-10-24 a checkout carried a foreign
`AGENTS.md` of 45165 bytes — 138 % of the budget — and the session that ran in
it received its project instructions truncated, mid-sentence, with the
difference between "the rule is not there" and "the rule did not fit" invisible
to the agent reading it.

The number is written down **once**, here. A budget quoted in a docstring is a
claim nobody checks; the record is the claim.

So the budget is a number the repository can hold itself to: `AGENTS.md` has to
fit in it whole, and a gate says so before the harness has to.

One record
----------
The number lives here, and both the gate (`scripts/check-agents-size.py`) and
the command that reports a checkout's state (`sgate checkout-state`) read it
from here. A budget written twice is a budget that drifts.
"""

from __future__ import annotations

#: The project-instruction budget, in bytes of UTF-8.
#:
#: This is the budget the local runtime gives the project instructions: the
#: file is truncated to fit it. The file has to fit whole — a truncated
#: instruction file is not an instruction file, and the part that did not fit
#: is exactly the part nobody notices is missing.
PROJECT_INSTRUCTIONS_BUDGET = 32768

#: The file the budget is about.
PROJECT_INSTRUCTIONS_FILE = "AGENTS.md"
