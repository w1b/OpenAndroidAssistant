/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.model

/**
 * A downloadable artifact: the local model, speech recognition, the optional voice. Artifacts live
 * under internal storage `<filesDir>/artifacts/<id>/` (loading model weights over FUSE external
 * storage hangs engine init).
 */
enum class ArchiveType {
  NONE,
  ZIP,
  TAR_BZ2,
}

/** One file of a multi-file artifact, downloaded as is into the artifact's [Artifact.extractDir]. */
data class ArtifactPart(val url: String, val fileName: String, val sizeBytes: Long)

data class Artifact(
  /** Stable id; also the artifact's directory name. */
  val id: String,
  val displayName: String,
  val url: String,
  /** Downloaded file name inside the artifact dir (the zip name for zip artifacts). */
  val fileName: String,
  val sizeBytes: Long,
  val archiveType: ArchiveType = ArchiveType.NONE,
  /** For archives: the directory (relative to the artifact dir) the archive extracts to. */
  val extractDir: String = "",
  /** Optional directory inside an archive to extract; other entries are ignored. */
  val archivePrefix: String = "",
  /** Optional lowercase SHA-256 digest for integrity verification after download. */
  val sha256: String = "",
  /** Files that must exist below [extractDir] before an extracted artifact is considered ready. */
  val requiredFiles: List<String> = emptyList(),
  /**
   * Set for artifacts published as separate files: each is downloaded into [extractDir] and
   * nothing is unpacked. [url], [fileName] and [archiveType] are then unused.
   */
  val parts: List<ArtifactPart> = emptyList(),
)

object Artifacts {
  /**
   * Gemma 4 E2B instruction-tuned, LiteRT-LM format. Native audio input and native tool calling.
   * The shipping LiteRT-LM bundle exposes a 32K context (128K is the base model's
   * ceiling, not what this on-device build provides). URL pinned to the validated commit.
   */
  val LLM =
    Artifact(
      id = "gemma-4-e2b-it",
      displayName = "Gemma 4 E2B",
      url =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
      fileName = "gemma-4-E2B-it.litertlm",
      sizeBytes = 2_588_147_712L,
    )

  /**
   * Stable, full-precision KittenTTS Nano v0.8 model from sherpa-onnx's official arm64 Android
   * engine package. The APK is a ZIP container and installs dramatically faster on Android than
   * the release catalog's bzip2 archive; only its architecture-independent model assets are kept.
   */
  val KITTEN_TTS_NANO_EN =
    Artifact(
      id = "kitten-tts-nano-en-v0-8-fp32",
      displayName = "KittenTTS Nano English",
      url =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/" +
          "tts-engine-new/1.13.4/" +
          "sherpa-onnx-1.13.4-arm64-v8a-eng-tts-engine-kitten-nano-en-v0_8-fp32.apk",
      fileName = "kitten-nano-en-v0_8-fp32.apk",
      sizeBytes = 82_037_420L,
      archiveType = ArchiveType.ZIP,
      extractDir = "model",
      archivePrefix = "assets/kitten-nano-en-v0_8-fp32/",
      sha256 = "11bf7627512fcd68054698c3448455ef3745533d37941f2fddcbc21a9a6966fe",
      requiredFiles =
        listOf("model.fp32.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/phontab"),
    )

  /**
   * NVIDIA Parakeet TDT 0.6B v3 (int8) for sherpa-onnx: transcripts, voice approval, history.
   * Separate files from the sherpa-onnx author's Hugging Face mirror, pinned to a commit; the same
   * files as the GitHub release's .tar.bz2, which took many minutes to unpack on the phone.
   * CC-BY-4.0.
   */
  val SPEECH = parakeet()

  private fun parakeet(): Artifact {
    val base = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/"
    val parts =
      listOf(
        ArtifactPart(base + "encoder.int8.onnx", "encoder.int8.onnx", 652_184_281L),
        ArtifactPart(base + "decoder.int8.onnx", "decoder.int8.onnx", 11_845_275L),
        ArtifactPart(base + "joiner.int8.onnx", "joiner.int8.onnx", 6_355_277L),
        ArtifactPart(base + "tokens.txt", "tokens.txt", 93_939L),
      )
    return Artifact(
      id = "parakeet-tdt-0.6b-v3-int8",
      displayName = "Speech recognition",
      url = "",
      fileName = "",
      sizeBytes = parts.sumOf { it.sizeBytes },
      extractDir = "model",
      parts = parts,
    )
  }
}
