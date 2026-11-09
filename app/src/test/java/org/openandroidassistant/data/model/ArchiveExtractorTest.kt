/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.model

import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveExtractorTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun extractsZipAndStripsTopLevelDirectory() {
    val archive = temporaryFolder.newFile("model.zip")
    ZipOutputStream(FileOutputStream(archive)).use { output ->
      output.putNextEntry(ZipEntry("bundle/model.txt"))
      output.write("zip model".encodeToByteArray())
      output.closeEntry()
    }
    val destination = temporaryFolder.newFolder("zip-output")

    runBlocking { ArchiveExtractor.extract(archive, ArchiveType.ZIP, destination) }

    assertEquals("zip model", destination.resolve("model.txt").readText())
  }

  @Test
  fun extractsOnlyRequestedPrefixAndReportsProgress() {
    val archive = temporaryFolder.newFile("model.apk")
    ZipOutputStream(FileOutputStream(archive)).use { output ->
      output.putNextEntry(ZipEntry("unrelated/file.txt"))
      output.write("ignore me".encodeToByteArray())
      output.closeEntry()
      output.putNextEntry(ZipEntry("assets/kitten/model.onnx"))
      output.write("kitten model".encodeToByteArray())
      output.closeEntry()
    }
    val destination = temporaryFolder.newFolder("apk-output")
    val progress = mutableListOf<Pair<Long, Long>>()

    runBlocking {
      ArchiveExtractor.extract(
        archive = archive,
        type = ArchiveType.ZIP,
        destination = destination,
        archivePrefix = "assets/kitten/",
      ) { completed, total -> progress += completed to total }
    }

    assertEquals("kitten model", destination.resolve("model.onnx").readText())
    assertTrue(!destination.resolve("unrelated").exists())
    assertEquals(0L to 12L, progress.first())
    assertEquals(12L to 12L, progress.last())
  }

  @Test
  fun rejectsPathsOutsideDestination() {
    val archive = temporaryFolder.newFile("unsafe.zip")
    ZipOutputStream(FileOutputStream(archive)).use { output ->
      output.putNextEntry(ZipEntry("bundle/../../escaped.txt"))
      output.write("unsafe".encodeToByteArray())
      output.closeEntry()
    }
    val destination = temporaryFolder.newFolder("unsafe-output")

    val failure =
      runCatching {
          runBlocking { ArchiveExtractor.extract(archive, ArchiveType.ZIP, destination) }
        }.exceptionOrNull()

    assertTrue(failure is java.io.IOException)
    assertTrue(!destination.parentFile!!.resolve("escaped.txt").exists())
  }
}
