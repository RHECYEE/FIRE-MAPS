package com.rhecyee.firelinemap.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few crashes where the operator can read them.
 *
 * A phone in a truck has no terminal attached and nobody is going to pull
 * logcat off it at a drop point, so a crash arrives here as "it crashes" and
 * nothing else -- which is a whole round trip before anyone knows what threw.
 * Writing the stack trace somewhere the app can show it turns that into one
 * paste.
 *
 * The system's own handler still runs afterwards, so this changes nothing
 * about how a crash behaves; it only leaves a note first.
 */
object CrashLog {

    private const val FILE_NAME = "crashes.log"

    /** Entries kept. Older ones are dropped as new ones arrive. */
    const val KEEP = 5

    /** Ceiling on the file, so a crash loop cannot fill the device. */
    const val MAX_BYTES = 128 * 1024

    private const val SEPARATOR = "\n===== CRASH =====\n"

    fun install(context: Context, versionName: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Wrapped: a handler that throws replaces a useful crash with a
            // useless one.
            runCatching {
                append(app, format(thread.name, error, System.currentTimeMillis(), versionName))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Everything recorded, newest first. Null when nothing has crashed. */
    fun latest(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }

    private fun append(context: Context, entry: String) {
        val file = File(context.filesDir, FILE_NAME)
        val existing = runCatching { file.readText() }.getOrDefault("")
        file.writeText(trim(entry + existing))
    }

    /** One entry, in the order someone reading it needs: what, then where. */
    fun format(
        threadName: String,
        error: Throwable,
        atMillis: Long,
        versionName: String
    ): String {
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(atMillis))
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            append(SEPARATOR)
            append("$when_  v$versionName  thread=$threadName\n")
            append("${error::class.java.name}: ${error.message}\n")
            append(trace)
        }
    }

    /**
     * Holds the log to the newest [KEEP] entries and to [MAX_BYTES].
     *
     * Newest first, so a truncation takes the oldest rather than the crash
     * somebody is currently trying to read.
     */
    fun trim(log: String): String {
        val entries = log.split(SEPARATOR).filter { it.isNotBlank() }
        var kept = entries.take(KEEP)
        var out = kept.joinToString("") { SEPARATOR + it }
        while (out.length > MAX_BYTES && kept.size > 1) {
            kept = kept.dropLast(1)
            out = kept.joinToString("") { SEPARATOR + it }
        }
        return if (out.length > MAX_BYTES) out.take(MAX_BYTES) else out
    }
}
