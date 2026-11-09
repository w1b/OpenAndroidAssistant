/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.clock

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.openandroidassistant.MainActivity
import org.openandroidassistant.R
import org.openandroidassistant.data.AlarmEntity
import org.openandroidassistant.data.ClockDao
import org.openandroidassistant.data.OaaDatabase
import org.openandroidassistant.data.ReminderEntity
import org.openandroidassistant.data.TimerEntity

const val CHANNEL_RINGING = "clock_ringing"
const val CHANNEL_TIMERS = "clock_timers"
const val CHANNEL_REMINDERS = "reminders"

/**
 * The assistant's own timers, alarms and reminders. Owning them (instead of handing off to a
 * clock app) is what makes "list", "cancel" and "change" possible.
 * Rings are scheduled with [AlarmManager.setAlarmClock], so they fire exactly and in Doze.
 */
@Singleton
class ClockManager
@Inject
constructor(@ApplicationContext private val context: Context, db: OaaDatabase) {
  val dao: ClockDao = db.clockDao()
  private val alarmManager = context.getSystemService(AlarmManager::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)

  init {
    notifications.createNotificationChannels(
      listOf(
        NotificationChannel(CHANNEL_RINGING, "Alarms and timers going off", NotificationManager.IMPORTANCE_HIGH).apply {
          setSound(null, null)
        },
        NotificationChannel(CHANNEL_TIMERS, "Running timers", NotificationManager.IMPORTANCE_LOW),
        NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH),
      )
    )
  }

  // ---------------------------------------------------------------- timers

  suspend fun startTimer(totalMs: Long, label: String): TimerEntity {
    val timer = TimerEntity(label = label, totalMs = totalMs, endsAtMs = System.currentTimeMillis() + totalMs)
    val saved = timer.copy(id = dao.insertTimer(timer))
    scheduleTimer(saved)
    return saved
  }

  suspend fun updateTimer(timer: TimerEntity) {
    dao.updateTimer(timer)
    if (timer.pausedRemainingMs == null && !timer.ringing) scheduleTimer(timer) else unscheduleTimer(timer)
  }

  suspend fun deleteTimer(timer: TimerEntity) {
    dao.deleteTimer(timer)
    unscheduleTimer(timer)
    RingingService.stopFor(context, Ring.Timer(timer.id))
  }

  private fun scheduleTimer(timer: TimerEntity) {
    setAlarmClock(timer.endsAtMs, ringIntent(Ring.Timer(timer.id)))
    notifications.notify(
      notificationId(Ring.Timer(timer.id)),
      NotificationCompat.Builder(context, CHANNEL_TIMERS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(timer.label.ifBlank { "Timer" })
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setWhen(timer.endsAtMs)
        .setShowWhen(true)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(openApp())
        .addAction(0, "Cancel", actionIntent(ACTION_CANCEL, Ring.Timer(timer.id)))
        .build(),
    )
  }

  private fun unscheduleTimer(timer: TimerEntity) {
    alarmManager.cancel(ringIntent(Ring.Timer(timer.id)))
    notifications.cancel(notificationId(Ring.Timer(timer.id)))
  }

  // ---------------------------------------------------------------- alarms

  suspend fun saveAlarm(alarm: AlarmEntity): AlarmEntity {
    val withNext = alarm.copy(nextAtMs = if (alarm.enabled) nextOccurrence(alarm) else 0)
    val saved =
      if (alarm.id == 0L) withNext.copy(id = dao.insertAlarm(withNext))
      else withNext.also { dao.updateAlarm(it) }
    if (saved.enabled) setAlarmClock(saved.nextAtMs, ringIntent(Ring.Alarm(saved.id)))
    else alarmManager.cancel(ringIntent(Ring.Alarm(saved.id)))
    return saved
  }

  suspend fun deleteAlarm(alarm: AlarmEntity) {
    dao.deleteAlarm(alarm)
    alarmManager.cancel(ringIntent(Ring.Alarm(alarm.id)))
    RingingService.stopFor(context, Ring.Alarm(alarm.id))
  }

  /** After an alarm rang: one-time alarms turn off, repeating ones move to their next day. */
  suspend fun afterAlarmRang(id: Long) {
    val alarm = dao.alarm(id) ?: return
    saveAlarm(if (alarm.daysMask == 0) alarm.copy(enabled = false) else alarm)
  }

  suspend fun snoozeAlarm(id: Long, minutes: Int = 10) {
    setAlarmClock(System.currentTimeMillis() + minutes * 60_000L, ringIntent(Ring.Alarm(id)))
  }

  // ---------------------------------------------------------------- reminders

  suspend fun saveReminder(reminder: ReminderEntity): ReminderEntity {
    val saved =
      if (reminder.id == 0L) reminder.copy(id = dao.insertReminder(reminder))
      else reminder.also { dao.updateReminder(it) }
    val due = saved.dueAtMs
    if (due != null && !saved.done) setAlarmClock(due, ringIntent(Ring.Reminder(saved.id)))
    else alarmManager.cancel(ringIntent(Ring.Reminder(saved.id)))
    return saved
  }

  suspend fun deleteReminder(reminder: ReminderEntity) {
    dao.deleteReminder(reminder)
    alarmManager.cancel(ringIntent(Ring.Reminder(reminder.id)))
    notifications.cancel(notificationId(Ring.Reminder(reminder.id)))
  }

  fun showReminder(reminder: ReminderEntity) {
    notifications.notify(
      notificationId(Ring.Reminder(reminder.id)),
      NotificationCompat.Builder(context, CHANNEL_REMINDERS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Reminder")
        .setContentText(reminder.text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(openApp())
        .addAction(0, "Done", actionIntent(ACTION_DONE, Ring.Reminder(reminder.id)))
        .build(),
    )
  }

  /** Re-arms everything after a reboot or time change. */
  suspend fun rescheduleAll() {
    val now = System.currentTimeMillis()
    for (t in dao.timers()) {
      if (t.pausedRemainingMs == null && !t.ringing) {
        if (t.endsAtMs > now) scheduleTimer(t) else RingingService.start(context, Ring.Timer(t.id))
      }
    }
    for (a in dao.alarms()) if (a.enabled) saveAlarm(a)
    for (r in dao.openReminders()) {
      val due = r.dueAtMs ?: continue
      if (due > now) setAlarmClock(due, ringIntent(Ring.Reminder(r.id))) else showReminder(r)
    }
  }

  // ---------------------------------------------------------------- plumbing

  private fun setAlarmClock(atMs: Long, operation: PendingIntent) {
    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, openApp()), operation)
  }

  private fun ringIntent(ring: Ring): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      notificationId(ring),
      Intent(context, ClockReceiver::class.java).setAction(ACTION_RING).putExtras(ring.toExtras()),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  internal fun actionIntent(action: String, ring: Ring): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      notificationId(ring) * 10 + ACTIONS.indexOf(action) + 1,
      Intent(context, ClockReceiver::class.java).setAction(action).putExtras(ring.toExtras()),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun openApp(): PendingIntent =
    PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE,
    )

  companion object {
    const val ACTION_RING = "org.openandroidassistant.clock.RING"
    const val ACTION_CANCEL = "org.openandroidassistant.clock.CANCEL"
    const val ACTION_STOP = "org.openandroidassistant.clock.STOP"
    const val ACTION_SNOOZE = "org.openandroidassistant.clock.SNOOZE"
    const val ACTION_DONE = "org.openandroidassistant.clock.DONE"
    private val ACTIONS = listOf(ACTION_CANCEL, ACTION_STOP, ACTION_SNOOZE, ACTION_DONE)

    fun notificationId(ring: Ring): Int =
      when (ring) {
        is Ring.Timer -> 10_000 + ring.id.toInt()
        is Ring.Alarm -> 20_000 + ring.id.toInt()
        is Ring.Reminder -> 30_000 + ring.id.toInt()
      }
  }
}

/** What is going off. */
sealed interface Ring {
  val id: Long

  data class Timer(override val id: Long) : Ring

  data class Alarm(override val id: Long) : Ring

  data class Reminder(override val id: Long) : Ring

  fun toExtras(): android.os.Bundle =
    android.os.Bundle().apply {
      putString("kind", this@Ring::class.simpleName)
      putLong("id", id)
    }

  companion object {
    fun from(intent: Intent): Ring? {
      val id = intent.getLongExtra("id", -1).takeIf { it >= 0 } ?: return null
      return when (intent.getStringExtra("kind")) {
        "Timer" -> Timer(id)
        "Alarm" -> Alarm(id)
        "Reminder" -> Reminder(id)
        else -> null
      }
    }
  }
}

/** The next time an alarm at [AlarmEntity.hour]:[AlarmEntity.minute] rings after now. */
fun nextOccurrence(alarm: AlarmEntity, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Long {
  val time = LocalTime.of(alarm.hour, alarm.minute)
  var day: LocalDate = now.toLocalDate()
  repeat(8) {
    val candidate = day.atTime(time)
    val dayOk = alarm.daysMask == 0 || (alarm.daysMask and (1 shl (day.dayOfWeek.value - 1))) != 0
    if (candidate.isAfter(now) && dayOk) return candidate.atZone(zone).toInstant().toEpochMilli()
    day = day.plusDays(1)
  }
  return day.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

/** "weekdays", "every day", "Mon, Wed", or null for a one-time alarm. */
fun daysLabel(mask: Int): String? =
  when (mask) {
    0 -> null
    0b1111111 -> "every day"
    0b0011111 -> "weekdays"
    0b1100000 -> "weekends"
    else ->
      DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.joinToString(", ") {
        it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
      }
  }
