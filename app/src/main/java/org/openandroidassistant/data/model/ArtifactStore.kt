/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves artifact locations on disk and answers "is it installed?". */
@Singleton
class ArtifactStore @Inject constructor(@ApplicationContext private val context: Context) {

  // Artifacts live in *internal* storage (ext4), not getExternalFilesDir. On FUSE-backed
  // external storage, LiteRT-LM's ParallelFileSectionLoading mmap hangs during engine init on
  // some devices (observed on Pixel 8a / GrapheneOS). Internal storage is ext4 and loads
  // reliably; the ~2.6 GB cost is acceptable.
  fun artifactDir(artifact: Artifact): File = File(context.filesDir, "artifacts/${artifact.id}")

  /** The path consumers load: the model file itself, or the unzipped directory for zips. */
  fun installedPath(artifact: Artifact): File =
    if (artifact.parts.isNotEmpty() || artifact.archiveType != ArchiveType.NONE) File(artifactDir(artifact), artifact.extractDir)
    else File(artifactDir(artifact), artifact.fileName)

  fun isInstalled(artifact: Artifact): Boolean {
    val path = installedPath(artifact)
    if (artifact.parts.isNotEmpty()) return artifact.parts.all { path.resolve(it.fileName).length() == it.sizeBytes }
    return if (artifact.archiveType != ArchiveType.NONE) {
      path.isDirectory &&
        if (artifact.requiredFiles.isEmpty()) {
          path.listFiles()?.isNotEmpty() ?: false
        } else {
          artifact.requiredFiles.all { path.resolve(it).isFile }
        }
    } else {
      path.isFile && path.length() == artifact.sizeBytes
    }
  }

  fun delete(artifact: Artifact) {
    artifactDir(artifact).deleteRecursively()
  }
}
