/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Secure extraction for the model archive formats used by the artifact catalog. */
object ArchiveExtractor {

  suspend fun extract(
    archive: File,
    type: ArchiveType,
    destination: File,
    archivePrefix: String = "",
    onProgress: suspend (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
  ) {
    when (type) {
      ArchiveType.NONE -> error("Cannot extract a non-archive artifact")
      ArchiveType.ZIP -> unzip(archive, destination, archivePrefix, onProgress)
      ArchiveType.TAR_BZ2 -> untarBz2(archive, destination, onProgress)
    }
  }

  /** A .tar.bz2 whose files sit under one top-level directory, which is dropped. */
  private suspend fun untarBz2(archive: File, destination: File, onProgress: suspend (Long, Long) -> Unit) {
    val total = archive.length()
    prepareDestination(destination)
    val counting = org.apache.commons.io.input.BoundedInputStream.builder().setInputStream(java.io.BufferedInputStream(java.io.FileInputStream(archive))).get()
    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
        org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(counting)
      )
      .use { tar ->
        var lastReported = 0L
        while (true) {
          val entry = tar.nextEntry ?: break
          val relative = entry.name.substringAfter('/', "").ifEmpty { if (entry.isDirectory) "" else entry.name }
          if (relative.isEmpty()) continue
          val output = safeOutput(destination, relative)
          if (entry.isDirectory) {
            output.mkdirs()
            continue
          }
          output.parentFile?.mkdirs()
          FileOutputStream(output).use { tar.copyTo(it) }
          if (counting.count - lastReported >= 2 * 1024 * 1024) {
            onProgress(counting.count, total)
            lastReported = counting.count
          }
        }
      }
    onProgress(total, total)
  }

  private suspend fun unzip(
    archive: File,
    destination: File,
    requestedPrefix: String,
    onProgress: suspend (Long, Long) -> Unit,
  ) {
    ZipFile(archive).use { zip ->
      val allEntries = zip.entries().asSequence().toList()
      val prefix =
        requestedPrefix
          .trimStart('/')
          .let { if (it.isNotEmpty() && !it.endsWith('/')) "$it/" else it }
          .ifEmpty { rootPrefix(allEntries.firstOrNull()?.name.orEmpty()) }
      val entries =
        if (requestedPrefix.isEmpty()) allEntries
        else allEntries.filter { it.name.startsWith(prefix) }
      if (entries.isEmpty()) throw IOException("Archive does not contain $prefix")

      val totalBytes =
        entries.asSequence().filterNot { it.isDirectory }.sumOf { it.size.coerceAtLeast(0L) }
      var completedBytes = 0L
      var lastReportedBytes = 0L
      prepareDestination(destination)
      onProgress(0L, totalBytes)

      entries.forEach { entry ->
        val relative = entry.name.removePrefix(prefix)
        if (relative.isEmpty()) return@forEach
        val output = safeOutput(destination, relative)
        if (entry.isDirectory) {
          output.mkdirs()
        } else {
          output.parentFile?.mkdirs()
          zip.getInputStream(entry).use { input ->
            FileOutputStream(output).use { fileOutput ->
              val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
              while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                fileOutput.write(buffer, 0, count)
                completedBytes += count
                if (completedBytes - lastReportedBytes >= 2 * 1024 * 1024) {
                  onProgress(completedBytes, totalBytes)
                  lastReportedBytes = completedBytes
                }
              }
            }
          }
        }
      }
      onProgress(totalBytes, totalBytes)
    }
  }

  private fun prepareDestination(destination: File) {
    if (destination.exists()) destination.deleteRecursively()
    if (!destination.mkdirs() && !destination.isDirectory) {
      throw IOException("Unable to create ${destination.absolutePath}")
    }
  }

  private fun rootPrefix(firstEntry: String): String {
    val slash = firstEntry.indexOf('/')
    return if (slash >= 0) firstEntry.substring(0, slash + 1) else ""
  }

  private fun safeOutput(destination: File, relative: String): File {
    val canonicalDestination = destination.canonicalFile
    val output = File(destination, relative).canonicalFile
    if (!output.path.startsWith(canonicalDestination.path + File.separator)) {
      throw IOException("Illegal archive entry path: $relative")
    }
    return output
  }
}
