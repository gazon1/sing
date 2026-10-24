package com.singularity.todo.core.log

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * A [LogWriter] that writes to standard error.
 *
 * ## Why
 *
 * The desktop application logs to standard output, where a person running it from a
 * terminal sees it. The tool server cannot: its standard output is the MCP protocol
 * channel, and a client parses every line there as a message.
 *
 * The server used to get there through a global side effect — `System.out` was
 * pointed at standard error, and the log followed it there. That made the
 * application's own log lines depend on a redirect that no reader of the logging
 * code could see. Pointing the writer at standard error is the explicit form of
 * the same arrangement: the log goes where it belongs because that is where the
 * writer was told to write.
 *
 * The redirect itself stays, as a defence against a third-party library that
 * prints to standard output without asking. A defence that the application's own
 * log leans on is not a defence, and this is how the two are kept apart.
 */
class StderrLogWriter : LogWriter() {

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = buildString {
            append(severity)
            append(": ")
            if (tag.isNotEmpty()) {
                append('(')
                append(tag)
                append(") ")
            }
            append(message)
        }
        System.err.println(line)
        throwable?.let { System.err.println(it.stackTraceToString()) }
        System.err.flush()
    }
}
