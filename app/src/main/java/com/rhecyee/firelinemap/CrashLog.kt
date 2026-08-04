package com.rhecyee.firelinemap

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash so it can be read afterwards.
 *
 * This app is used in places with no cable, no console and often no signal.
 * When it dies on a fireline the only person who can say what happened is the
 * one holding it, and "it crashed when I zoomed" is not something anyone can
 * act on. Writing the trace to a file the operator can read out or send is the
 * difference between fixing a fault and guessing at it.
 *
 * Deliberately local. Nothing is transmitted anywhere: this is a company tool
 * with no server behind it, and a crash reporter that phones home would be the
 * one piece of infrastructure the whole design is built to avoid.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /** Chains onto whatever handler is already installed rather than replacing it. */
    fun install(context: Context) {
        val app = context.applicationContext
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            existing?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file(context).writeText(
            buildString {
                appendLine("Fireline Map crash")
                appendLine(stamp)
                appendLine("thread: ${thread.name}")
                appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("android: ${android.os.Build.VERSION.RELEASE}")
                appendLine()
                append(trace)
            }
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** The last crash, or null if the app has not died since it was cleared. */
    fun read(context: Context): String? =
        file(context).takeIf { it.length() > 0 }?.let {
            runCatching { it.readText() }.getOrNull()
        }

    /** The first few lines, which is usually where the answer is. */
    fun summary(context: Context): String? = read(context)?.let { text ->
        val lines = text.lines()
        val cause = lines.firstOrNull { it.startsWith("Caused by:") }
        val thrown = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }
        val at = lines.firstOrNull { it.trim().startsWith("at com.rhecyee.firelinemap") }
        listOfNotNull(cause ?: thrown, at?.trim()).joinToString("\n").ifBlank { null }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
