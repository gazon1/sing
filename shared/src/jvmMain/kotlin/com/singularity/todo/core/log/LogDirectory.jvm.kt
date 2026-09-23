package com.singularity.todo.core.log

import okio.Path
import java.lang.reflect.Method

actual fun logDirectory(): Path {
    val userHome = System.getProperty("user.home")
        ?: throw IllegalStateException("user.home system property is not set")
    val raw = "$userHome/.local/share/singularity/logs"
    val method: Method = Path::class.java.getMethod("get", String::class.java)
    @Suppress("UNCHECKED_CAST")
    val path = method.invoke(null, raw) as Path
    return path
}
