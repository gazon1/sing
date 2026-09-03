package com.singularity.todo.core.notifications

/**
 * In-memory fake of [NotificationPort] for unit tests.
 * Records all [scheduleAt] calls and provides [cancel] assertions.
 */
class FakeNotificationPort(
    override val isAvailable: Boolean = true
) : NotificationPort {

    data class Scheduled(
        val key: String,
        val title: String,
        val body: String,
        val fireAtEpochMs: Long,
        val payload: String?
    )

    val scheduled = mutableListOf<Scheduled>()
    val canceled = mutableListOf<String>()

    override suspend fun scheduleAt(
        key: String,
        title: String,
        body: String,
        fireAtEpochMs: Long,
        payload: String?
    ) {
        scheduled.add(Scheduled(key, title, body, fireAtEpochMs, payload))
    }

    override suspend fun cancel(key: String) {
        canceled.add(key)
    }

    override suspend fun cancelAll() {
        canceled.addAll(scheduled.map { it.key })
    }

    fun reset() {
        scheduled.clear()
        canceled.clear()
    }
}
