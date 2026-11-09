/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.trigger

import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.librewakeword.client.MicrophoneHandoff
import org.librewakeword.client.WakewordConsumerService
import org.librewakeword.client.WakewordEvent
import org.openandroidassistant.audio.AudioBus
import org.openandroidassistant.orchestrator.TriggerSource
import org.openandroidassistant.ui.overlay.AssistantOverlayActivity

/**
 * The LibreWakeWord handler. On a verified wake word it takes the microphone
 * handoff: the SDK's capture stream (the wake prefix, then live audio) becomes the mic for the
 * first turn, so nothing said right after the wake word is lost.
 */
@AndroidEntryPoint
class AssistantWakewordService : WakewordConsumerService() {
  @Inject lateinit var audioBus: AudioBus

  override fun onWakeword(event: WakewordEvent) {
    AssistantOverlayActivity.launch(applicationContext, TriggerSource.WAKE_WORD)
  }

  override fun onMicrophoneHandoff(event: WakewordEvent, handoff: MicrophoneHandoff) {
    try {
      val capture = handoff.openAudioCapture(this, includeWakeword = true)
      // Closing the stream (when the first turn ends) also closes the handoff.
      audioBus.attachHandoff(capture)
    } catch (e: Throwable) {
      Log.w(TAG, "Microphone handoff failed; listening on our own recorder", e)
      handoff.close()
    }
    AssistantOverlayActivity.launch(applicationContext, TriggerSource.WAKE_WORD)
  }

  companion object {
    private const val TAG = "AssistantWakeword"
  }
}
