/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.clock

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openandroidassistant.R

/** Auto-silence after this long, like a clock app. */
private const val RING_TIMEOUT_MS = 10 * 60_000L

/** Plays the alarm sound for a timer or alarm that went off, with Stop and Snooze buttons. */
@AndroidEntryPoint
class RingingService : Service() {
  @Inject lateinit var clock: ClockManager

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var player: MediaPlayer? = null
  private var current: Ring? = null
  private var timeout: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val ring = intent?.let(Ring::from)
    if (intent?.action == ACTION_STOP_RING || ring == null) {
      if (ring == null || ring == current) stopRinging()
      return START_NOT_STICKY
    }
    current = ring
    scope.launch {
      val title =
        when (ring) {
          is Ring.Timer -> clock.dao.timer(ring.id)?.label?.ifBlank { null } ?: "Time's up"
          is Ring.Alarm -> clock.dao.alarm(ring.id)?.label?.ifBlank { null } ?: "Alarm"
          is Ring.Reminder -> "Reminder"
        }
      startForeground(ClockManager.notificationId(ring), notification(ring, title), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      startSound()
      timeout?.cancel()
      timeout = launch {
        delay(RING_TIMEOUT_MS)
        stopRinging()
      }
    }
    return START_NOT_STICKY
  }

  private fun notification(ring: Ring, title: String): Notification =
    NotificationCompat.Builder(this, CHANNEL_RINGING)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText(if (ring is Ring.Timer) "Timer" else "Alarm")
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setOngoing(true)
      .setFullScreenIntent(clock.actionIntent(ClockManager.ACTION_STOP, ring), true)
      .addAction(0, "Stop", clock.actionIntent(ClockManager.ACTION_STOP, ring))
      .apply { if (ring is Ring.Alarm) addAction(0, "Snooze", clock.actionIntent(ClockManager.ACTION_SNOOZE, ring)) }
      .build()

  private fun startSound() {
    player?.release()
    val uri =
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    player =
      runCatching {
          MediaPlayer().apply {
            setAudioAttributes(
              AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            setDataSource(this@RingingService, uri)
            isLooping = true
            prepare()
            start()
          }
        }
        .getOrNull()
    getSystemService(VibratorManager::class.java)
      ?.defaultVibrator
      ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
  }

  private fun stopRinging() {
    timeout?.cancel()
    player?.runCatching { stop() }
    player?.release()
    player = null
    getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
    val ring = current
    current = null
    if (ring is Ring.Timer) scope.launch { clock.dao.timer(ring.id)?.let { clock.deleteTimer(it) } }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    player?.release()
    super.onDestroy()
  }

  companion object {
    private const val ACTION_STOP_RING = "org.openandroidassistant.clock.STOP_RING"

    fun start(context: Context, ring: Ring) {
      context.startForegroundService(Intent(context, RingingService::class.java).putExtras(ring.toExtras()))
    }

    /** Stops [ring] if it's the one ringing. A no-op when nothing rings. */
    fun stopFor(context: Context, ring: Ring) {
      runCatching {
        context.startService(Intent(context, RingingService::class.java).setAction(ACTION_STOP_RING).putExtras(ring.toExtras()))
      }
    }
  }
}
