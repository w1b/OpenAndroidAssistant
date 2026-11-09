/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.openandroidassistant.clock.ClockManager
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.runtime.ModelResidencyService

/** After a reboot: re-arms timers, alarms and reminders, and loads the local model if asked to. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

  @Inject lateinit var settingsRepository: SettingsRepository
  @Inject lateinit var clock: ClockManager

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
      try {
        clock.rescheduleAll()
        if (settingsRepository.current().loadModelOnBoot) {
          ModelResidencyService.start(context)
        }
      } finally {
        pending.finish()
      }
    }
  }
}
