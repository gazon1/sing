# =============================================================================
# Singularity Todo KMP — Build recipes
# Run `just --list` for all available commands.
# Run `just tests::check` before every commit.
#
# Module recipes: just <module>::<recipe>
#   just android::build release
#   just android::run serial=emulator-5554
#   just desktop::build deb
#   just desktop::run-headless
#   just tests::check
# =============================================================================

mod config   '.just/config'
mod android  '.just/android'
mod desktop  '.just/desktop'
mod tests    '.just/tests'
mod scripts  '.just/scripts'

set shell := ["bash", "-uc"]
set unstable

# ==============================================================================
# 🎯 DEFAULT
# ==============================================================================
[doc('Show available commands')]
default:
    @just --list --list-heading $'🎯 Available Commands:\n' --list-prefix '  • '

# ==============================================================================
# Super-aliases
# ==============================================================================

# ----- Android shortcuts -----
alias aapk   := android::build-debug
alias arel   := android::build-release
alias arun   := android::run
alias at     := android::test
alias al     := android::logs
alias adb-d  := android::devices
alias adb-stop := android::force-stop

# ----- Desktop shortcuts -----
alias dr     := desktop::build-run
alias drh    := desktop::run-headless
alias ddist  := desktop::build-dist
alias dpkg   := desktop::build-deb
alias dinst  := desktop::install-deb

# ----- Tests shortcuts -----
alias tc     := tests::common
alias tj     := tests::jvm
alias tah    := tests::android-host
alias tcheck := tests::check
alias tclean := tests::clean

# ----- DB shortcuts -----
alias db-a   := android::db-schema
alias db-d   := desktop::db-schema

# ----- Scripts shortcuts -----
alias bench  := scripts::bench
alias rd     := scripts::refresh-decisions
