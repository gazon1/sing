package com.singularity.todo.core.auth.oauth

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The loopback socket on the desktop.
 *
 * The same shape as the Android one: the socket is the only part of the
 * consent step that touches the operating system, and everything that decides
 * something lives in [OAuthConsentFlow].
 */
class JvmOAuthRedirectSocket : OAuthRedirectSocket {

    private var serverSocket: ServerSocket? = null

    override suspend fun bind(): Int = withContext(Dispatchers.IO) {
        val socket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        serverSocket = socket
        socket.localPort
    }

    override suspend fun acceptRequestTarget(timeoutMs: Long): Result<String> = withContext(Dispatchers.IO) {
        val server = serverSocket ?: return@withContext Result.failure(
            OAuthConsentException("The redirect socket was never bound"),
        )
        try {
            server.soTimeout = timeoutMs.toInt()
            server.accept().use { client ->
                val target = client.readRequestTarget()
                client.respondWithDonePage()
                Result.success(target)
            }
        } catch (e: CancellationException) {
            // Cancellation is the coroutine's own signal, not a failure of the
            // sign-in: it has to keep travelling up, or a cancelled desktop app
            // would report a failed consent to whoever asked for it.
            throw e
        } catch (e: OAuthConsentException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(
                OAuthConsentException("The sign-in took too long and was not completed: ${e.message}"),
            )
        } catch (e: IOException) {
            Result.failure(
                OAuthConsentException("The sign-in could not be completed: ${e.message}"),
            )
        }
    }

    override suspend fun respondDone() = Unit

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}

/**
 * Reads the request line and returns the target it carried.
 *
 * The headers are read and dropped: the answer is in the query string, and
 * leaving the rest of the request unread would leave the browser waiting for
 * a response that never comes.
 */
private fun Socket.readRequestTarget(): String {
    // `Socket` is not an `InputStream` — it hands one out. The reader reads the
    // request line and the headers, and nothing else.
    val reader = inputStream.bufferedReader()
    val requestLine = reader.readLine() ?: throw OAuthConsentException("The sign-in sent an empty request")
    val target = requestLine.split(' ').getOrNull(1)
        ?: throw OAuthConsentException("The sign-in sent a request this app cannot read")
    // The headers are read and dropped: the answer is in the query string, and
    // leaving the rest of the request unread would leave the browser waiting for
    // a response that never comes. The loop ends at the empty line that closes
    // the headers, or at the end of the request.
    var line = reader.readLine()
    while (line != null && line.isNotEmpty()) {
        line = reader.readLine()
    }
    return target
}

/** Answers the browser with a page that says the sign-in is done. */
private fun Socket.respondWithDonePage() {
    val body = DONE_PAGE
    val response = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: text/html; charset=utf-8\r\n")
        append("Content-Length: ${body.toByteArray().size}\r\n")
        append("Connection: close\r\n\r\n")
        append(body)
    }
    outputStream.bufferedWriter().use { it.write(response) }
}

private const val LOOPBACK = "127.0.0.1"
