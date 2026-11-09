/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class KittenTtsVoicesTest {

  @Test
  fun friendlyNamesMapToOfficialV08SpeakerOrder() {
    assertEquals(
      linkedMapOf(
        "Bella" to 1,
        "Jasper" to 0,
        "Luna" to 3,
        "Bruno" to 2,
        "Rosie" to 5,
        "Hugo" to 4,
        "Kiki" to 7,
        "Leo" to 6,
      ),
      KITTEN_TTS_VOICES,
    )
  }
}
