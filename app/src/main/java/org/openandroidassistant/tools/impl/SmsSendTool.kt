/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.tools.ApprovalCard
import org.openandroidassistant.tools.ApprovalContent
import org.openandroidassistant.tools.ApprovalLevel
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

/** Send an SMS to a contact (by id) or to a number the user said. */
class SmsSendTool @Inject constructor() : BaseTool() {

  override val schema =
    ToolSchema(
      name = "send_message",
      description =
        "Send a text message (SMS). Give either a contact id from find_contact or a phone " +
          "number the user said out loud. The app asks the user to approve it first when needed.",
      params =
        listOf(
          ToolParam(
            name = "contact",
            type = ParamKind.HANDLE,
            description = "Contact id from find_contact.",
            required = false,
            handleType = HandleType.CONTACT,
          ),
          ToolParam(
            name = "number",
            type = ParamKind.STRING,
            description = "A phone number the user said, when there's no contact.",
            required = false,
          ),
          ToolParam(name = "message", type = ParamKind.STRING, description = "The text to send."),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "send_message",
      lockedRisk = LockedRisk.ACTS_AS_YOU,
      title = "Send text messages",
      summary = "Send an SMS to a contact or a number.",
      category = ToolCategory.COMMUNICATION,
      defaultApproval = ApprovalLevel.LOW,
      cancellable = false,
      requiredCapabilities = listOf(AppCapability.SEND_MESSAGES),
      optionalCapabilities = listOf(AppCapability.CONTACTS),
      unlockVerb = "send that message",
    )

  private data class Recipient(val name: String, val number: String)

  private fun recipient(call: ValidatedCall): Recipient? {
    val contact = call.handleArg("contact")?.payload as? RenderCard.Contact
    if (contact != null) return contact.phone?.let { Recipient(contact.name, it) }
    val number = call.stringArg("number") ?: return null
    if (number.count(Char::isDigit) < 3) return null
    return Recipient(PhoneNumberUtils.formatNumber(number, java.util.Locale.getDefault().country) ?: number, number)
  }

  override fun approvalContent(call: ValidatedCall, context: ToolContext): ApprovalContent {
    val to = recipient(call)?.name ?: "this contact"
    val message = call.stringArg("message").orEmpty()
    return ApprovalContent(
      spokenPrompt = "Your message to $to says: ${message.trimEnd('.', ' ')}. Send it?",
      card = ApprovalCard(title = "Send message", target = to, body = message),
    )
  }

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val message = call.stringArg("message")
      ?: return ToolOutcome.Steering("error", "The message is empty. Ask what to say.")
    val to =
      recipient(call)
        ?: return ToolOutcome.Steering(
          "error",
          if (call.handleArg("contact") != null) "That contact has no phone number. Tell the user."
          else "send_message needs a contact id or a phone number.",
        )
    return withContext(Dispatchers.IO) {
      val sms = context.appContext.getSystemService(SmsManager::class.java)
      sms.sendMultipartTextMessage(to.number, null, sms.divideMessage(message), null, null)
      ToolOutcome.Success(
        items =
          listOf(
            ResultItem(
              type = HandleType.MESSAGE,
              nativeId = "sent-${System.currentTimeMillis()}",
              projection = mapOf("to" to to.name, "message" to message, "sent" to true),
              renderPayload = RenderCard.Message(from = to.name, body = message, timeLabel = "Now", outgoing = true),
              autoShow = true,
            )
          )
      )
    }
  }
}
