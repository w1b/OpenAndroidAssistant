/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * The app's Material 3 theme: the system's wallpaper-derived colors (minSdk 33 always has them)
 * in light or dark to match the system, with [OaaTypography].
 *
 * Status bar icon contrast is handled by `enableEdgeToEdge()` in each activity; this only stops
 * the system from painting a scrim behind the gesture/3-button navigation bar.
 */
@Composable
fun OaaTheme(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val dark = isSystemInDarkTheme()
  val colors =
    remember(context, dark) {
      if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

  val view = LocalView.current
  SideEffect { (view.context as? Activity)?.window?.isNavigationBarContrastEnforced = false }

  MaterialTheme(colorScheme = colors, typography = OaaTypography, content = content)
}
