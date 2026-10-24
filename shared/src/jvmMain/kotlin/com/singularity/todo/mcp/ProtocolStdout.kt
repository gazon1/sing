package com.singularity.todo.mcp

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import java.io.OutputStream
import java.io.PrintStream

/**
 * The protocol's standard output — the one stream a protocol message may be written to.
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
 * ## The contract, made explicit
 *
 * "Standard output is the protocol channel" used to be a fact about a global:
 * [reserve] moved `System.out` aside, and every caller that wanted to write the
 * protocol had to know that a stream had been kept somewhere.
 *
 * Now it is a value. This type owns the protocol's stream, and it is passed to
 * everything that writes the protocol. A writer that holds it cannot write
 * anywhere else, and a writer that does not hold it cannot reach the protocol.
 * The type is the contract; the stream it holds is what the contract is about.
 *
 * ## What it does about the rest of the process
 *
 * Taking the channel also points `System.out` at standard error, so a print that
 * nobody asked for — a library's banner, a `println` left in a dependency — cannot
 * reach a client that parses standard output strictly.
 *
 * That redirect is a **defence**, not the contract. The application's own log is
 * pointed at standard error by its own configuration
 * (`StderrLogWriter`, installed by the server's start-up), so the one thing this
 * repository controls does not depend on a global side effect. A defence that
 * everything leans on is not a defence.
 *
 * The redirection is deliberately a *separate* stream rather than
 * `System.setOut(System.err)`: sharing the object would mean that closing one
 * closes the other, and the transport closes the output it was handed.
 */
internal class ProtocolStdout private constructor(private val stream: PrintStream) {

    /**
     * The protocol's stream.
     *
     * For the transport and for the early error responses — the two places that
     * write a protocol message before or outside the protocol loop.
     */
    fun asPrintStream(): PrintStream = stream

    /**
     * The protocol's stream as a write channel, which is what the MCP transport
     * writes its frames to.
     */
    fun asByteWriteChannel(): ByteWriteChannel = stream.asByteWriteChannel()

    companion object {

        /**
         * Takes standard output for the protocol and moves everything else to standard error.
         *
         * @return the protocol's channel — the only stream a protocol message may be
         *   written to.
         */
        fun reserve(): ProtocolStdout {
            val protocol = System.out
            System.setOut(stderrStream())
            return ProtocolStdout(protocol)
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
}
