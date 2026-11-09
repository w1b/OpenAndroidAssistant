/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.trigger

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import org.openandroidassistant.orchestrator.TriggerSource
import org.openandroidassistant.ui.overlay.AssistantOverlayActivity

/**
 * Quick Settings tile: a manual trigger for the assistant (a manual trigger is
 * always available since OAA is not the default assistant and wake-word delivery is external).
 */
class AssistantTileService : TileService() {
  @SuppressLint("StartActivityAndCollapseDeprecated")
  override fun onClick() {
    super.onClick()
    val intent = AssistantOverlayActivity.intent(this, TriggerSource.QS_TILE)
    // From a tile, launch the overlay above the lock screen when needed.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startActivityAndCollapse(
        PendingIntent.getActivity(
          this,
          0,
          intent,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      )
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }
}
