package com.singularity.todo.test.fakes

import com.singularity.todo.core.notifications.Notifier

/**
 * Test double for [Notifier].
 *
 * Records what was posted so a test can assert on the notification the user would have
 * seen, and lets a test declare the host unable to display anything — the state a
 * headless box or a session without a notification daemon is in.
 *
 * @param supported what [Notifier.isSupported] reports. Default `true`, so a test has to
 *   opt into the interesting case rather than inherit it.
 */
class FakeNotifier(private val supported: Boolean = true) : Notifier {

    /** One entry per [post] call, in call order. */
    val posts = mutableListOf<Post>()

    /** The tag/title/body/deeplink a single [post] carried. */
    data class Post(val tag: String, val title: String, val body: String, val viewId: String?)

    override val isSupported: Boolean get() = supported

    override fun post(tag: String, title: String, body: String, viewId: String?) {
        posts += Post(tag, title, body, viewId)
    }
}
