/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Alarm-manager wakeups and notification buttons for timers, alarms and reminders. */
@AndroidEntryPoint
class ClockReceiver : BroadcastReceiver() {
  @Inject lateinit var clock: ClockManager

  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        handle(context, intent)
      } finally {
        pending.finish()
      }
    }
  }

  private suspend fun handle(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED -> clock.rescheduleAll()
      ClockManager.ACTION_RING ->
        when (val ring = Ring.from(intent)) {
          is Ring.Timer -> {
            val timer = clock.dao.timer(ring.id) ?: return
            clock.dao.updateTimer(timer.copy(ringing = true))
            RingingService.start(context, ring)
          }
          is Ring.Alarm -> {
            clock.dao.alarm(ring.id) ?: return
            RingingService.start(context, ring)
            clock.afterAlarmRang(ring.id)
          }
          is Ring.Reminder -> clock.dao.reminder(ring.id)?.let(clock::showReminder)
          null -> Unit
        }
      ClockManager.ACTION_CANCEL,
      ClockManager.ACTION_STOP ->
        when (val ring = Ring.from(intent)) {
          is Ring.Timer -> clock.dao.timer(ring.id)?.let { clock.deleteTimer(it) }
          is Ring.Alarm -> RingingService.stopFor(context, ring)
          else -> Unit
        }
      ClockManager.ACTION_SNOOZE ->
        (Ring.from(intent) as? Ring.Alarm)?.let { ring ->
          RingingService.stopFor(context, ring)
          clock.snoozeAlarm(ring.id)
        }
      ClockManager.ACTION_DONE ->
        (Ring.from(intent) as? Ring.Reminder)?.let { ring ->
          clock.dao.reminder(ring.id)?.let { clock.saveReminder(it.copy(done = true)) }
          context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(ClockManager.notificationId(ring))
        }
    }
  }
}
