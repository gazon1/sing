package com.singularity.todo.core.log

actual fun osDescription(): String {
    val osName = System.getProperty("os.name", "unknown")
    val osVersion = System.getProperty("os.version", "")
    return "$osName $osVersion".trim()
}
