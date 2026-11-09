/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsToolTest {
  @Test
  fun spokenOrdinalTriesLikelyWrittenContactNameFirst() {
    assertEquals(
      listOf("1st avenue bakery", "first avenue bakery"),
      contactQueryVariants("first avenue bakery"),
    )
  }

  @Test
  fun ordinaryNameIsQueriedOnce() {
    assertEquals(listOf("Dana Whitfield"), contactQueryVariants("Dana Whitfield"))
  }
}
