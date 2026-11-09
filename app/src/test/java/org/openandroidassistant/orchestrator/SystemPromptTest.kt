/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openandroidassistant.model.ModelKind
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.MessageWording
import org.openandroidassistant.proto.PhoneNumberSpeech

class SystemPromptTest {
  private val settings =
    AssistantSettings.newBuilder()
      .setAboutName("Ada")
      .setAboutNotes("My wife is Jane\n\nI'm vegetarian")
      .setMessageWording(MessageWording.WORDING_EXACT)
      .setPhoneNumbers(PhoneNumberSpeech.PHONE_NUMBERS_SHOW_ONLY)
      .build()

  @Test
  fun turnPromptCarriesTheTurnRulesAndPreferences() {
    val p = ConversationSpecBuilder.systemPrompt(settings, ModelKind.AUDIO_IN, nativeWebSearch = true)
    assertTrue(p.contains("wait_for_reply"))
    assertTrue(p.contains("hear their audio directly"))
    assertTrue(p.contains("send exactly the words"))
    assertTrue(p.contains("Here's their number."))
    assertTrue(p.contains("- Name: Ada"))
    assertTrue(p.contains("- My wife is Jane"))
    assertTrue(p.contains("# Web"))
  }

  @Test
  fun textOnlyModelsAreToldTheyGetATranscript() {
    val p = ConversationSpecBuilder.systemPrompt(settings, ModelKind.TEXT_ONLY, nativeWebSearch = false)
    assertTrue(p.contains("speech-to-text transcript"))
    assertFalse(p.contains("# Web"))
  }

  @Test
  fun realtimeBackendPromptDropsSpeechTurnRules() {
    val p = ConversationSpecBuilder.liveBackendInstructions(settings, nativeWebSearch = false)
    assertFalse(p.contains("wait_for_reply"))
    assertFalse(p.contains("# The user's input"))
    assertTrue(p.contains("# How you work"))
    assertTrue(p.contains("# What's on screen"))
    assertTrue(p.contains("Call get_time"))
    assertTrue(p.contains("- Name: Ada"))
  }

  @Test
  fun liveInstructionsUseTheDelegationToken() {
    val p = ConversationSpecBuilder.liveInstructions(settings)
    assertTrue(p.contains("Generate <|SpawnThinking|> when:"))
    assertTrue(p.contains("Do not generate <|SpawnThinking|> when:"))
  }
}
