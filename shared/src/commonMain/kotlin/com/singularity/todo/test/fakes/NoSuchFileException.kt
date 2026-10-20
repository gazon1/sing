package com.singularity.todo.test.fakes

/**
 * The "no such file" failure of [MapFileSystem].
 *
 * It used to live in `core/files/FileSystem.kt`, next to the port it never
 * belonged to: the platform implementations throw their own exception —
 * `java.io.FileNotFoundException` on both JVM and Android — and the port's own
 * header says so ("errors are propagated as platform-specific exceptions — no
 * wrapping here"). The KDoc on `readBytes` promised a different exception, and
 * the promise was kept by exactly one implementation: the in-memory double.
 *
 * So the exception moved with the double. The port's KDoc now describes what
 * the production code actually does.
 */
class NoSuchFileException(path: String) : Exception("No such file: $path")
