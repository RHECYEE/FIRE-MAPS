package com.rhecyee.firelinemap.geopdf

import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Reads ISO 32000 geospatial viewports out of a PDF.
 *
 * Written against real wildfire incident products rather than against the
 * specification alone, which changed several assumptions:
 *
 * * Neither test product carries an OGC `/LGIDict`. A reader that only
 *   understands the OGC Best Practice dictionary finds nothing in them.
 * * `/GCS` holds a `/Type /PROJCS` with a WKT literal, not the geographic
 *   system the specification's naming implies, and there is no `/EPSG` entry.
 * * `/Bounds` carries ten numbers, not eight; the ring is closed.
 * * A page can hold several viewports. They may be genuine separate frames
 *   (the transportation sheet has a main map and two insets) or the same
 *   frame repeated (the operations sheet declares one frame three times).
 */
object GeoPdfReader {

    fun read(file: File): GeoPdfDocument = read(file.readBytes())

    fun read(bytes: ByteArray): GeoPdfDocument =
        GeoPdfParse.readText(buildSearchableText(bytes))

    /**
     * The raw bytes plus any inflated object streams.
     *
     * Latin-1 maps bytes to characters one to one, so the PDF's binary
     * sections survive the decode unchanged and offsets stay meaningful.
     */
    private fun buildSearchableText(bytes: ByteArray): String {
        val latin1 = String(bytes, Charsets.ISO_8859_1)
        val builder = StringBuilder(latin1)
        for (streamStart in GeoPdfParse.objectStreamOffsets(latin1)) {
            inflate(bytes, streamStart)?.let {
                builder.append('\n').append(String(it, Charsets.ISO_8859_1))
            }
        }
        return builder.toString()
    }

    private fun inflate(bytes: ByteArray, start: Int): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(bytes, start, bytes.size - start)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (!inflater.finished()) {
            val produced = inflater.inflate(buffer)
            if (produced == 0) break
            output.write(buffer, 0, produced)
        }
        inflater.end()
        output.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

}
