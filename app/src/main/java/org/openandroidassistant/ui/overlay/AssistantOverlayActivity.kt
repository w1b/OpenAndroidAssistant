/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.overlay

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.openandroidassistant.BuildConfig
import org.openandroidassistant.orchestrator.ConversationController
import org.openandroidassistant.orchestrator.TriggerSource
import org.openandroidassistant.ui.theme.OaaTheme

/**
 * The assistant overlay: a translucent activity above the current screen (and the keyguard).
 * It opens on a trigger and finishes when the overlay hides.
 */
@AndroidEntryPoint
class AssistantOverlayActivity : ComponentActivity() {

  @Inject lateinit var controller: ConversationController

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    if (savedInstanceState == null) handleIntent(intent)

    setContent { OaaTheme { OverlayScreen(controller = controller, onDismiss = controller::onOverlayDismissed) } }

    lifecycleScope.launch {
      var wasVisible = false
      controller.uiState.collectLatest { state ->
        if (state.visible) wasVisible = true else if (wasVisible && !isFinishing) finish()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    controller.biometricPrompt = ::authenticate
  }

  override fun onPause() {
    if (controller.biometricPrompt == ::authenticate) controller.biometricPrompt = null
    super.onPause()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent) {
    val source = runCatching { TriggerSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: "") }.getOrDefault(TriggerSource.IN_APP)
    val debugText = intent.getStringExtra(EXTRA_DEBUG_TEXT)
    val debugAudio = intent.getStringExtra(EXTRA_DEBUG_AUDIO_PATH)
    val preview = intent.getStringExtra(EXTRA_DEBUG_PREVIEW)
    when {
      BuildConfig.DEBUG && !preview.isNullOrBlank() -> controller.debugPreview(preview)
      BuildConfig.DEBUG && !debugAudio.isNullOrBlank() -> controller.onDebugAudio(debugAudio, startTurn = intent.getBooleanExtra(EXTRA_DEBUG_NEW_TURN, true))
      BuildConfig.DEBUG && !debugText.isNullOrBlank() -> controller.onDebugText(debugText)
      else -> controller.onTrigger(source)
    }
  }

  /** Tap plus biometric (or screen lock) for Critical approvals. */
  private suspend fun authenticate(title: String): Boolean =
    suspendCancellableCoroutine { cont ->
      val signal = CancellationSignal()
      cont.invokeOnCancellation { signal.cancel() }
      BiometricPrompt.Builder(this)
        .setTitle(title)
        .setSubtitle("Confirm it's you")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()
        .authenticate(
          signal,
          mainExecutor,
          object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
              if (cont.isActive) cont.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
              if (cont.isActive) cont.resume(false)
            }
          },
        )
    }

  companion object {
    private const val EXTRA_SOURCE = "trigger_source"
    const val EXTRA_DEBUG_TEXT = "debug_text"
    const val EXTRA_DEBUG_AUDIO_PATH = "debug_audio_path"
    const val EXTRA_DEBUG_PREVIEW = "debug_preview"
    /** false: feed the audio to whatever is listening (an approval or a follow-up). */
    const val EXTRA_DEBUG_NEW_TURN = "debug_new_turn"

    fun intent(context: Context, source: TriggerSource): Intent =
      Intent(context, AssistantOverlayActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_SOURCE, source.name)

    fun launch(context: Context, source: TriggerSource) {
      context.startActivity(intent(context, source))
    }
  }
}
