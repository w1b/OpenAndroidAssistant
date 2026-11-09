/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openandroidassistant.MainActivity
import org.openandroidassistant.R
import org.openandroidassistant.data.model.ArchiveExtractor
import org.openandroidassistant.data.model.ArchiveType

/** Input, progress and output keys of [ArtifactDownloadWorker]. */
object DownloadKeys {
  // Input
  const val URL = "url"
  const val DISPLAY_NAME = "display_name"
  const val ARTIFACT_DIR = "artifact_dir"
  const val FILE_NAME = "file_name"
  const val TOTAL_BYTES = "total_bytes"
  const val ARCHIVE_TYPE = "archive_type"
  const val EXTRACT_DIR = "extract_dir"
  const val ARCHIVE_PREFIX = "archive_prefix"
  const val SHA256 = "sha256"
  const val PART_URLS = "part_urls"
  const val PART_NAMES = "part_names"
  const val PART_SIZES = "part_sizes"

  // Progress
  const val RECEIVED_BYTES = "received_bytes"
  const val RATE_BYTES_PER_SEC = "rate_bps"
  const val REMAINING_MS = "remaining_ms"
  const val UNZIPPING = "unzipping"
  const val INSTALLED_BYTES = "installed_bytes"
  const val INSTALL_TOTAL_BYTES = "install_total_bytes"

  // Output
  const val ERROR_MESSAGE = "error"
}

/**
 * Installs one artifact as a foreground job.
 *
 * An artifact is either a single file (optionally an archive, unpacked into its extract dir and
 * then removed) or a set of parts fetched straight into the extract dir. Every file lands as
 * `<name>.part` first and is renamed when complete, so an interrupted job picks up where it
 * stopped via an HTTP range request.
 */
class ArtifactDownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  /** One file to fetch: [size] is 0 when unknown, [sha256] empty when unchecked. */
  private class Transfer(val url: String, val target: File, val size: Long, val sha256: String = "")

  private val notifications = context.getSystemService(NotificationManager::class.java)
  private val notificationId = params.id.hashCode()
  private val title = "Downloading ${inputData.getString(DownloadKeys.DISPLAY_NAME) ?: "model"}"

  override suspend fun doWork(): Result =
    withContext(Dispatchers.IO) {
      try {
        setForeground(foregroundInfo(percent = 0))
        install()
        Result.success()
      } catch (e: IOException) {
        Log.e(TAG, "Artifact install failed", e)
        Result.failure(workDataOf(DownloadKeys.ERROR_MESSAGE to (e.message ?: "Download failed")))
      }
    }

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(percent = 0)

  private suspend fun install() {
    val input = inputData
    val root = File(input.getString(DownloadKeys.ARTIFACT_DIR) ?: throw IOException("No artifact directory"))
    val extractDir = input.getString(DownloadKeys.EXTRACT_DIR).orEmpty()
    val partUrls = input.getStringArray(DownloadKeys.PART_URLS).orEmpty()

    if (partUrls.isNotEmpty()) {
      val names = input.getStringArray(DownloadKeys.PART_NAMES).orEmpty()
      val sizes = input.getLongArray(DownloadKeys.PART_SIZES) ?: LongArray(0)
      if (names.size != partUrls.size || sizes.size != partUrls.size) throw IOException("Malformed part list")
      val dir = File(root, extractDir)
      fetchAll(partUrls.indices.map { Transfer(partUrls[it], File(dir, names[it]), sizes[it]) })
      // Anything else under the artifact dir is left over from an older packaging.
      root.listFiles()?.filter { it.name != extractDir }?.forEach { it.deleteRecursively() }
      return
    }

    val file =
      File(root, input.getString(DownloadKeys.FILE_NAME) ?: throw IOException("No file name"))
    val url = input.getString(DownloadKeys.URL) ?: throw IOException("No download URL")
    fetchAll(
      listOf(
        Transfer(
          url,
          file,
          input.getLong(DownloadKeys.TOTAL_BYTES, 0L),
          input.getString(DownloadKeys.SHA256).orEmpty().lowercase(),
        )
      )
    )

    val archiveType =
      ArchiveType.entries.firstOrNull { it.name == input.getString(DownloadKeys.ARCHIVE_TYPE) }
        ?: ArchiveType.NONE
    if (archiveType == ArchiveType.NONE || extractDir.isEmpty()) return
    setProgress(workDataOf(DownloadKeys.UNZIPPING to true))
    ArchiveExtractor.extract(
      archive = file,
      type = archiveType,
      destination = File(root, extractDir),
      archivePrefix = input.getString(DownloadKeys.ARCHIVE_PREFIX).orEmpty(),
    ) { done, total ->
      setProgress(
        workDataOf(
          DownloadKeys.UNZIPPING to true,
          DownloadKeys.INSTALLED_BYTES to done,
          DownloadKeys.INSTALL_TOTAL_BYTES to total,
        )
      )
    }
    file.delete()
  }

  /** Fetches each transfer not already in place, reporting progress across the whole set. */
  private suspend fun fetchAll(transfers: List<Transfer>) {
    val meter = Meter(total = transfers.sumOf { it.size })
    for (t in transfers) {
      if (t.size > 0 && t.target.length() == t.size && t.sha256.isEmpty()) {
        meter.skip(t.size)
        continue
      }
      t.target.parentFile?.mkdirs()
      val partial = File(t.target.path + ".part")
      val length = fetch(t, partial, meter)
      if (t.size > 0 && length != t.size) {
        throw IOException("${t.target.name}: expected ${t.size} bytes, got $length")
      }
      if (t.sha256.isNotEmpty() && sha256Of(partial) != t.sha256) {
        partial.delete() // A corrupt complete file can't be repaired by resuming.
        throw IOException("${t.target.name} failed its SHA-256 check")
      }
      t.target.delete()
      if (!partial.renameTo(t.target)) throw IOException("Could not move ${partial.name} into place")
    }
    Log.d(TAG, "Fetched ${transfers.size} file(s), ${meter.total} bytes")
  }

  /** Appends the rest of [t] to [partial] and returns the partial file's final length. */
  private suspend fun fetch(t: Transfer, partial: File, meter: Meter): Long {
    var have = partial.length()
    if (t.size in 1 until have) {
      partial.delete()
      have = 0
    }
    if (t.size > 0 && have == t.size) {
      meter.skip(have)
      return have
    }

    val conn = URL(t.url).openConnection() as HttpURLConnection
    try {
      // Byte offsets only line up with the file when the body isn't transfer-compressed.
      conn.setRequestProperty("Accept-Encoding", "identity")
      if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
      when (val code = conn.responseCode) {
        HttpURLConnection.HTTP_PARTIAL -> Log.d(TAG, "Resuming ${t.target.name} at $have bytes")
        HttpURLConnection.HTTP_OK -> have = 0 // No range support: start from scratch.
        else -> throw IOException("${t.target.name}: server returned HTTP $code")
      }
      meter.skip(have)
      conn.inputStream.use { body ->
        FileOutputStream(partial, /* append= */ have > 0).use { out ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = body.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            have += n
            meter.add(n.toLong())
          }
        }
      }
    } finally {
      conn.disconnect()
    }
    return have
  }

  /** Byte counter that publishes progress, rate and ETA at most every [INTERVAL_MS]. */
  private inner class Meter(val total: Long) {
    private var received = 0L
    private var windowStartMs = SystemClock.elapsedRealtime()
    private var windowBytes = 0L
    private var bytesPerSec = 0.0

    fun skip(bytes: Long) {
      received += bytes
    }

    suspend fun add(bytes: Long) {
      currentCoroutineContext().ensureActive()
      received += bytes
      windowBytes += bytes
      val now = SystemClock.elapsedRealtime()
      val elapsed = now - windowStartMs
      if (elapsed < INTERVAL_MS) return
      val sample = windowBytes * 1000.0 / elapsed
      bytesPerSec = if (bytesPerSec == 0.0) sample else bytesPerSec * 0.7 + sample * 0.3
      windowStartMs = now
      windowBytes = 0

      val remainingMs =
        if (total > 0 && bytesPerSec > 0) ((total - received).coerceAtLeast(0) * 1000 / bytesPerSec).toLong()
        else 0L
      setProgress(
        workDataOf(
          DownloadKeys.RECEIVED_BYTES to received,
          DownloadKeys.RATE_BYTES_PER_SEC to bytesPerSec.toLong(),
          DownloadKeys.REMAINING_MS to remainingMs,
        )
      )
      if (total > 0) setForeground(foregroundInfo((received * 100 / total).toInt()))
    }
  }

  private fun foregroundInfo(percent: Int): ForegroundInfo {
    notifications.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
    )
    val open =
      PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText("$percent%")
        .setProgress(100, percent.coerceIn(0, 100), false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(open)
        .build()
    return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }

  private companion object {
    const val TAG = "OaaDownload"
    const val CHANNEL_ID = "artifact_download_channel"
    const val INTERVAL_MS = 250L

    fun sha256Of(file: File): String {
      val md = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          md.update(buf, 0, n)
        }
      }
      return md.digest().joinToString("") { "%02x".format(it) }
    }
  }
}
