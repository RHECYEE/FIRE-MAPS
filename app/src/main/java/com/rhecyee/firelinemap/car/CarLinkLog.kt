package com.rhecyee.firelinemap.car

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A record of the car host actually reaching this app.
 *
 * Static checks can prove the app is declared correctly and still not say why
 * it is missing from the launcher, because everything after the declarations
 * happens inside a host this app cannot see. This closes that: if the service
 * is never created, the host is not binding it and the problem is the host's
 * list. If it is created and no session follows, the host reached the app and
 * something here failed. Those are opposite problems and nothing else on the
 * phone tells them apart.
 *
 * Kept in plain preferences and deliberately tiny. It is written from a service
 * that may be started at any moment, including with the phone screen off.
 */
object CarLinkLog {

    fun record(context: Context, event: String) {
        runCatching {
            val store = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = store.getString(KEY, "").orEmpty()
            val line = "${System.currentTimeMillis()}|$event"
            val kept = (listOf(line) + existing.lines().filter { it.isNotBlank() })
                .take(MAX)
            store.edit().putString(KEY, kept.joinToString("\n")).apply()
        }
    }

    /** Newest first, already formatted for reading. */
    fun events(context: Context): List<String> = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val at = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                val what = line.substringAfter('|')
                "${STAMP.format(Date(at))}  $what"
            }
    }.getOrDefault(emptyList())

    fun everReached(context: Context): Boolean = events(context).isNotEmpty()

    fun clear(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        }
    }

    private const val PREFS = "car_link_log"
    private const val KEY = "events"
    private const val MAX = 12
    private val STAMP = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)
}
