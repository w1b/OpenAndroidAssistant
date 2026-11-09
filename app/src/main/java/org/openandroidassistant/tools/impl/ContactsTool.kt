/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import com.google.gson.JsonObject
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.permissions.AppCapability
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

private const val MAX_CONTACTS = 8

/** Contact search. The provider ranks matches; the model acts on the returned ids. */
class ContactsTool @Inject constructor() : BaseTool() {

  override val schema =
    ToolSchema(
      name = "find_contact",
      description =
        "Find contacts by name, nickname or company. Returns ids plus phone numbers and " +
          "addresses. Use it before any call that needs a contact id.",
      params =
        listOf(
          ToolParam(
            name = "query",
            type = ParamKind.STRING,
            description = "A name, part of a name, or company to search for.",
          )
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "find_contact",
      lockedRisk = LockedRisk.READS_PRIVATE,
      title = "Find contacts",
      summary = "Look up people by name for calls, messages, and contact details.",
      category = ToolCategory.COMMUNICATION,
      requiredCapabilities = listOf(AppCapability.CONTACTS),
      progressPhrase = "Looking that up.",
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome =
    withContext(Dispatchers.IO) {
      val query = call.stringArg("query")
        ?: return@withContext ToolOutcome.Steering("error", "find_contact needs a name. Ask who they mean.")
      val items =
        contactQueryVariants(query).firstNotNullOfOrNull { candidate ->
          queryContacts(context.appContext, candidate).takeIf { it.isNotEmpty() }
        }.orEmpty()
      if (items.isEmpty()) {
        ToolOutcome.Steering("empty", "No contact matches \"$query\". Tell the user, or ask for a different name.")
      } else if (items.size > 1) {
        ToolOutcome.Success(items = items, extras = JsonObject().apply { addProperty("note", ambiguityNote(items.size)) })
      } else {
        ToolOutcome.Success(items = items)
      }
    }

  internal fun ambiguityNote(count: Int): String =
    "$count contacts match. If the user means one person, ask which one by name, show them, and call wait_for_reply."

  private fun queryContacts(context: Context, query: String): List<ResultItem> {
    val resolver = context.contentResolver
    val ids = LinkedHashMap<Long, Pair<String, String?>>()
    resolver
      .query(
        Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query)),
        arrayOf(
          ContactsContract.Contacts._ID,
          ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
          ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        ),
        null,
        null,
        "${ContactsContract.Contacts.STARRED} DESC",
      )
      ?.use { c ->
        while (c.moveToNext() && ids.size < MAX_CONTACTS) {
          val name = c.getString(1) ?: continue
          ids[c.getLong(0)] = name to c.getString(2)
        }
      }
    return ids.map { (id, nameAndPhoto) ->
      val (name, photo) = nameAndPhoto
      val phones = phones(context, id)
      val address = address(context, id)
      val primary = phones.firstOrNull()
      ResultItem(
        type = HandleType.CONTACT,
        nativeId = id.toString(),
        projection =
          mapOf(
            "name" to name,
            "phones" to phones.map { (label, number) -> if (label != null) "$label: $number" else number },
            "address" to address,
          ),
        renderPayload =
          RenderCard.Contact(
            name = name,
            phone = primary?.second,
            photoUri = photo,
            phoneLabel = primary?.first,
            address = address,
          ),
      )
    }
  }

  private fun phones(context: Context, contactId: Long): List<Pair<String?, String>> {
    val out = mutableListOf<Pair<String?, String>>()
    context.contentResolver
      .query(
        Phone.CONTENT_URI,
        arrayOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY),
        "${Phone.CONTACT_ID} = ?",
        arrayOf(contactId.toString()),
        "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
      )
      ?.use { c ->
        while (c.moveToNext()) {
          val number = c.getString(0) ?: continue
          val label =
            Phone.getTypeLabel(context.resources, c.getInt(1), c.getString(2))?.toString()?.lowercase()
          if (out.none { it.second.filter(Char::isDigit) == number.filter(Char::isDigit) }) out += label to number
        }
      }
    return out
  }

  private fun address(context: Context, contactId: Long): String? =
    context.contentResolver
      .query(
        StructuredPostal.CONTENT_URI,
        arrayOf(StructuredPostal.FORMATTED_ADDRESS),
        "${StructuredPostal.CONTACT_ID} = ?",
        arrayOf(contactId.toString()),
        null,
      )
      ?.use { c -> if (c.moveToFirst()) c.getString(0)?.replace('\n', ' ')?.trim() else null }
}

/**
 * Contact names are typed text while the query normally comes from speech. Android's provider
 * doesn't treat "first" and "1st" as equal, so try the likely written form, then the query as is.
 */
internal fun contactQueryVariants(query: String): List<String> {
  val ordinalAliases =
    linkedMapOf(
      "first" to "1st",
      "second" to "2nd",
      "third" to "3rd",
      "fourth" to "4th",
      "fifth" to "5th",
      "sixth" to "6th",
      "seventh" to "7th",
      "eighth" to "8th",
      "ninth" to "9th",
      "tenth" to "10th",
    )
  var written = query
  ordinalAliases.forEach { (spoken, typed) ->
    written = written.replace(Regex("""(?i)\b${Regex.escape(spoken)}\b"""), typed)
  }
  return listOf(written, query).distinct()
}
