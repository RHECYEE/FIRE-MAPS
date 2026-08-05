package com.rhecyee.firelinemap.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing tracks and pins to somebody else.
 *
 * No transport is implemented here, on purpose. Bluetooth, AirDrop, a message,
 * email, a cable and a nearby phone are all already on the device, and every
 * one of them takes a file. Writing a Bluetooth stack would be building, badly,
 * one of the six things the operator can already pick from a list -- and it
 * would be the only one that could not reach an iPhone.
 *
 * What this does is produce the file and get out of the way.
 */
object ShareIntents {

    /** Where shared files are written. Matches res/xml/shared_files.xml. */
    private const val DIRECTORY = "shares"

    /**
     * What a picture message will actually carry.
     *
     * Carriers cap MMS, commonly at three hundred kilobytes and sometimes
     * lower, and an attachment over the cap is refused rather than shrunk --
     * usually with no error worth reading. Two hundred and fifty thousand
     * bytes is under every cap in common use, so a file prepared to this size
     * sends rather than silently failing on a hilltop with one bar.
     *
     * RCS and iMessage carry far more, but neither is guaranteed to be what
     * the message goes out over, and the failure only shows up in the field.
     */
    const val MESSAGE_BUDGET_BYTES = 250_000

    /**
     * Puts one encoded part into a message, through the normal share sheet.
     *
     * The share sheet is the whole point. It is where the operator's actual
     * contacts are, one tap each, and it reaches Messages, WhatsApp, Signal
     * and a nearby phone without this app knowing anything about any of them
     * -- no phone numbers typed, no permission to send texts, and nothing that
     * could send a message the operator did not pick a recipient for.
     *
     * A readable line goes in front of the payload so whoever receives it
     * knows what it is and what to do with it. The reader ignores anything
     * before the marker, so the note costs nothing.
     */
    fun textPart(
        context: Context,
        pkg: SharePackage,
        part: String,
        index: Int,
        total: Int
    ): Boolean {
        val which = if (total > 1) " (part $index of $total)" else ""
        val instruction = if (total > 1) {
            "Paste each part into Fireline Map, Share, Receive."
        } else {
            "Paste into Fireline Map, Share, Receive."
        }
        val body = "${pkg.incidentName} — ${pkg.describe()}$which\n$instruction\n\n$part"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_SUBJECT, pkg.incidentName)
        }
        val chooser = Intent.createChooser(
            intent,
            if (total > 1) "Send part $index of $total" else "Send ${pkg.describe()}"
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }

    /**
     * Sends the positions as the text of a message.
     *
     * The path that always works. No attachment, so nothing can refuse it on
     * size or type, and the person receiving it needs nothing installed -- the
     * coordinates are readable on the screen, over a radio, and can be typed
     * straight back into this app's search, which reads the form written here.
     */
    fun text(context: Context, pkg: SharePackage): Boolean {
        if (pkg.isEmpty) return false
        val body = ShareText.message(pkg)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_SUBJECT, pkg.incidentName)
        }
        val chooser = Intent.createChooser(intent, "Text ${pkg.describe()}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }

    /**
     * Prepares the file, thinned if it has to be to go in a message.
     *
     * Returns what was done as well as the package, so the operator is told
     * rather than finding out later that a switchback is missing from a track
     * they sent somebody.
     */
    fun prepareForMessage(pkg: SharePackage): TrackSimplify.Simplified =
        TrackSimplify.toFit(pkg, MESSAGE_BUDGET_BYTES) { GpxFormat.write(it).length }

    /** Bytes the file would take, without writing it. */
    fun sizeOf(pkg: SharePackage): Int = GpxFormat.write(pkg).length

    fun authority(context: Context): String = "${context.packageName}.shares"

    /**
     * Writes the package and opens the share sheet.
     *
     * Returns false only when the file could not be written or nothing on the
     * device will take it -- both worth saying out loud rather than leaving
     * the operator tapping a button that appears to do nothing.
     */
    fun share(context: Context, pkg: SharePackage): Boolean {
        if (pkg.isEmpty) return false
        val file = write(context, pkg) ?: return false

        val uri = runCatching {
            FileProvider.getUriForFile(context, authority(context), file)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = GpxFormat.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, pkg.incidentName)
            // Read in the preview of a message before anything is opened, so
            // whoever receives it knows what it is without an app to open it.
            putExtra(
                Intent.EXTRA_TEXT,
                "${pkg.incidentName} — ${pkg.describe()}. " +
                    "Opens in Fireline Map, or any GPS app that reads GPX."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Send ${pkg.describe()}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }

    /**
     * Writes the file, replacing whatever was there last time.
     *
     * Kept in the cache directory: once it has been handed over, the copy here
     * is spent. Android reclaims it under pressure and nothing is lost, since
     * the tracks and pins themselves live in the database.
     */
    fun write(context: Context, pkg: SharePackage): File? = runCatching {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        // One file per incident rather than per tap, so repeated sends do not
        // fill the cache with near-identical copies.
        val file = File(directory, "${fileName(pkg.incidentName)}.${GpxFormat.EXTENSION}")
        file.writeText(GpxFormat.write(pkg))
        file
    }.getOrNull()

    /** Reads a package somebody sent. */
    fun readFrom(context: Context, uri: Uri): SharePackage? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // Bounded: this is parsed into memory, and a file picker will
            // happily hand over a gigabyte of something that is not a GPX.
            val text = input.readBytes().decodeToString()
            GpxFormat.read(text)
        }
    }.getOrNull()

    /** A filename that survives every filesystem and messaging app. */
    private fun fileName(incidentName: String): String {
        val cleaned = incidentName.map { character ->
            if (character.isLetterOrDigit()) character else '-'
        }.joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(48)
        return cleaned.ifEmpty { "fireline" }
    }
}
