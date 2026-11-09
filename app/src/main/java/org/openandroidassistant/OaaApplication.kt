/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class OaaApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) {
      // Keeps the latest model requests for inspection with adb.
      val dir = File(cacheDir, "requests").apply { mkdirs() }
      org.openandroidassistant.model.debugDump = { name, content ->
        runCatching {
          File(dir, "$name-${System.currentTimeMillis()}.json").writeText(content)
          dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
        }
      }
    }
  }
}
