"""maestro._fixtures — per-run fixture setup and teardown."""
from __future__ import annotations

import time
from typing import TYPE_CHECKING

from loguru import logger as _logger

from emulator._loguru import setup_logger
from emulator.maestro._types import APP_ID

if TYPE_CHECKING:  # pragma: no cover — import cycle guard, annotations only
    from emulator.maestro._types import DeviceDeps

# idempotent; uses the shared logger configured by the first module to import it.
setup_logger(is_ci=False)
logger = _logger


def reset_session(serial: str, deps: "DeviceDeps") -> None:
    """Give the app a fresh process, and with it the session it starts with.

    A session reset is a different kind of fixture from the content wipe in
    :func:`cleanup_run_fixtures`, and it needs its own mechanism: the wipe
    removes the *rows* a flow seeded, while this restores the *session* the
    next flow runs under. The two cannot be folded into each other — a flow
    that ends signed out leaves no rows behind and still hands the next flow
    an app that is signed out.

    In the E2E build the session lives in the process. The auth repository is
    a stub that reports ``Session.SignedIn`` from the first frame and keeps
    the session in memory, so a fresh process is a fresh session: force-
    stopping the app and starting it again is the reset, and it is what stops
    a flow that ends signed out from deciding the session of the flow after
    it.

    The flows that need a *signed-out* app ask for one themselves, through
    the app's debug session link; this is the other half — the state every
    other flow may assume.
    """
    if not deps.device_alive(serial):
        return
    deps.adb_on(serial, "shell", "am", "force-stop", APP_ID)
    time.sleep(1)
    deps.adb_on(serial, "shell", "am", "start", "-n", f"{APP_ID}/.MainActivity")
    deps.wait_for_app_resumed(serial)


#: The rotation settings the fixture took from a device, so that they can be
#: given back when the run is over. Keyed by serial — one device per process.
#: The value holds the settings that could be *read*: a setting that could not
#: be read is absent, and only the settings that are there are given back.
_TAKEN_ROTATION: dict[str, dict[str, str]] = {}

#: The settings the fixture takes and gives back, in the order they are given
#: back: the orientation first, the policy that follows it.
_ROTATION_SETTINGS = ("user_rotation", "accelerometer_rotation")


def _read_rotation(serial: str, deps: "DeviceDeps", key: str) -> str | None:
    """A rotation setting's current value, or None when it cannot be read.

    `settings get` answers with the value, and answers with nothing at all
    when the setting does not exist. A value that is not a number is not a
    rotation setting, and is treated as unreadable.
    """
    value = deps.adb_on(serial, "shell", "settings", "get", "system", key)
    if value is None:
        return None
    value = value.strip()
    return value if value.isdigit() else None


def reset_orientation(serial: str, deps: "DeviceDeps") -> None:
    """Put the device back in portrait. Best-effort.

    A flow that rotates the device and then fails leaves it rotated, and the
    next flow is measured against that: Maestro keeps the screen size it read
    before the rotation, so every visibility check on the wide landscape layout
    is computed against the portrait width — a row that is on the screen comes
    out as "not visible", and every flow after the rotation fails on it. The
    suite then reports one defect as forty (measured 2026-11-25, API 36
    emulator: `lifecycle/03-rotate-in-editor` failed, and the eight flows
    after it failed on the rotation it left behind).

    The orientation is a fixture like any other: it is part of what a flow
    leaves on the device, and it is put back before the next flow runs.

    **A fixture gives back what it took.** The device's own rotation settings
    are read before the first change and restored by :func:`restore_orientation`
    when the run is over. Without that, the fixture leaves a physical phone
    with automatic rotation switched off — a setting the person holding the
    phone chose, and one the runner has no business keeping changed.
    """
    if not deps.device_alive(serial):
        return
    _take_rotation(serial, deps)
    deps.adb_on(serial, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
    deps.adb_on(serial, "shell", "settings", "put", "system", "user_rotation", "0")


def _take_rotation(serial: str, deps: "DeviceDeps") -> None:
    """Remember the device's own rotation settings — once per device.

    Only the first call reads them: after it, the settings on the device are
    the ones the fixture put there, and reading them again would remember the
    fixture's own values as the device's.

    Each setting is taken on its own. A device that answers for one of them
    and not for the other is still a device whose readable setting has to be
    given back — taking them as a pair would throw the readable one away with
    the unreadable one, and leave the device with the fixture's value for both.
    """
    if serial in _TAKEN_ROTATION:
        return
    taken: dict[str, str] = {}
    for key in _ROTATION_SETTINGS:
        value = _read_rotation(serial, deps, key)
        if value is None:
            logger.warning(
                f"the device's {key} could not be read — it cannot be given back "
                "when the run is over",
            )
            continue
        taken[key] = value
    _TAKEN_ROTATION[serial] = taken


def restore_orientation(serial: str, deps: "DeviceDeps") -> None:
    """Give the device back the rotation settings the fixture took.

    Best-effort, like the fixture itself: a device that is gone cannot be
    given anything back, and a run that ends with a dead device has already
    lost the run.

    Only the settings that were read are given back; a setting that could not
    be read was reported when it was taken.

    Called when the run is over — not after every flow. Between flows the
    device stays in portrait, which is what the next flow is measured
    against; at the end of the run the person who owns the device gets their
    own settings back.

    The orientation is put back before the policy that follows it: the
    orientation is where the device is, and the policy is what decides where
    it is allowed to go.
    """
    taken = _TAKEN_ROTATION.pop(serial, None)
    if not taken:
        return
    if not deps.device_alive(serial):
        return
    for key in _ROTATION_SETTINGS:
        value = taken.get(key)
        if value is None:
            continue
        deps.adb_on(serial, "shell", "settings", "put", "system", key, value)


def cleanup_run_fixtures(
    serial: str, mode: str, deps: "DeviceDeps",
) -> None:
    """Remove what the previous flow left on the device. Best-effort.

    Two scopes, and the default is the one that makes the suite mean what
    it says:

    - ``all`` (default) — wipe every task, note and project in the active
      profile. This is the only scope that gives each flow **its own data**:
      the flows share one device and one database, so anything a flow leaves
      behind is still there for the next one, and a flow that asserts an
      empty list then fails on the *previous* flow's row.
    - ``off`` — send nothing.

    The narrow scope — a sweep of the rows whose title starts with the run id
    or with a fixed title — is gone, and it is not coming back in that shape.
    Its boundary was a *list of titles* that somebody had to keep up to date
    by hand, and the list was already behind the suite: a title it forgot
    survived the run, and the failure that caused was silent — a flow that
    asserts an empty list fails on the *previous* flow's data, and a flow
    that asserts its own task fails because another flow's task is in the way.
    The nightly run of 2026-10-20 failed 43 flows across all four shards in
    exactly that shape, while the same flows passed in the smoke suite. See
    the ADR ``2026-10-28-the-bridge-policy-is-a-file.md``.

    Why ``all`` is the default and the seed activity's own warning about it
    ("not concurrency-safe by construction") does not apply: the runner holds
    a per-device lock for the whole run, so there is no second suite on the
    device to wipe. See ``maestro._lock`` and the ADR
    ``2026-10-21-each-flow-gets-a-clean-device.md``.

    The wipe is scoped to the active profile — the profile, the session and
    the auth state are left alone, and a flow that switched profiles is
    cleaned while that profile is still active.

    Uses ``deps.adb_on`` so ``-s <serial>`` is present; see
    ``emulator/maestro/_artifacts.py`` for the failure this prevents.
    """
    if mode == "off":
        return
    if mode != "all":
        raise ValueError(f"unknown cleanup scope: {mode!r}")
    if not deps.device_alive(serial):
        return
    cmd = "am start -a android.intent.action.VIEW -d todo-debug://seed?reset=content"
    deps.adb_on(serial, "shell", cmd)
    # The reset runs on the seed activity's own thread, and the next step
    # force-stops the app for the session reset — so the wait has to be for the
    # reset to be *done*, not for a fixed number of seconds. A fixed sleep
    # killed the reset half way through on a slow device, and the tables the
    # reset empties last (the saved agenda views) stayed populated: the next
    # flow then asserted an empty list and found the previous flow's row.
    #
    # The wait answers "the reset thread has ended", not "the reset worked" —
    # a seeding thread that threw still brings the app up. So a false answer
    # is not a failed reset; it means the app never came up, and the flow that
    # follows would run against whatever was on the screen. Say so instead of
    # letting the next step read the answer as a clean fixture.
    if not deps.wait_for_main_activity_resumed(serial):
        logger.warning(
            "the app did not come up after the content reset — "
            "the next flow may run against the previous flow's rows",
        )
