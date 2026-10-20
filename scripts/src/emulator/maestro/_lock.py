"""maestro._lock — one Maestro run per device.

Why this exists
---------------

A Maestro run drives the device: it installs an APK, resets the app's session,
and starts a device server on the emulator. A *second* run on the same device
does not queue behind the first — it fights it. The two device servers kill each
other, and every flow fails within a second with

    DeviceServerDiedException: Device server died during 'deviceInfo'

which reads like a broken suite and is really two runners sharing one device.

That is not a hypothetical on a host with several checkouts: the emulator is
one process on one machine, and every checkout that asks for
``emulator-5554`` gets the same one.

The lock is a file per serial that names the owning process. A lock whose owner
is dead is stale and is taken over — a killed run must not lock the device out
of the next one, which is why the owner is a PID and not a timestamp.
"""
from __future__ import annotations

import errno
import os
import pathlib

#: Where the lock files live by default. Overridable through the environment
#: so a test never touches /tmp — read when the lock is taken, not at import,
#: so a test can point it somewhere else after this module is loaded.
LOCK_DIR = pathlib.Path(os.environ.get("MAESTRO_LOCK_DIR", "/tmp"))


class DeviceLocked(RuntimeError):
    """Another run holds this device."""

    def __init__(self, serial: str, owner: int):
        self.serial = serial
        self.owner = owner
        super().__init__(
            f"{serial} is already in use by Maestro run pid {owner}. "
            "Two runs on one device kill each other's device server, so the "
            "second one is refused rather than left to fail every flow. "
            "Wait for the other run, or point this one at another device."
        )


def _pid_alive(pid: int) -> bool:
    """Whether a process with this PID exists.

    ``EPERM`` means the process exists and belongs to someone else — still
    alive, so the lock still holds.
    """
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError as exc:  # pragma: no cover — defensive
        return exc.errno != errno.ESRCH
    return True


class DeviceLock:
    """An exclusive claim on one device, held by this process.

    The lock is a file that names the owner's PID. Acquisition is atomic where
    it can be (`O_CREAT | O_EXCL`), and a lock whose owner is gone is taken
    over rather than waited for.
    """

    def __init__(self, serial: str, *, lock_dir: pathlib.Path | None = None):
        self.serial = serial
        self._dir = lock_dir
        self._held = False

    @property
    def dir(self) -> pathlib.Path:
        """Where the lock file lives.

        The environment is read here and not at import, so a test can point the
        lock somewhere else after this module is loaded.
        """
        if self._dir is not None:
            return self._dir
        return pathlib.Path(os.environ.get("MAESTRO_LOCK_DIR", str(LOCK_DIR)))

    @property
    def path(self) -> pathlib.Path:
        """Where this device's lock file lives."""
        safe = self.serial.replace("/", "_")
        return self.dir / f"singularity-maestro-{safe}.lock"

    def _owner(self) -> int | None:
        """The PID in the lock file, or None when it cannot be read."""
        try:
            text = self.path.read_text(encoding="utf-8").strip()
        except OSError:
            return None
        try:
            return int(text.split()[0])
        except (ValueError, IndexError):
            return None

    def acquire(self) -> None:
        """Take the lock, or raise :class:`DeviceLocked`.

        Raises:
            DeviceLocked: Another live run owns this device.
        """
        self.dir.mkdir(parents=True, exist_ok=True)
        try:
            fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
        except FileExistsError:
            owner = self._owner()
            if owner is not None and _pid_alive(owner):
                # Alive includes "this process": taking the device twice is a
                # bug in the caller, not a stale lock to be taken over.
                raise DeviceLocked(self.serial, owner)
            # A stale lock — its owner is gone, or it is unreadable. Taking it
            # over is the only way the device ever becomes usable again.
            self.path.write_text(f"{os.getpid()}\n", encoding="utf-8")
            self._held = True
            return
        with os.fdopen(fd, "w") as handle:
            handle.write(f"{os.getpid()}\n")
        self._held = True

    def release(self) -> None:
        """Give the device back, if this process still holds it."""
        if not self._held:
            return
        self._held = False
        # Only remove a file that still names us: a lock taken over by
        # somebody else after we died is not ours to delete.
        if self._owner() == os.getpid():
            try:
                self.path.unlink()
            except OSError:
                pass
