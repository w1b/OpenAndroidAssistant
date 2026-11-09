/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import org.openandroidassistant.tools.HandleType

/** One issued handle: session-scoped name → persistent native identity . */
data class HandleEntry(
  val handle: String,
  val type: HandleType,
  /** Persistent native identifier (SMS row id, contact URI, event id…). */
  val nativeId: String,
  /** Host-side payload for renderers and confirmation cards; never model-facing. */
  val payload: Any? = null,
)

/**
 * Session-scoped, type-prefixed short handles (`m3`, `c1`). A fabricated handle resolves to
 * nothing — fail closed. Model-facing text contains handles only.
 */
class HandleTable {
  private val counters = HashMap<HandleType, Int>()
  private val byHandle = LinkedHashMap<String, HandleEntry>()
  private val byIdentity = HashMap<Pair<HandleType, String>, HandleEntry>()

  /** Mints a handle, reusing the existing one if this object was already issued this session. */
  @Synchronized
  fun mint(type: HandleType, nativeId: String, payload: Any? = null): HandleEntry {
    byIdentity[type to nativeId]?.let { existing ->
      // Refresh the payload: later results may carry a fuller projection.
      val updated = if (payload != null) existing.copy(payload = payload) else existing
      byHandle[existing.handle] = updated
      byIdentity[type to nativeId] = updated
      return updated
    }
    val n = (counters[type] ?: 0) + 1
    counters[type] = n
    val entry = HandleEntry(handle = "${type.prefix}$n", type = type, nativeId = nativeId, payload = payload)
    byHandle[entry.handle] = entry
    byIdentity[type to nativeId] = entry
    return entry
  }

  @Synchronized fun resolve(handle: String): HandleEntry? = byHandle[handle]

  @Synchronized fun issued(): Set<String> = byHandle.keys.toSet()
}
