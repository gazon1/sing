"""sgate.models — typed JSON payloads for gate results.

Every parse-then-report gate shim returns a :class:`GatePayload` (usually a
per-gate subclass) instead of a hand-assembled dict.  JSON emission goes
through pydantic's ``model_dump_json``; parsing back is
``Model.model_validate_json`` — no ``json.dumps`` / ``json.loads`` in shims.

The honesty invariant lives here, once:

    a gate reports pass only if BOTH the parser says so AND the underlying
    command exited 0.

A parser derives ``passed`` from output patterns; a novel failure format can
evade every pattern, and the underlying returncode is the only signal that
cannot lie.  A gate that reports green on a failed underlying script is worse
than no gate, so :meth:`GatePayload.emit_and_exit` ANDs the two signals and
exits with the honest code in every mode.
"""
from __future__ import annotations

from typing import NoReturn

from pydantic import BaseModel, ConfigDict


class GatePayload(BaseModel):
    """Typed result payload emitted by a gate under ``--json``.

    Subclasses add per-gate counters (``dangling_count``, ``digest_lines``, …);
    ``extra="allow"`` keeps them flowing through (de)serialization.
    """

    model_config = ConfigDict(extra="allow")

    gate: str
    passed: bool
    blocking_errors: list[str] = []
    warnings: list[str] = []

    def honest_pass(self, returncode: int) -> bool:
        """True only if the parser passed AND the underlying command exited 0."""
        return self.passed and returncode == 0

    def honest_payload(self, returncode: int) -> "GatePayload":
        """This payload with ``passed`` corrected to the honest value.

        A payload that says ``passed: true`` about a command that exited
        non-zero is a green gate on a failed script. This is the one place
        that correction is made, so every emitter agrees on it.
        """
        return self.model_copy(update={"passed": self.honest_pass(returncode)})

    def emit_and_exit(self, returncode: int, *, emit_json: bool) -> NoReturn:
        """Exit honestly; under ``--json`` print the typed payload first.

        ``passed`` in the emitted JSON is corrected to the honest value, so a
        consumer reading the payload never sees "passed: true" on a gate whose
        underlying command failed.
        """
        honest = self.honest_pass(returncode)
        if emit_json:
            print(self.honest_payload(returncode).model_dump_json(indent=2))
        raise SystemExit(0 if honest else (returncode if returncode != 0 else 1))


class CapturedPayload(GatePayload):
    """Payload for shims that forward a captured command verbatim.

    The generated ``_handle_result`` pattern: no custom parser, the raw
    stdout plus the exit code ARE the result.
    """

    exit_code: int = 0
    output: str = ""
