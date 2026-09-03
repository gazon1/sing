---
name: singularity-todo-notifications
description: Notification port pattern for KMP with notify-send/at on JVM, AlarmManager/NotificationManager on Android, and ReminderScheduler background polling. Use when building cross-platform reminder and notification systems.
---

# Singularity TODO — Notification Port Pattern

This skill documents the notification architecture in the Singularity TODO KMP app: a `NotificationPort` interface with `notify-send`/`at` on JVM, `AlarmManager`/`NotificationManager` on Android, and a `ReminderScheduler` background poller.

## Architecture

```
NotificationPort (interface — commonMain)
    │
    ├── JvmNotificationPort (jvmMain)
    │       ├── notify-send (immediate notifications)
    │       └── at daemon (scheduled notifications)
    │
    ├── AndroidNotificationPort (androidMain)
    │       ├── AlarmManager.setExactAndAllowWhileIdle (scheduling)
    │       └── NotificationManagerCompat (delivery)
    │
    └── FakeNotificationPort (commonMain — tests)
            └── Records scheduled/canceled calls in mutable lists

ReminderScheduler (commonMain)
    └── Polls ReminderRepository.watchDueBefore() every 60s
        └── Calls NotificationPort.scheduleAt() for due items
        └── Deletes one-shot reminders after firing
```

## NotificationPort Interface

```kotlin
interface NotificationPort {
    val isAvailable: Boolean
    suspend fun scheduleAt(
        key: String,
        title: String,
        body: String,
        fireAtEpochMs: Long,
        payload: String? = null
    )
    suspend fun cancel(key: String)
    suspend fun cancelAll()
}
```

Key conventions:
- `key` is the unique identifier (e.g., `"reminder:r1"`)
- `payload` is passed through to the notification (e.g., reminder ID for deep-link)
- `isAvailable` checks binary presence (`which notify-send` on JVM, `NotificationManagerCompat.areNotificationsEnabled()` on Android)

## JVM Implementation — JvmNotificationPort

**Immediate notification** (fire-at is now or past):
```kotlin
if (fireAtEpochMs <= System.currentTimeMillis() + 500) {
    val proc = ProcessBuilder("notify-send", "-a", "Singularity", title, body)
        .redirectErrorStream(true).start()
    proc.outputStream.close()
    proc.waitFor()
    return
}
```

**Future notification** via `at` daemon:
```kotlin
val atJob = "$fireAtEpochMs".byteInputStream()
ProcessBuilder("at", "-f", "-", "-t",
    SimpleDateFormat("HHmmyyyyMMdd").format(Date(fireAtEpochMs)))
    .redirectErrorStream(true)
    .start()
    .apply { outputStream.use { it.write(atJob.readBytes()) } }
    .waitFor()

// Persist key → at job mapping for cancellation
jobFile.appendText("$key=$atJobId\n")
```

**Cancellation**: reads `jobFile`, extracts the at-job ID, runs `atrm $jobId`, removes from file.

**Job file**: `~/.singularity-todo/notify-jobs.txt` — format `key=atJobId\n` per line.

**Return type rule**: `cancel` and `cancelAll` must return `Unit`, not `Result<Unit>`:

```kotlin
override suspend fun cancel(key: String) {
    // correct: block body, not expression
    withContext(Dispatchers.IO) {
        runCatching { /* ... */ }
    }
}
```

## Android Implementation — AndroidNotificationPort

**Scheduling** with `AlarmManager`:
```kotlin
val intent = Intent(context, NotificationReceiver::class.java).apply {
    putExtra("key", key)
    putExtra("title", title)
    putExtra("body", body)
    putExtra("payload", payload)
}
val pending = PendingIntent.getBroadcast(
    context, key.hashCode(), intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
alarmManager.setExactAndAllowWhileIdle(
    AlarmManager.RTC_WAKEUP, fireAtEpochMs, pending
)
```

**BroadcastReceiver** wakes at fire time, posts notification:
```kotlin
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = NotificationManagerCompat.from(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra("title"))
            .setContentText(intent.getStringExtra("body"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(intent.getStringExtra("key").hashCode(), notif)
    }
}
```

**Cancellation**: `alarmManager.cancel(pendingIntent)` + `nm.cancel(key.hashCode())`.

## ReminderScheduler — Background Polling

Rather than relying on OS scheduled-intent guarantees, `ReminderScheduler` polls every 60 seconds:

```kotlin
class ReminderScheduler(
    private val notificationPort: NotificationPort,
    private val reminderRepository: ReminderRepository,
    private val currentUserId: UserId = UserId("current_user"),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun poll(nowEpochMs: Long = System.currentTimeMillis()) {
        if (!notificationPort.isAvailable) return
        val due = reminderRepository.watchDueBefore(nowEpochMs, currentUserId).first()
        for (reminder in due) {
            notificationPort.scheduleAt(
                key = "reminder:${reminder.id.value}",
                title = "Task Reminder",
                body = "A task reminder is due",
                fireAtEpochMs = reminder.fireAt,
                payload = reminder.id.value
            )
            // Delete one-shot after firing; recurring remain for caller to re-schedule
            if (reminder.recurringPattern == null) {
                reminderRepository.delete(reminder.id, currentUserId)
            }
        }
    }

    companion object { const val POLL_INTERVAL_MS = 60_000L }
}
```

**Polling interval**: 60 seconds — a balance between responsiveness and battery. The `ReminderScheduler` is started in `Application.onCreate()` (Android) or `main()` (JVM).

**One-shot vs recurring**:
- **One-shot** (`recurringPattern == null`): deleted after `poll()` fires the notification
- **Recurring**: caller re-creates the reminder after each firing (or a separate recurring scheduler handles it)

## Database Schema

Room entities for reminders:

```kotlin
@Entity(
    tableName = "task_reminders",
    primaryKeys = ["user_id", "id"],
    indices = [Index("user_id"), Index("task_id"), Index("fire_at")]
)
data class TaskReminderEntity(
    val id: String,
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("user_id") val userId: String,
    val type: String,          // "gentle" | "annoying"
    @ColumnInfo("offset_minutes") val offsetMinutes: Int,
    @ColumnInfo("fire_at") val fireAt: Long,
    @ColumnInfo("recurring_pattern") val recurringPattern: String?, // cron expr or null
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)
```

## Fake for Testing — FakeNotificationPort

```kotlin
class FakeNotificationPort(
    override val isAvailable: Boolean = true
) : NotificationPort {
    data class Scheduled(val key, val title, val body, val fireAtEpochMs, val payload)
    val scheduled = mutableListOf<Scheduled>()
    val canceled = mutableListOf<String>()

    override suspend fun scheduleAt(key, title, body, fireAtEpochMs, payload) {
        scheduled.add(Scheduled(key, title, body, fireAtEpochMs, payload))
    }
    override suspend fun cancel(key: String) { canceled.add(key) }
    override suspend fun cancelAll() { canceled.addAll(scheduled.map { it.key }) }
    fun reset() { scheduled.clear(); canceled.clear() }
}
```

## Testing Pattern

```kotlin
@Test
fun `poll fires notification for due reminder`() = runTest {
    val fakePort = FakeNotificationPort()
    val repo = FakeReminderRepository(userId)
    val scheduler = ReminderScheduler(fakePort, repo, userId)

    repo.add(Reminder(id, taskId, userId, Gentle, -15,
        System.currentTimeMillis() - 1000, null))
    scheduler.poll()

    assertEquals(1, fakePort.scheduled.size)
    assertEquals("reminder:r1", fakePort.scheduled[0].key)
}

@Test
fun `poll deletes one-shot reminder after firing`() = runTest {
    // ...
    assertTrue(repo.getById(ReminderId("r2"), userId) == null)
}
```

No mocks — `FakeNotificationPort` and `FakeReminderRepository` are the test doubles.

## When to Use This Pattern

- Building reminder/notification features in a KMP app targeting JVM and Android
- Polling is acceptable (60s interval) — for tighter SLAs, add platform-specific exact alarms
- Needing to test notification logic without platform APIs or root access
- Using Room for reminder persistence with a DAO that supports `watchDueBefore`

## Key Files

| File | Purpose |
|---|---|
| `shared/src/commonMain/.../core/notifications/NotificationPort.kt` | Interface |
| `shared/src/jvmMain/.../core/notifications/JvmNotificationPort.kt` | notify-send + at |
| `shared/src/androidMain/.../core/notifications/AndroidNotificationPort.kt` | AlarmManager + NotificationManager |
| `shared/src/commonMain/.../core/notifications/FakeNotificationPort.kt` | In-memory test double |
| `shared/src/commonMain/.../feature/reminders/ReminderScheduler.kt` | Background polling |
| `shared/src/commonMain/.../core/database/Entities.kt` | TaskReminderEntity |
