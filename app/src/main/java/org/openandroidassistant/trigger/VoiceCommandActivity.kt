/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.trigger

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.openandroidassistant.orchestrator.TriggerSource
import org.openandroidassistant.ui.overlay.AssistantOverlayActivity

/**
 * Entry point for `android.intent.action.VOICE_COMMAND` and `ASSIST` (and the
 * LibreWakeWord "activity" integration path — LWW can launch an app's VOICE_COMMAND activity
 * without any SDK). It just forwards to the overlay and finishes.
 */
class VoiceCommandActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AssistantOverlayActivity.launch(this, TriggerSource.VOICE_COMMAND_INTENT)
    finish()
  }
}
