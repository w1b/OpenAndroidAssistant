/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Invisible above-keyguard activity that drives `requestDismissKeyguard` for lock-gated tool
 * calls. It exists only long enough to get an unlock verdict.
 */
@AndroidEntryPoint
class KeyguardGateActivity : ComponentActivity() {

  @Inject lateinit var controller: ConversationController

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    keyguard.requestDismissKeyguard(
      this,
      object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() {
          controller.onUnlockResult(true)
          finish()
        }

        override fun onDismissCancelled() {
          controller.onUnlockResult(false)
          finish()
        }

        override fun onDismissError() {
          controller.onUnlockResult(false)
          finish()
        }
      },
    )
  }

  companion object {
    fun launch(context: Context) {
      context.startActivity(
        Intent(context, KeyguardGateActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
      )
    }
  }
}
