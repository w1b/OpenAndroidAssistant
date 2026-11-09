/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.openandroidassistant.R
import org.openandroidassistant.model.LitertChatModel

/**
 * Keeps the local model loaded as a foreground service. Best-effort: Android may still kill a
 * multi-GB process under memory pressure, so "Waking up" is normal UX. Started for
 * load-on-boot.
 */
@AndroidEntryPoint
class ModelResidencyService : Service() {

  @Inject lateinit var localModel: LitertChatModel
  @Inject lateinit var controller: org.openandroidassistant.orchestrator.ConversationController

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    scope.launch {
      runCatching {
        localModel.prepare()
        controller.warmLocalModel()
      }
    }
    return START_STICKY
  }

  private fun buildNotification(): Notification {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Assistant model", NotificationManager.IMPORTANCE_MIN)
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Assistant ready")
      .setContentText("The assistant model is loaded and ready.")
      .setSmallIcon(R.drawable.ic_notification)
      .setOngoing(true)
      .build()
  }

  override fun onDestroy() {
    scope.launch { runCatching { localModel.unload() } }
    super.onDestroy()
  }

  companion object {
    private const val NOTIFICATION_ID = 42
    private const val CHANNEL_ID = "model_residency"

    fun start(context: Context) {
      context.startForegroundService(Intent(context, ModelResidencyService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, ModelResidencyService::class.java))
    }
  }
}
