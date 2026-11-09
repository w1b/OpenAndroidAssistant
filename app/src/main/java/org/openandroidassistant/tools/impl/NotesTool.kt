/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import javax.inject.Inject
import org.openandroidassistant.data.NoteEntity
import org.openandroidassistant.data.OaaDatabase
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.LockedRisk
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ResultItem
import org.openandroidassistant.tools.ToolCategory
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

private fun NoteEntity.item(autoShow: Boolean = false) =
  ResultItem(
    type = HandleType.NOTE,
    nativeId = id.toString(),
    projection = mapOf("title" to title, "body" to body, "updated" to formatWhen(updatedAtMs)),
    renderPayload = RenderCard.Note(title = title, body = body),
    autoShow = autoShow,
  )

/** Notes kept by the assistant itself. */
class CreateNoteTool @Inject constructor(db: OaaDatabase) : BaseTool() {
  private val dao = db.notesDao()

  override val schema =
    ToolSchema(
      name = "create_note",
      description = "Save a note. To add to an existing note, pass its id and the text to append.",
      params =
        listOf(
          ToolParam("body", ParamKind.STRING, "The note's text, or the text to append."),
          ToolParam("title", ParamKind.STRING, "Optional short title for a new note.", required = false),
          ToolParam("append_to", ParamKind.HANDLE, "A note id from find_notes to add to.", required = false, handleType = HandleType.NOTE),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "create_note",
      title = "Save notes",
      summary = "Write notes and add to them.",
      category = ToolCategory.PLANNING,
      cancellable = false,
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val body = call.stringArg("body") ?: return ToolOutcome.Steering("error", "What should the note say?")
    val now = System.currentTimeMillis()
    val existing = call.handleArg("append_to")?.nativeId?.toLongOrNull()?.let { dao.note(it) }
    val saved =
      if (existing != null) {
        existing.copy(body = existing.body.trimEnd() + "\n" + body, updatedAtMs = now).also { dao.update(it) }
      } else {
        val title = call.stringArg("title") ?: body.lineSequence().first().take(40)
        NoteEntity(title = title, body = body, createdAtMs = now, updatedAtMs = now).let { it.copy(id = dao.insert(it)) }
      }
    return ToolOutcome.Success(items = listOf(saved.item(autoShow = true)))
  }
}

class FindNotesTool @Inject constructor(db: OaaDatabase) : BaseTool() {
  private val dao = db.notesDao()

  override val schema =
    ToolSchema(
      name = "find_notes",
      description = "Search the user's notes by words in the title or text. Omit query for the most recent notes.",
      params = listOf(ToolParam("query", ParamKind.STRING, "Words to match.", required = false)),
    )

  override val metadata =
    ToolMetadata(
      id = "find_notes",
      lockedRisk = LockedRisk.READS_PRIVATE,
      title = "Find notes",
      summary = "Search your notes.",
      category = ToolCategory.PLANNING,
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val notes = dao.search(call.stringArg("query").orEmpty())
    return if (notes.isEmpty()) ToolOutcome.Steering("empty", "No notes matched. Tell the user.")
    else ToolOutcome.Success(items = notes.map { it.item() })
  }
}
