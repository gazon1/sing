#!/usr/bin/env bash
# provision-avd.sh — make an Android emulator, its system image and its AVD exist
# on this machine. Idempotent: a machine that already has all three is left alone.
#
# Why this is a script and not a workflow step: the AVD is produced by one job
# (`setup-avd`) and consumed by another (`e2e` / `instrumented`), and runners do not
# share a filesystem. The cache is the intended transport between them, and it is
# an optimisation, not a guarantee — a cache entry can be evicted, and the entry
# that was written before the paths were fixed holds nothing at all. A consumer
# that depends on the cache alone turns "the cache was evicted" into "every shard
# is red", which is what run 39253664636 did: all four Maestro shards died with
# "'emulator' binary not found in PATH" on a runner where the producer had
# installed that exact binary ninety seconds earlier.
#
# So: the cache makes the common case fast, and this script makes the uncommon
# case work. Both the producer and the consumers run it, so the two sides cannot
# disagree about what "provisioned" means.
#
# Environment:
#   AVD_NAME         — name of the AVD to create            (required)
#   SYSTEM_IMAGE    — sdkmanager package id                (required)
#   DEVICE          — avdmanager hardware profile          (default: pixel_6)
#   SDK_ROOT        — Android SDK root                     (default: $ANDROID_SDK_ROOT/$ANDROID_HOME)
#   ANDROID_AVD_HOME— directory holding the AVD            (default: $HOME/.android/avd)
#
# Exit codes:
#   0 — the emulator, the system image and the AVD are present
#   1 — provisioning failed (the message says which part)
#   2 — the inputs are missing or unusable

set -euo pipefail

AVD_NAME="${AVD_NAME:-}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-}"
DEVICE="${DEVICE:-pixel_6}"
SDK_ROOT="${SDK_ROOT:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

fail() {
    echo "::error::provision-avd: $*"
    echo "provision-avd: $*" >&2
    exit 1
}

usage_error() {
    echo "::error::provision-avd: $*"
    echo "provision-avd: $*" >&2
    exit 2
}

[ -n "$AVD_NAME" ] || usage_error "AVD_NAME is not set"
[ -n "$SYSTEM_IMAGE" ] || usage_error "SYSTEM_IMAGE is not set"
[ -n "$SDK_ROOT" ] || usage_error "SDK_ROOT is not set and neither ANDROID_SDK_ROOT nor ANDROID_HOME is"
[ -d "$SDK_ROOT" ] || fail "no Android SDK at $SDK_ROOT"

export ANDROID_AVD_HOME
mkdir -p "$ANDROID_AVD_HOME"

emulator_bin="$SDK_ROOT/emulator/emulator"
# `system-images;android-36;google_apis;x86_64` is the sdkmanager package id;
# it unpacks into the same path with slashes, directly under the SDK root.
image_dir="$SDK_ROOT/${SYSTEM_IMAGE//;/\/}"
avd_dir="$ANDROID_AVD_HOME/$AVD_NAME.avd"

if [ -x "$emulator_bin" ] && [ -d "$image_dir" ] && [ -f "$avd_dir/userdata.img" ]; then
    echo "provision-avd: emulator, system image and AVD $AVD_NAME are already present"
else
    cmdline="$(find "$SDK_ROOT/cmdline-tools" -name sdkmanager -type f 2>/dev/null | sort | tail -1 || true)"
    [ -n "$cmdline" ] || fail "no sdkmanager under $SDK_ROOT/cmdline-tools"
    cmdline="$(dirname "$cmdline")"

    # "emulator" and "platform-tools" are named explicitly. Relying on the system
    # image to pull the emulator in as a transitive dependency is a requirement
    # that depends on somebody else's side effect: it happened to work, and the
    # only place it showed up was a different job with no emulator binary.
    yes | "$cmdline/sdkmanager" --licenses >/dev/null 2>&1 || true
    "$cmdline/sdkmanager" "emulator" "platform-tools" "$SYSTEM_IMAGE"

    if [ ! -f "$avd_dir/userdata.img" ]; then
        echo "provision-avd: creating AVD $AVD_NAME (device $DEVICE)"
        echo no | "$cmdline/avdmanager" create avd \
            --force --name "$AVD_NAME" --package "$SYSTEM_IMAGE" --device "$DEVICE"
    else
        echo "provision-avd: AVD $AVD_NAME already exists"
    fi
fi

[ -x "$emulator_bin" ] || fail "no emulator binary at $emulator_bin"
"$emulator_bin" -list-avds | grep -qx "$AVD_NAME" \
    || fail "emulator -list-avds does not list $AVD_NAME"

# Put the tools on PATH for the steps that follow. The workflows also do this,
# but a script that leaves the machine half-provisioned is how the binary went
# missing in the first place.
if [ -n "${GITHUB_PATH:-}" ]; then
    {
        echo "$SDK_ROOT/emulator"
        echo "$SDK_ROOT/platform-tools"
    } >> "$GITHUB_PATH"
fi

# ── What the cache carries ───────────────────────────────────────────────────
# The cache entry for this AVD carries the SDK payload and nothing else: the
# emulator and the system image, under the SDK root. The AVD is not part of it —
# it is created where it is used, on every runner, by this script.
#
# The entry that also carried the AVD measured ~2.15 GB, and nothing said so.
# The next person to put the AVD back would have found out from a slow run and
# not from the log. This report is the log: the payload paths, the size of
# each, the total, and the size of the AVD that is deliberately not in it. A
# diff between two runs shows a cache that grew.
#
# Both the producer and the consumers run this script, so both logs carry the
# same report and the two sides cannot disagree about what the cache holds.
dir_size_bytes() {
    # `du -sb` counts apparent bytes (GNU coreutils); `du -sk` is the fallback
    # and rounds to 1 KiB, which is enough for a report. A path that cannot be
    # measured reports 0 — this is a report, not a gate, and it must not fail
    # a job that has already provisioned everything it was asked for.
    local path="$1" bytes
    bytes="$(du -sb "$path" 2>/dev/null | cut -f1 || true)"
    if [ -z "$bytes" ]; then
        bytes="$(du -sk "$path" 2>/dev/null | cut -f1 || true)"
        bytes="${bytes:-0}"
        bytes=$((bytes * 1024))
    fi
    printf '%s' "${bytes:-0}"
}

report_cache_payload() {
    # The two paths `_setup-avd.yml` caches, derived from the same SDK root the
    # cache step uses. If that list ever changes, this one has to change with
    # it — this report is what makes the difference visible.
    local paths=("$SDK_ROOT/system-images" "$SDK_ROOT/emulator")
    local total=0 path size line
    local lines=()

    lines+=("cache payload — the paths _setup-avd.yml caches:")
    for path in "${paths[@]}"; do
        size="$(dir_size_bytes "$path")"
        lines+=("  $path = $size bytes")
        total=$((total + size))
    done
    lines+=("  total = $total bytes")

    size="$(dir_size_bytes "$avd_dir")"
    lines+=("  not cached: $avd_dir = $size bytes (the AVD is created here, it never travels)")

    for line in "${lines[@]}"; do
        echo "provision-avd: $line"
    done

    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        {
            echo "### Android SDK cache payload"
            echo
            echo '```'
            printf '%s\n' "${lines[@]}"
            echo '```'
        } >> "$GITHUB_STEP_SUMMARY"
    fi
}

report_cache_payload

echo "provision-avd: emulator $emulator_bin, AVD $AVD_NAME in $ANDROID_AVD_HOME"
