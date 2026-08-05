package com.rhecyee.firelinemap.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * Sending an incident as a run of text messages.
 *
 * The point is that the sender does one thing. Copying seven parts into seven
 * messages by hand is seven chances to send the same one twice, miss one, or
 * give up halfway -- and the person doing it is in a truck with gloves on.
 * They confirm once and the messages go.
 *
 * This sends real texts, which cost real money and cannot be recalled, so it
 * asks first and says exactly how many and to whom. Nothing here sends
 * anything without that confirmation.
 *
 * Text rather than a picture message on purpose. A picture message is capped
 * by the carrier and refuses an attachment over the cap; a text is not capped,
 * it is split into segments the receiving phone puts back together, and it
 * arrives on anything.
 */
object SmsSender {

    private const val PREFS = "fireline-sms"
    private const val KEY_LAST_NUMBER = "last-number"

    /**
     * Characters a single SMS segment holds.
     *
     * The GSM alphabet gives 160 alone, but 153 once a message is split,
     * because the rest carries the part number that lets the receiving phone
     * reassemble it.
     */
    const val SEGMENT_CHARACTERS = 153

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether this device can send a text at all. A tablet on wifi cannot. */
    fun hasTelephony(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /** How many SMS segments a run of parts will actually cost. */
    fun segmentsFor(parts: List<String>): Int =
        parts.sumOf { (it.length + SEGMENT_CHARACTERS - 1) / SEGMENT_CHARACTERS }

    /**
     * A number worth trying to send to.
     *
     * Deliberately loose. Numbers get typed with spaces, dashes, brackets and
     * a country code or none, and refusing any of those helps nobody -- the
     * network is the thing that decides, and it will say so.
     */
    fun looksLikeNumber(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        return digits in 7..15 && text.none { it.isLetter() }
    }

    fun lastNumber(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_NUMBER, "") ?: ""

    fun rememberNumber(context: Context, number: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_NUMBER, number)
            .apply()
    }

    /** What happened, so the operator is told rather than left guessing. */
    data class Outcome(
        val sent: Int,
        val total: Int,
        val failure: String? = null
    ) {
        val allSent: Boolean get() = failure == null && sent == total

        fun describe(): String = when {
            allSent && total == 1 -> "Message sent."
            allSent -> "All $total messages sent."
            sent == 0 -> failure ?: "Nothing could be sent."
            // The half-sent case is the one that matters. The receiver will be
            // holding parts that do not assemble and will not know why.
            else -> "Only $sent of $total sent — ${failure ?: "the rest failed"}. " +
                "Tell them which parts are missing."
        }
    }

    /**
     * Sends each part as its own message.
     *
     * Each part goes through the multipart call, so a part longer than one
     * segment is split by the phone and reassembled by the receiving phone
     * into a single message -- which is what makes it pasteable in one go at
     * the other end.
     */
    fun send(context: Context, number: String, parts: List<String>): Outcome {
        if (parts.isEmpty()) return Outcome(0, 0, "Nothing to send.")
        if (!hasPermission(context)) {
            return Outcome(0, parts.size, "Permission to send texts was not granted.")
        }
        if (!hasTelephony(context)) {
            return Outcome(0, parts.size, "This device cannot send texts.")
        }

        val manager = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        }.getOrNull() ?: return Outcome(0, parts.size, "No messaging service on this device.")

        var sent = 0
        parts.forEach { part ->
            val outcome = runCatching {
                val segments = manager.divideMessage(part)
                manager.sendMultipartTextMessage(number, null, segments, null, null)
            }
            if (outcome.isFailure) {
                return Outcome(
                    sent,
                    parts.size,
                    outcome.exceptionOrNull()?.message ?: "the network refused it"
                )
            }
            sent++
        }
        rememberNumber(context, number)
        return Outcome(sent, parts.size)
    }
}
