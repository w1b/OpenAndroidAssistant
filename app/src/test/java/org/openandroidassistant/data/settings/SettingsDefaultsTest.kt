/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.ModelProvider
import org.openandroidassistant.proto.TtsProviderType
import org.openandroidassistant.proto.WebSearchMode

class SettingsDefaultsTest {
  private val defaults = AssistantSettings.getDefaultInstance()

  @Test
  fun zeroValuesMeanTheDesignDefaults() {
    assertEquals(ModelProvider.PROVIDER_OPENROUTER, defaults.effectiveProvider)
    assertEquals("google/gemini-3.8-flash", defaults.effectiveOpenRouterModel)
    assertEquals("high", defaults.effectiveOpenRouterReasoning)
    assertEquals("gpt-6.1-sol", defaults.effectiveOpenAiModel)
    assertEquals("low", defaults.effectiveOpenAiReasoning)
    assertEquals(TtsProviderType.TTS_PROVIDER_ANDROID, defaults.effectiveTtsProvider)
    assertFalse(defaults.localFallbackEnabled)
    assertEquals(org.openandroidassistant.proto.HistoryMode.HISTORY_TEXT_AND_VOICE, defaults.history)
  }

  @Test
  fun defaultModelUsesItsNativeSearchAndHearsAudio() {
    assertTrue(defaults.openRouterAcceptsAudio)
    assertEquals(WebSearchMode.WEB_SEARCH_NATIVE, defaults.webSearchMode())
  }

  @Test
  fun nativeSearchIsNeverOfferedForAModelWithout() {
    val s =
      defaults.toBuilder()
        .setOpenrouterModel("meta/muse-spark-1.2")
        .setOpenrouterModelInfoCached(true)
        .setOpenrouterModelNativeSearch(false)
        .setOpenrouterWebSearch(WebSearchMode.WEB_SEARCH_NATIVE)
        .build()
    assertEquals(WebSearchMode.WEB_SEARCH_OFF, s.webSearchMode())
  }

  @Test
  fun searxngNeedsAnInstance() {
    val s = defaults.toBuilder().setLocalWebSearch(WebSearchMode.WEB_SEARCH_SEARXNG).setProvider(ModelProvider.PROVIDER_LOCAL).build()
    assertEquals(WebSearchMode.WEB_SEARCH_OFF, s.webSearchMode())
    assertEquals(WebSearchMode.WEB_SEARCH_SEARXNG, s.toBuilder().setSearxngUrl("https://s.example").build().webSearchMode())
  }

  @Test
  fun instancesAreHttpsUnlessOnThePhone() {
    assertEquals(null, instanceUrlProblem(""))
    assertEquals(null, instanceUrlProblem("https://search.example.org"))
    assertEquals(null, instanceUrlProblem("http://localhost:8888"))
    assertEquals(null, instanceUrlProblem("http://127.0.0.1:8888"))
    assertEquals("Use an https:// address.", instanceUrlProblem("http://192.168.1.10:8080"))
    assertEquals("Use an https:// address.", instanceUrlProblem("http://search.example.org"))
    assertEquals("Enter the full address, starting with https://", instanceUrlProblem("search.example.org"))
  }
}
