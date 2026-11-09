/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import java.util.Locale
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

private const val MAX_RESULTS = 10
private const val BODY_MAX = 600
private const val SCAN_LIMIT = 2_000

/** Search SMS conversations, newest first: received, sent, or both. */
class SmsReadTool @Inject constructor() : BaseTool() {

  override val schema =
    ToolSchema(
      name = "read_messages",
      description =
        "Search text messages (SMS), newest first. Filter by contact, by words in the message, " +
          "by direction, and by how many days back. Includes messages the user sent.",
      params =
        listOf(
          ToolParam(
            name = "contact",
            type = ParamKind.HANDLE,
            description = "Only messages with this contact (id from find_contact).",
            required = false,
            handleType = HandleType.CONTACT,
          ),
          ToolParam(
            name = "query",
            type = ParamKind.STRING,
            description = "Only messages whose text contains this word or phrase. For messages with a person, pass contact instead.",
            required = false,
          ),
          ToolParam(
            name = "direction",
            type = ParamKind.ENUM,
            description = "received, sent, or any (default).",
            required = false,
            enumValues = listOf("received", "sent", "any"),
          ),
          ToolParam(
            name = "days",
            type = ParamKind.INTEGER,
            description = "How many days back to look. Omit for any time.",
            required = false,
            minimum = 1.0,
            maximum = 3650.0,
          ),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "read_messages",
      lockedRisk = LockedRisk.READS_PRIVATE,
      title = "Read text messages",
      summary = "Search received and sent SMS by person, words, or date.",
      category = ToolCategory.COMMUNICATION,
      requiredCapabilities = listOf(AppCapability.READ_MESSAGES),
      optionalCapabilities = listOf(AppCapability.CONTACTS),
      progressPhrase = "Checking your messages.",
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome =
    withContext(Dispatchers.IO) {
      val contactEntry = call.handleArg("contact")
      val contact = contactEntry?.payload as? RenderCard.Contact
      val numbers = contactEntry?.let { contactNumbers(context.appContext, it.nativeId) }.orEmpty()
      if (contactEntry != null && numbers.isEmpty()) {
        return@withContext ToolOutcome.Steering("error", "That contact has no phone number, so there are no texts to search.")
      }
      val query = call.stringArg("query")?.lowercase()
      val direction = call.stringArg("direction") ?: "any"
      val since = call.longArg("days")?.let { System.currentTimeMillis() - it * 86_400_000L }
      val items = search(context.appContext, numbers, contact?.name, query, direction, since)
      if (items.isEmpty()) {
        ToolOutcome.Steering("empty", "No messages matched. Tell the user, or try a broader search.")
      } else {
        ToolOutcome.Success(items = items, hasMore = items.size == MAX_RESULTS)
      }
    }

  private fun search(
    context: Context,
    numbers: List<String>,
    contactName: String?,
    query: String?,
    direction: String,
    since: Long?,
  ): List<ResultItem> {
    val selection = buildList {
      when (direction) {
        "received" -> add("${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}")
        "sent" -> add("${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_SENT}")
        else ->
          add("${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})")
      }
      if (since != null) add("${Telephony.Sms.DATE} >= $since")
    }.joinToString(" AND ")
    val country = Locale.getDefault().country
    val items = ArrayList<ResultItem>()
    val names = HashMap<String, String>()
    context.contentResolver
      .query(
        Telephony.Sms.CONTENT_URI,
        arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
        selection,
        null,
        "${Telephony.Sms.DATE} DESC LIMIT $SCAN_LIMIT",
      )
      ?.use { c ->
        while (c.moveToNext() && items.size < MAX_RESULTS) {
          val address = c.getString(1).orEmpty()
          val body = c.getString(2).orEmpty()
          if (numbers.isNotEmpty() && numbers.none { PhoneNumberUtils.areSamePhoneNumber(it, address, country) }) continue
          if (query != null && !body.lowercase().contains(query)) continue
          val sent = c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_SENT
          val other = contactName ?: names.getOrPut(address) { lookupName(context, address) ?: address }
          val time = c.getLong(3)
          val timeLabel = formatWhen(time)
          val shown = if (body.length > BODY_MAX) body.take(BODY_MAX) + "…" else body
          items +=
            ResultItem(
              type = HandleType.MESSAGE,
              nativeId = c.getString(0),
              projection =
                mapOf(
                  (if (sent) "to" else "from") to other,
                  "direction" to if (sent) "sent" else "received",
                  "time" to timeLabel,
                  "body" to shown,
                ),
              renderPayload =
                RenderCard.Message(from = if (sent) "You → $other" else other, body = shown, timeLabel = timeLabel, outgoing = sent),
            )
        }
      }
    return items
  }

  private fun contactNumbers(context: Context, contactId: String): List<String> {
    val out = mutableListOf<String>()
    context.contentResolver
      .query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId),
        null,
      )
      ?.use { c -> while (c.moveToNext()) c.getString(0)?.let(out::add) }
    return out
  }

  private fun lookupName(context: Context, number: String): String? =
    runCatching {
        context.contentResolver
          .query(
            android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
          )
          ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
      }
      .getOrNull()
}
