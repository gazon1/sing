"""sgate.kiwi — Kiwi TCMS scanner wrapper.

KIWI_REQUIRED: ClassVar[bool] = False
    Default for all gate commands. Set to True on Kiwi-dependent gates.

KiwiUnavailable(msg: str, cause: BaseException | None):
    Raised when the scanner cannot be loaded.

KiwiResult(NamedTuple):
    Returned on success. Fields: scanner (KiwiClient instance), available (True).
"""
from __future__ import annotations

import sys
from typing import ClassVar, NamedTuple

# Default: Kiwi is optional for most gates
KIWI_REQUIRED: ClassVar[bool] = False


class KiwiUnavailable(Exception):
    """Raised when the Kiwi TCMS scanner cannot be loaded."""

    def __init__(self, msg: str, cause: BaseException | None = None) -> None:
        super().__init__(msg)
        self.msg = msg
        self.cause = cause


class KiwiResult(NamedTuple):
    """Result of a successful Kiwi scanner load."""

    scanner: object  # KiwiClient instance
    available: bool = True


def load_scanner() -> KiwiResult:
    """Load and return the Kiwi TCMS KiwiClient scanner.

    Returns
    -------
    KiwiResult
        With scanner (the KiwiClient instance) and available=True.

    Raises
    ------
    KiwiUnavailable
        When the module cannot be imported or the client cannot be created.
    """
    # infra/kiwi/ is at repo-root/infra/kiwi/
    # This file is at scripts/src/sgate/kiwi.py → src/sgate/ → src/ → scripts/ → repo-root
    import pathlib as _pathlib

    repo_root = _pathlib.Path(__file__).resolve().parent.parent.parent.parent
    kiwi_path = repo_root / "infra" / "kiwi"
    if str(kiwi_path) not in sys.path:
        sys.path.insert(0, str(kiwi_path))

    try:
        import importlib.util

        if str(kiwi_path) not in sys.path:
            sys.path.insert(0, str(kiwi_path))

        spec = importlib.util.find_spec("traceability")
        if spec is None or spec.loader is None:
            raise KiwiUnavailable(
                "infra/kiwi/traceability package not found",
                cause=None,
            )
        mod = importlib.util.module_from_spec(spec)
        sys.modules["traceability"] = mod
        spec.loader.exec_module(mod)  # type: ignore[union-attr]

        # traceability.kiwi_module() returns the kiwi package
        kiwi_pkg = mod.kiwi_module()

        # KiwiClient is the XML-RPC scanner
        KiwiClient = getattr(kiwi_pkg, "KiwiClient", None)
        if KiwiClient is None:
            raise KiwiUnavailable(
                "kiwi_module() has no 'KiwiClient' export",
                cause=None,
            )

        # Read defaults from the kiwi package
        default_url = getattr(kiwi_pkg, "DEFAULT_URL", None)
        default_user = getattr(kiwi_pkg, "DEFAULT_USER", None)
        default_password = getattr(kiwi_pkg, "DEFAULT_PASSWORD", None)
        default_cert = getattr(kiwi_pkg, "DEFAULT_CERT", None)

        if not all([default_url, default_user, default_password]):
            raise KiwiUnavailable(
                "Kiwi TCMS credentials not configured (DEFAULT_URL/USER/PASSWORD missing)",
                cause=None,
            )

        scanner = KiwiClient(default_url, default_user, default_password, default_cert)
        return KiwiResult(scanner=scanner, available=True)

    except KiwiUnavailable:
        raise  # re-raise without wrapping
    except Exception as exc:  # noqa: BLE001
        raise KiwiUnavailable(
            f"Failed to load Kiwi TCMS scanner: {exc}",
            cause=exc,
        ) from exc
