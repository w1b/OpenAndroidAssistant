/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.model

import org.openandroidassistant.model.ModelKind

private const val HF = "https://huggingface.co"

/**
 * On-device models in LiteRT-LM format. Gemma hears audio directly; the others are text-only
 * and get a local transcript. URLs are pinned to validated commits.
 */
enum class LocalModel(val id: String, val displayName: String, val summary: String, val kind: ModelKind, val artifact: Artifact) {
  GEMMA_4_E2B(
    "gemma-4-e2b",
    "Gemma 4 E2B",
    "Understands audio natively. 2.6 GB",
    ModelKind.AUDIO_IN,
    Artifacts.LLM,
  ),
  GEMMA_4_E4B(
    "gemma-4-e4b",
    "Gemma 4 E4B",
    "Understands audio natively; smarter and slower. 3.7 GB",
    ModelKind.AUDIO_IN,
    Artifact(
      id = "gemma-4-e4b-it",
      displayName = "Gemma 4 E4B",
      url = "$HF/litert-community/gemma-4-E4B-it-litert-lm/resolve/e6a3892a8c22cf87ff99583996149cf3cb32452b/gemma-4-E4B-it.litertlm",
      fileName = "gemma-4-E4B-it.litertlm",
      sizeBytes = 3_659_530_240L,
    ),
  ),
  QWEN_3_5_0_8B(
    "qwen3.5-0.8b",
    "Qwen 3.5 0.8B",
    "Small and fast; uses speech transcription. 1 GB",
    ModelKind.TEXT_ONLY,
    Artifact(
      id = "qwen3.5-0.8b",
      displayName = "Qwen 3.5 0.8B",
      url = "$HF/litert-community/Qwen3.5-0.8B/resolve/0cd9e7a616d5f0d5c5cfab471dfc02b9ab1d2c17/Qwen3.5-0.8B_int8.litertlm",
      fileName = "Qwen3.5-0.8B_int8.litertlm",
      sizeBytes = 964_566_992L,
    ),
  ),
  QWEN_3_5_2B(
    "qwen3.5-2b",
    "Qwen 3.5 2B",
    "Uses speech transcription. 2.1 GB",
    ModelKind.TEXT_ONLY,
    Artifact(
      id = "qwen3.5-2b",
      displayName = "Qwen 3.5 2B",
      url = "$HF/litert-community/Qwen3.5-2B/resolve/4e5cdbd47c29043a1ff432d71b8668aaa9d200b0/Qwen3.5-2B_int8.litertlm",
      fileName = "Qwen3.5-2B_int8.litertlm",
      sizeBytes = 2_119_744_416L,
    ),
  ),
  GRANITE_4_2_3B(
    "granite-4.2-3b",
    "Granite 4.2 3B",
    "Uses speech transcription. 2.2 GB",
    ModelKind.TEXT_ONLY,
    Artifact(
      id = "granite-4.2-3b",
      displayName = "Granite 4.2 3B",
      url = "$HF/litert-community/granite-4.2-3b/resolve/31108eaab3f9625f5c8580837cf3952931979052/granite-4.2-3b_int4.litertlm",
      fileName = "granite-4.2-3b_int4.litertlm",
      sizeBytes = 2_190_708_656L,
    ),
  );

  companion object {
    fun byId(id: String): LocalModel = entries.firstOrNull { it.id == id } ?: GEMMA_4_E2B
  }
}
