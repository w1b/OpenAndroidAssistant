/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.openandroidassistant.orchestrator.TriggerSource
import org.openandroidassistant.ui.OaaApp
import org.openandroidassistant.ui.overlay.AssistantOverlayActivity
import org.openandroidassistant.ui.theme.OaaTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    handleDebugIntent(intent)

    enableEdgeToEdge()
    setContent { OaaTheme { OaaApp() } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleDebugIntent(intent)
  }

  /** Debug builds: `am start -n …/.MainActivity --es debug_text "…"` runs a typed turn. */
  private fun handleDebugIntent(intent: Intent) {
    if (!BuildConfig.DEBUG) return
    val text = intent.getStringExtra(AssistantOverlayActivity.EXTRA_DEBUG_TEXT)
    val audio = intent.getStringExtra(AssistantOverlayActivity.EXTRA_DEBUG_AUDIO_PATH)
    if (text.isNullOrBlank() && audio.isNullOrBlank()) return
    val overlay = AssistantOverlayActivity.intent(this, TriggerSource.IN_APP)
    overlay.putExtras(intent)
    startActivity(overlay)
  }
}
