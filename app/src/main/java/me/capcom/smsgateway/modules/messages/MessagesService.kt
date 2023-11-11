package me.capcom.smsgateway.modules.messages

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import me.capcom.smsgateway.data.dao.MessageDao
import me.capcom.smsgateway.data.entities.Message
import me.capcom.smsgateway.data.entities.MessageRecipient
import me.capcom.smsgateway.data.entities.MessageWithRecipients
import me.capcom.smsgateway.helpers.PhoneHelper
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.receivers.EventsReceiver

class MessagesService(
    private val context: Context,
    private val dao: MessageDao,    // todo: use MessagesRepository
) {
    val events = EventBus()
    private val countryCode: String? =
        (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkCountryIso

    suspend fun sendMessage(
        id: String?,
        text: String,
        recipients: List<String>,
        source: Message.Source
    ): MessageWithRecipients {
        val id = id ?: NanoIdUtils.randomNanoId()

        val message = MessageWithRecipients(
            Message(id, text, source),
            recipients.map {
                val phoneNumber = PhoneHelper.filterPhoneNumber(it, countryCode ?: "RU")
                MessageRecipient(
                    id,
                    phoneNumber ?: it,
                    phoneNumber?.let { Message.State.Pending } ?: Message.State.Failed
                )
            },
        )

        dao.insert(message)

        if (message.state != Message.State.Pending) {
            updateState(id, null, message.state)
            return message
        }

        try {
            sendSMS(
                id,
                text,
                message.recipients.filter { it.state == Message.State.Pending }
                    .map { it.phoneNumber })
        } catch (e: Exception) {
            updateState(id, null, Message.State.Failed)
            return requireNotNull(getState(id))
        }

        return message
    }

    suspend fun getState(id: String): MessageWithRecipients? {
        val message = dao.get(id)
            ?: return null

        val state = message.state

        if (state == message.message.state) {
            return message
        }

        if (state != message.message.state) {
            dao.updateMessageState(message.message.id, state)
        }

        return dao.get(id)
    }

    suspend fun processStateIntent(intent: Intent, resultCode: Int) {
        val state = when (intent.action) {
            EventsReceiver.ACTION_SENT -> when (resultCode) {
                Activity.RESULT_OK -> Message.State.Sent
                else -> Message.State.Failed
            }

            EventsReceiver.ACTION_DELIVERED -> Message.State.Delivered
            else -> return
        }
        val (id, phone) = intent.dataString?.split("|", limit = 2) ?: return

        updateState(id, phone, state)
    }

    private suspend fun updateState(
        id: String,
        phone: String?,
        state: Message.State
    ) {
        phone?.let {
            dao.updateRecipientState(id, it, state)
        }
            ?: kotlin.run {
                dao.updateRecipientsState(id, state)
            }

        val state = requireNotNull(getState(id)?.state)

        events.emitEvent(
            MessageStateChangedEvent(
                id,
                state,
                dao.get(id)?.recipients?.associate {
                    it.phoneNumber to it.state
                } ?: return
            )
        )
    }

    private suspend fun sendSMS(id: String, message: String, recipients: List<String>) {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

        recipients.forEach {
            val sentIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(EventsReceiver.ACTION_SENT, Uri.parse("$id|$it"), context, EventsReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val deliveredIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(EventsReceiver.ACTION_DELIVERED, Uri.parse("$id|$it"), context, EventsReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            try {
                smsManager.sendTextMessage(it, null, message, sentIntent, deliveredIntent)
                updateState(id, it, Message.State.Processed)
            } catch (th: Throwable) {
                th.printStackTrace()
                updateState(id, it, Message.State.Failed)
            }
        }
    }
}