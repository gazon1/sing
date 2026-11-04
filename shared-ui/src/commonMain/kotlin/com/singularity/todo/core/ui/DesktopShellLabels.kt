package com.singularity.todo.core.ui

/**
 * The names the desktop shell's own chrome publishes to assistive technology and to
 * the flow suite.
 *
 * They live in `shared-ui`, which both the shell (`shared/jvmMain`) and the desktop
 * flow tests depend on, so the control that *draws* a name and the selector that
 * *looks it up* read one constant. A duplicated literal is a link that holds only
 * until someone edits one of the two, and the edit that matters — renaming the
 * control in production — then leaves the suite red for no reason anyone can see
 * from the selector.
 */
object DesktopShellLabels {
    /**
     * The content description of the drawer trigger, the shell's one control that
     * opens and closes the navigation drawer.
     *
     * It says what the control does, not what its icon depicts: "Menu" named the
     * hamburger, which is the same shape on a thousand apps and tells a screen
     * reader nothing about what activating it will do. One value for both states,
     * because a selector that only matches while the drawer is closed cannot be the
     * same selector a flow uses to close it.
     */
    const val DRAWER_TRIGGER = "Open navigation menu"
}
