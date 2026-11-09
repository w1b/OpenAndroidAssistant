/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import java.util.Locale
import javax.inject.Inject
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.tools.ApprovalCard
import org.openandroidassistant.tools.ApprovalContent
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.Handoff
import org.openandroidassistant.tools.LockedRisk
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ToolCategory
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

/** Place a phone call to a contact, a place from find_places, or a number the user said. */
class CallTool @Inject constructor() : BaseTool() {

  override val schema =
    ToolSchema(
      name = "call",
      description =
        "Place a phone call. Give exactly one of: a contact id from find_contact, a place id from " +
          "find_places, or a phone number the user said.",
      params =
        listOf(
          ToolParam("contact", ParamKind.HANDLE, "Contact id.", required = false, handleType = HandleType.CONTACT),
          ToolParam("number_type", ParamKind.STRING, "For a contact with several numbers: mobile, home, work…", required = false),
          ToolParam("place", ParamKind.HANDLE, "Place id from find_places.", required = false, handleType = HandleType.PLACE),
          ToolParam("number", ParamKind.STRING, "A phone number the user said.", required = false),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "call",
      lockedRisk = LockedRisk.ACTS_AS_YOU,
      title = "Make phone calls",
      summary = "Call a contact, a place, or a number.",
      category = ToolCategory.COMMUNICATION,
      defaultApproval = ApprovalLevel.LOW,
      cancellable = false,
      requiredCapabilities = listOf(AppCapability.PHONE_CALLS),
      optionalCapabilities = listOf(AppCapability.CONTACTS),
      unlockVerb = "make that call",
      endsConversation = true,
    )

  private data class Target(val name: String, val number: String)

  private fun target(call: ValidatedCall, context: ToolContext): Target? {
    call.handleArg("contact")?.let { entry ->
      val card = entry.payload as? RenderCard.Contact ?: return null
      val wanted = call.stringArg("number_type")?.lowercase()
      val number =
        if (wanted != null) contactPhones(context, entry.nativeId).firstOrNull { it.first?.contains(wanted) == true }?.second
        else null
      return (number ?: card.phone)?.let { Target(card.name, it) }
    }
    call.handleArg("place")?.let { entry ->
      val place = (entry.payload as? PlacePayload)?.card ?: return null
      return place.phone?.let { Target(place.name, it) }
    }
    val number = call.stringArg("number")?.takeIf { it.count(Char::isDigit) >= 3 } ?: return null
    return Target(PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number, number)
  }

  private fun contactPhones(context: ToolContext, contactId: String): List<Pair<String?, String>> {
    val out = mutableListOf<Pair<String?, String>>()
    context.appContext.contentResolver
      .query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL), "${Phone.CONTACT_ID} = ?", arrayOf(contactId), null)
      ?.use { c ->
        while (c.moveToNext()) {
          val label = Phone.getTypeLabel(context.appContext.resources, c.getInt(1), c.getString(2))?.toString()?.lowercase()
          c.getString(0)?.let { out += label to it }
        }
      }
    return out
  }

  override fun approvalContent(call: ValidatedCall, context: ToolContext): ApprovalContent {
    val target = target(call, context)
    val name = target?.name ?: "this number"
    return ApprovalContent(
      spokenPrompt = "Call $name?",
      card = ApprovalCard(title = "Call", target = name, body = target?.number.orEmpty()),
    )
  }

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val target =
      target(call, context)
        ?: return ToolOutcome.Steering("error", "There's no phone number to call. Tell the user.")
    val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", target.number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return ToolOutcome.Success(
      items = emptyList(),
      extras = com.google.gson.JsonObject().apply { addProperty("calling", target.name) },
      handoff = Handoff(phrase = "Calling ${target.name}.", intent = intent),
    )
  }
}
