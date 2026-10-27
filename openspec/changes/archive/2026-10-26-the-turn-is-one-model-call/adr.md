# ADR Review Manifest — the chat's turn is one model call

The sequence numbers below are the ordinal of the ADR inside this change; the
corpus in `docs/decisions/` is not numbered.

## ADRs Created

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| 1 | the-turn-is-one-model-call | The chat's turn is one model call, and the screen is a format of it | accepted | The turn asks the model once, through the port that owns the tools; the screen is what the answer may contain; the transport seam that made a second call possible is deleted |

## ADRs Reviewed (No Action)

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| — | genui-server-driven-ui | The screen is drawn by the server | accepted | Reviewed: its parser, catalogue and renderer are kept unchanged, and only its transport seam is superseded by the ADR above. The prior file is not edited. |
| — | genui-subsystem-applied-r23 | The screen's own error handling | accepted | Reviewed: the screen's rejections, their severities and the feedback the model receives move with the screen and keep their rules |
| — | genui-surface-belongs-to-one-answer | A screen belongs to the answer that drew it | accepted | Reviewed: the identifier is still chosen by the caller, per answer, and the renderer is still addressed by it |
| — | acp-client-and-agent-activity | External agent in the app (ACP client) and agent activity visibility | accepted | Reviewed: the activity vocabulary this change finally produces is the one that decision defines, and the built-in agent's missing plan producer is left as a recorded gap rather than quietly closed |

## Decisions That Did Not Meet the Bar

- **A plan producer for the built-in agent.** `PlanUpdated` has no built-in producer, so
  the plan card is unreachable for the built-in agent. Giving the built-in agent a plan
  is a product decision about what the built-in agent knows, and it belongs to the
  feature's task list (T074), not to a change whose subject is the turn's shape.
- **A rewrite of the screen's machinery.** The parser, the catalogue, the processor and
  the renderer are not what is wrong. Touching them would have made the change bigger
  than the defect it fixes.
- **One prompt for every call.** The instructions for a call are a parameter of that
  call, not a global setting: a turn that is not about drawing anything must not be
  handed a catalogue it has no use for.
