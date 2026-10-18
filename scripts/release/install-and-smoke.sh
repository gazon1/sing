#!/usr/bin/env bash
# install-and-smoke.sh — install a packaged Singularity build and run it.
#
# #664: release.yml verified its artifacts by reading metadata only — `dpkg -f`,
# `rpm -qp`, the APK's certificate. All of that reads a header. Nothing ever
# installed the artifact or executed a byte of it, and both release verifications
# that shipped this repository's first ever release were wrong about correct
# artifacts: one read a v1 signature an APK with `minSdk 26` does not have, the
# other compared a version against jpackage's own `RELEASE=1`.
#
# This runs in a throwaway container so the check is about the package, not about
# the runner. It asserts four things, in increasing order of what they prove:
#
#   1. the package manager installs the file at all (maintainer scripts, deps,
#      arch, checksum — none of which a header records);
#   2. the launcher is where the package database says it is;
#   3. the launcher runs and exits 0 — which starts the bundled JRE, loads the app
#      classpath, and reaches `main`. A package whose jars were mis-staged dies
#      here with "Could not find or load main class", a container check that reads
#      only the header would call it a pass;
#   4. it prints the version the tag named — which is the runtime half of the
#      metadata half. The package said 0.2.1 while every jar inside reported 0.1.0
#      through `-Dsingularity.version`, so an installed user was told 0.1.0
#      forever and the in-app update gate compared 0.1.0 against the releases feed.
#
# Usage (from release.yml):
#   INSTALL_CMD='apt-get install -y ./app.deb' \
#   PKG_FORMAT=deb PACKAGE=/tmp/smoke/app.deb EXPECT_VERSION=0.2.1 \
#   scripts/release/install-and-smoke.sh
#
# `--version` is what makes step 3 possible at all. Before it existed, the only way
# to exercise a packaged desktop build was to open a window: impossible in a
# container, and the app's own headless entry points (`fire-reminder`) reach the
# database, so a failure there would say nothing about packaging.

set -euo pipefail

: "${INSTALL_CMD:?INSTALL_CMD is the command that installs the package inside the container}"
: "${PKG_FORMAT:?PKG_FORMAT is deb or rpm}"
: "${PACKAGE:?PACKAGE is the path to the built package inside the container}"
: "${EXPECT_VERSION:?EXPECT_VERSION is the version the tag named}"

# The .deb ships a no-op postinst (patched by patchDebPostinst in build.gradle.kts,
# #686): the original called `xdg-desktop-menu install` which exits 3 on headless
# Debian because no desktop-directories directory exists. The workaround directories
# are no longer needed and are not asserted.

# The mount is the only thing carrying the artifact into the container, and a `cp`
# that produced nothing leaves an empty directory that `apt-get install ./app.deb`
# reports as "No such file" — a message about a filename, not about the copy.
[ -f "$PACKAGE" ] || {
    echo "::error::$PACKAGE is not inside the container; the mount or the copy failed" >&2
    exit 1
}

# `bash -c`, not bare `$INSTALL_CMD`: word splitting would pass `;`, `>` and `2>&1`
# to apt as literal arguments — which is what an unquoted expansion turns them into.
# Quoted, the shell parses it, so the caller can write a real command line.
bash -c "$INSTALL_CMD"

readonly launcher=/opt/singularity-todo/bin/singularity-todo

case "$PKG_FORMAT" in
    deb) listed=$(dpkg -L singularity-todo) ;;
    rpm) listed=$(rpm -ql singularity-todo) ;;
    *) echo "::error::unknown PKG_FORMAT '$PKG_FORMAT'" >&2; exit 1 ;;
esac

# Asserted against the package database rather than `ls /opt`: a file can be on
# disk without being owned by the package, and `rpm -ql` is also what proves the
# rpm's own file list is intact.
grep -qx "$launcher" <<<"$listed" || {
    echo "::error::$PKG_FORMAT does not list $launcher; it installs:" >&2
    grep -E '/bin/' <<<"$listed" >&2 || true
    exit 1
}

[ -x "$launcher" ] || { echo "::error::$launcher is not executable after install" >&2; exit 1; }

# Deliberately not asserted: that the launcher is on `PATH`. jpackage creates no
# /usr/bin symlink, so nothing is on PATH and asserting it would be asserting a
# packaging decision nobody made. The constant that does reference a launcher path,
# `REMINDER_LAUNCHER_PATH`, is checked against a real installed package by
# `JvmReminderSchedulerLauncherPathTest` and by this script's path above.

out=$("$launcher" --version) || {
    echo "::error::$launcher --version exited $?; the launcher or its bundled runtime is broken" >&2
    exit 1
}
echo "installed launcher reports: $out"

case "$out" in
    *"$EXPECT_VERSION"*) ;;
    *)
        echo "::error::the installed launcher reports a version that is not the tag's" >&2
        echo "  tag:      $EXPECT_VERSION" >&2
        echo "  reported: $out" >&2
        exit 1
        ;;
esac

echo "install-and-smoke OK ($PKG_FORMAT $EXPECT_VERSION)"