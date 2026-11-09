/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.openandroidassistant.R

private val Nunito =
  FontFamily(
    listOf(
        R.font.nunito_extralight to FontWeight.ExtraLight,
        R.font.nunito_light to FontWeight.Light,
        R.font.nunito_regular to FontWeight.Normal,
        R.font.nunito_medium to FontWeight.Medium,
        R.font.nunito_semibold to FontWeight.SemiBold,
        R.font.nunito_bold to FontWeight.Bold,
        R.font.nunito_extrabold to FontWeight.ExtraBold,
        R.font.nunito_black to FontWeight.Black,
      )
      .map { (res, weight) -> Font(res, weight) }
  )

/** Material 3's default type scale, set in Nunito. */
val OaaTypography: Typography =
  Typography().run {
    fun TextStyle.nunito() = copy(fontFamily = Nunito)
    Typography(
      displayLarge = displayLarge.nunito(),
      displayMedium = displayMedium.nunito(),
      displaySmall = displaySmall.nunito(),
      headlineLarge = headlineLarge.nunito(),
      headlineMedium = headlineMedium.nunito(),
      headlineSmall = headlineSmall.nunito(),
      titleLarge = titleLarge.nunito(),
      titleMedium = titleMedium.nunito(),
      titleSmall = titleSmall.nunito(),
      bodyLarge = bodyLarge.nunito(),
      bodyMedium = bodyMedium.nunito(),
      bodySmall = bodySmall.nunito(),
      labelLarge = labelLarge.nunito(),
      labelMedium = labelMedium.nunito(),
      labelSmall = labelSmall.nunito(),
    )
  }
