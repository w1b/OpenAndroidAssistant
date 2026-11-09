/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openandroidassistant.data.model.DownloadState

/** True while a download is queued, running or being unpacked. */
val DownloadState.isActive: Boolean
  get() = this is DownloadState.Enqueued || this is DownloadState.Downloading || this is DownloadState.Installing

/** What a running download is doing: a label and a progress bar. Draws nothing otherwise. */
@Composable
fun DownloadProgress(state: DownloadState, horizontalPadding: Dp = 24.dp) {
  val (label, fraction) =
    when (state) {
      DownloadState.Enqueued -> "Waiting to start…" to null
      is DownloadState.Downloading ->
        "Downloading… ${state.receivedBytes / 1_000_000} of ${state.totalBytes / 1_000_000} MB" +
          (if (state.bytesPerSecond > 0) " · ${remaining(state.remainingMs)}" else "") to
          (if (state.totalBytes > 0) state.receivedBytes.toFloat() / state.totalBytes else null)
      is DownloadState.Installing ->
        "Getting it ready…" to (if (state.totalBytes > 0) state.completedBytes.toFloat() / state.totalBytes else null)
      else -> return
    }
  Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
  }
}

/** A settings row for a downloadable artifact: its state, progress, and the button to get it. */
@Composable
fun DownloadRow(state: DownloadState, downloadLabel: String, installedText: String, onDownload: () -> Unit) {
  when (state) {
    DownloadState.Installed -> Explanation(installedText)
    is DownloadState.Failed -> {
      Explanation("Download failed: ${state.message}")
      Button(onClick = onDownload, modifier = Modifier.padding(horizontal = 24.dp)) { Text("Try again") }
    }
    DownloadState.NotInstalled -> Button(onClick = onDownload, modifier = Modifier.padding(horizontal = 24.dp)) { Text(downloadLabel) }
    else -> DownloadProgress(state)
  }
}

private fun remaining(ms: Long): String {
  val s = ms / 1000
  return when {
    s < 60 -> "less than a minute left"
    s < 3600 -> "${s / 60} min left"
    else -> "${s / 3600} h ${s % 3600 / 60} min left"
  }
}
