# ==============================================================================
# 🖥️ DESKTOP — build, run, db
# Recipes available as desktop::<recipe>.
# ==============================================================================

DEB_FILE := "desktopApp/build/compose/binaries/main/deb/singularity-todo_0.1.0_amd64.deb"

import 'build.just'
import 'run.just'
import 'db/mod.just'
