/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.overlay

import org.openandroidassistant.orchestrator.HandleEntry
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.tools.impl.PlacePayload

/** Maps a result's app-side payload to the card the overlay draws. Unknown payloads draw nothing. */
object RendererRegistry {
  fun render(entry: HandleEntry): RenderCard? = render(entry.payload)

  fun render(payload: Any?): RenderCard? =
    when (payload) {
      is RenderCard -> payload
      is PlacePayload -> payload.card
      else -> null
    }
}
