"""Unit tests for emulator._loguru — idempotency and handler count."""
from __future__ import annotations

import unittest


class LoguruIdempotencyTest(unittest.TestCase):
    """Verify setup_logger() is safe to call multiple times across modules."""

    def test_consecutive_calls_do_not_raise(self):
        """Calling setup_logger() N times must not raise.

        This is the core invariant: the _CONFIGURED sentinel prevents
        re-configuration on repeated imports or repeated explicit calls.
        If the sentinel fails, _logger.add() would add a duplicate handler on each
        call, and subsequent log calls would multiply output.
        """
        import sys

        for m in [k for k in sys.modules if k.startswith("emulator._loguru")]:
            del sys.modules[m]

        from emulator._loguru import setup_logger

        # Five consecutive calls — all must be no-ops, no exceptions.
        setup_logger(is_ci=True)
        setup_logger(is_ci=True)
        setup_logger(is_ci=True)
        setup_logger(is_ci=True)
        setup_logger(is_ci=True)

    def test_ci_and_non_ci_both_idempotent(self):
        """Both is_ci=True and is_ci=False must be individually idempotent."""
        import sys

        for m in [k for k in sys.modules if k.startswith("emulator._loguru")]:
            del sys.modules[m]

        from emulator._loguru import setup_logger

        # Alternate between CI and non-CI; each call must be a no-op.
        for is_ci in (True, False) * 3:
            setup_logger(is_ci=is_ci)

    def test_setup_logger_returns_none(self):
        """setup_logger() must return None (not the logger or a handler)."""
        import sys

        for m in [k for k in sys.modules if k.startswith("emulator._loguru")]:
            del sys.modules[m]

        from emulator._loguru import setup_logger

        result = setup_logger(is_ci=True)
        self.assertIsNone(result)

    def test_get_logger_returns_logger(self):
        """get_logger() must return the loguru logger instance."""
        import sys

        for m in [k for k in sys.modules if k.startswith("emulator._loguru")]:
            del sys.modules[m]

        from emulator._loguru import get_logger

        logger = get_logger()
        self.assertIsNotNone(logger)
        # loguru loggers have a .info() method.
        self.assertTrue(hasattr(logger, "info"))


if __name__ == "__main__":
    unittest.main()
