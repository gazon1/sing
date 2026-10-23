package com.singularity.todo.mcp

import java.io.OutputStream
import java.io.PrintStream

/**
 * The protocol channel, and everything that is not the protocol.
 *
 * ## Why the process's standard output is reserved
 *
 * A stdio server has exactly one protocol channel: the lines it writes to standard
 * output are the messages its client parses. The MCP specification says so in one
 * sentence — a server *must not* write anything to standard output that is not a
 * valid MCP message — and a client that takes that literally reads every line as
 * JSON.
 *
 * The failure this prevents is not hypothetical and was not visible in the tests:
 * the server's own log lines, and one banner from a third-party logging library
 * that initialises itself on first use, were both landing on standard output. A
 * client that parses strictly sees a malformed message and stops; a client that
 * skips what it cannot parse sees a server that answers late. Either way the agent
 * is left without the tool access the application promised it, and nothing on the
 * screen says why.
 *
 * ## What is reserved, and what is not
 *
 * [reserve] hands back the real standard output and points `System.out` at standard
 * error. From that moment on, everything anybody prints in this process — this
 * application's logger, a library's start-up banner, a stray `println` — lands on
 * standard error, where the protocol does not live and where a client does not
 * look. The caller keeps the returned stream and writes the protocol to it.
 *
 * The redirection is deliberately a *separate* stream rather than
 * `System.setOut(System.err)`: sharing the object would mean that closing one
 * closes the other, and the transport closes the output it was handed.
 */
internal object ProtocolStdout {

    /**
     * Takes standard output for the protocol and moves everything else to standard error.
     *
     * @return the process's real standard output — the only stream the protocol may be
     *   written to.
     */
    fun reserve(): PrintStream {
        val protocol = System.out
        System.setOut(stderrStream())
        return protocol
    }

    /** A distinct stream that forwards to standard error. */
    private fun stderrStream(): PrintStream =
        PrintStream(
            object : OutputStream() {
                override fun write(b: Int) {
                    System.err.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    System.err.write(b, off, len)
                }

                override fun flush() {
                    System.err.flush()
                }
            },
            true,
        )
}
