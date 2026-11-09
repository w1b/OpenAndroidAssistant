/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openandroidassistant.tools.HandleType

class HandleTableTest {

  @Test
  fun mintsTypePrefixedSequentialHandles() {
    val table = HandleTable()
    assertEquals("m1", table.mint(HandleType.MESSAGE, "row-1").handle)
    assertEquals("m2", table.mint(HandleType.MESSAGE, "row-2").handle)
    assertEquals("c1", table.mint(HandleType.CONTACT, "uri-1").handle)
  }

  @Test
  fun reMintingSameIdentityReusesHandle() {
    val table = HandleTable()
    val first = table.mint(HandleType.MESSAGE, "row-1")
    val again = table.mint(HandleType.MESSAGE, "row-1")
    assertEquals(first.handle, again.handle)
    assertEquals(1, table.issued().size)
  }

  @Test
  fun reMintingRefreshesPayload() {
    val table = HandleTable()
    table.mint(HandleType.MESSAGE, "row-1", payload = "old")
    val refreshed = table.mint(HandleType.MESSAGE, "row-1", payload = "new")
    assertEquals("new", refreshed.payload)
    assertEquals("new", table.resolve(refreshed.handle)?.payload)
  }

  @Test
  fun fabricatedHandleResolvesToNull() {
    val table = HandleTable()
    table.mint(HandleType.MESSAGE, "row-1")
    // Fail closed: a plausible-but-unissued handle resolves to nothing.
    assertNull(table.resolve("m99"))
    assertNull(table.resolve("c1"))
  }

  @Test
  fun resolveReturnsUnderlyingNativeId() {
    val table = HandleTable()
    val entry = table.mint(HandleType.EVENT, "event-42")
    assertEquals("event-42", table.resolve(entry.handle)?.nativeId)
    assertSame(HandleType.EVENT, table.resolve(entry.handle)?.type)
  }

  @Test
  fun issuedTracksEveryHandle() {
    val table = HandleTable()
    table.mint(HandleType.MESSAGE, "a")
    table.mint(HandleType.CONTACT, "b")
    assertTrue(table.issued().containsAll(listOf("m1", "c1")))
  }
}
