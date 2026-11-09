/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.model

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.openandroidassistant.worker.ArtifactDownloadWorker
import org.openandroidassistant.worker.DownloadKeys

sealed interface DownloadState {
  data object NotInstalled : DownloadState

  data object Enqueued : DownloadState

  data class Downloading(
    val receivedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val remainingMs: Long,
  ) : DownloadState

  data class Installing(val completedBytes: Long, val totalBytes: Long) : DownloadState

  data object Installed : DownloadState

  data class Failed(val message: String) : DownloadState
}

/** Enqueues artifact downloads on WorkManager and exposes their state as flows. */
@Singleton
class DownloadRepository
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val artifactStore: ArtifactStore,
) {
  private val workManager: WorkManager
    get() = WorkManager.getInstance(context)

  fun enqueue(artifact: Artifact) {
    if (artifactStore.isInstalled(artifact)) return
    val request =
      OneTimeWorkRequestBuilder<ArtifactDownloadWorker>()
        .setInputData(
          Data.Builder()
            .putString(DownloadKeys.URL, artifact.url)
            .putString(DownloadKeys.DISPLAY_NAME, artifact.displayName)
            .putString(DownloadKeys.ARTIFACT_DIR, artifactStore.artifactDir(artifact).absolutePath)
            .putString(DownloadKeys.FILE_NAME, artifact.fileName)
            .putLong(DownloadKeys.TOTAL_BYTES, artifact.sizeBytes)
            .putString(DownloadKeys.ARCHIVE_TYPE, artifact.archiveType.name)
            .putString(DownloadKeys.EXTRACT_DIR, artifact.extractDir)
            .putString(DownloadKeys.ARCHIVE_PREFIX, artifact.archivePrefix)
            .putString(DownloadKeys.SHA256, artifact.sha256)
            .putStringArray(DownloadKeys.PART_URLS, artifact.parts.map { it.url }.toTypedArray())
            .putStringArray(DownloadKeys.PART_NAMES, artifact.parts.map { it.fileName }.toTypedArray())
            .putLongArray(DownloadKeys.PART_SIZES, artifact.parts.map { it.sizeBytes }.toLongArray())
            .build()
        )
        .addTag(tagFor(artifact))
        .build()
    workManager.enqueueUniqueWork(uniqueNameFor(artifact), ExistingWorkPolicy.KEEP, request)
  }

  fun cancel(artifact: Artifact) {
    workManager.cancelUniqueWork(uniqueNameFor(artifact))
  }

  /** Live state for an artifact, folding installed-on-disk state with WorkManager progress. */
  fun state(artifact: Artifact): Flow<DownloadState> =
    workManager.getWorkInfosForUniqueWorkFlow(uniqueNameFor(artifact)).map { infos ->
      val info = infos.maxByOrNull { it.generation }
      when {
        artifactStore.isInstalled(artifact) &&
          (info == null || info.state.isFinished) -> DownloadState.Installed
        info == null -> DownloadState.NotInstalled
        info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED ->
          DownloadState.Enqueued
        info.state == WorkInfo.State.RUNNING -> {
          if (info.progress.getBoolean(DownloadKeys.UNZIPPING, false)) {
            DownloadState.Installing(
              completedBytes = info.progress.getLong(DownloadKeys.INSTALLED_BYTES, 0L),
              totalBytes = info.progress.getLong(DownloadKeys.INSTALL_TOTAL_BYTES, 0L),
            )
          } else {
            DownloadState.Downloading(
              receivedBytes = info.progress.getLong(DownloadKeys.RECEIVED_BYTES, 0L),
              totalBytes = artifact.sizeBytes,
              bytesPerSecond = info.progress.getLong(DownloadKeys.RATE_BYTES_PER_SEC, 0L),
              remainingMs = info.progress.getLong(DownloadKeys.REMAINING_MS, 0L),
            )
          }
        }
        info.state == WorkInfo.State.FAILED ->
          DownloadState.Failed(
            info.outputData.getString(DownloadKeys.ERROR_MESSAGE) ?: "Download failed"
          )
        info.state == WorkInfo.State.CANCELLED -> DownloadState.NotInstalled
        else -> DownloadState.NotInstalled
      }
    }

  private fun uniqueNameFor(artifact: Artifact) = "download-${artifact.id}"

  private fun tagFor(artifact: Artifact) = "artifact:${artifact.id}"
}
