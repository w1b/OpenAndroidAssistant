/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatchTest {
  private val apps =
    listOf(
      AppEntry("Phone", "com.android.dialer"),
      AppEntry("Phone Link", "com.microsoft.appmanager"),
      AppEntry("Molly", "im.molly.app"),
      AppEntry("Google Maps", "com.google.android.apps.maps"),
      AppEntry("Maps", "app.organicmaps"),
      AppEntry("Signal", "org.thoughtcrime.securesms"),
      AppEntry("Signal", "org.thoughtcrime.securesms.beta"),
      AppEntry("Vanadium", "app.vanadium.browser"),
    )

  @Test
  fun `the same name comes first`() {
    assertEquals(apps[0], rankApps(apps, "phone").first())
    assertEquals(apps[4], rankApps(apps, "Maps").first())
    assertEquals(apps[3], rankApps(apps, "google maps").first())
  }

  @Test
  fun `a shared word or part of the name finds the app`() {
    assertTrue(apps[3] in rankApps(apps, "maps"))
    assertTrue(apps[1] in rankApps(apps, "link"))
  }

  @Test
  fun `a word of the package finds the app`() {
    assertEquals(listOf(apps[7]), rankApps(apps, "browser"))
  }

  @Test
  fun `two apps with the same name are both found`() {
    assertEquals(listOf(apps[5], apps[6]), rankApps(apps, "Signal"))
  }

  @Test
  fun `a misheard name finds the close spelling, and nothing finds nothing`() {
    assertEquals(apps[2], rankApps(apps, "Mollie").first())
    assertEquals(emptyList<AppEntry>(), rankApps(apps, "Spotify"))
  }
}
