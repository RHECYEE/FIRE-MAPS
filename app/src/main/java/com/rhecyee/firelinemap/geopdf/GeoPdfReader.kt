package com.rhecyee.firelinemap.geopdf

import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * How a PDF should be presented once its geospatial content has been read.
 *
 * The distinction is shown to the operator verbatim. A product that is not
 * georeferenced must never be displayed as though a position on it means
 * something.
 */
enum class PdfKind {
    /** Carries usable georeferencing; live position can be drawn on it. */
    GEOREFERENCED,

    /** A readable document with no usable georeferencing. View only. */
    PLAIN
}

/** The result of reading a PDF's geospatial dictionaries. */
data class GeoPdfDocument(
    val frames: List<MapFrame>,
    val kind: PdfKind
) {
    /**
     * The frame the live position should be drawn against.
     *
     * The largest frame on the sheet is the main map. Insets are smaller by
     * construction, and using one would place the position by the wrong
     * transform entirely.
     */
    val primaryFrame: MapFrame? get() = frames.maxByOrNull { it.box.area }

    val isGeoreferenced: Boolean get() = kind == PdfKind.GEOREFERENCED

    /** Frames other than the main one, i.e. detail insets. */
    val insetFrames: List<MapFrame>
        get() = primaryFrame?.let { main -> frames.filter { it !== main } } ?: emptyList()
}

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

    fun read(bytes: ByteArray): GeoPdfDocument {
        val text = buildSearchableText(bytes)
        val frames = parseViewports(text)
        val distinct = deduplicate(frames)
        return GeoPdfDocument(
            frames = distinct,
            kind = if (distinct.isNotEmpty()) PdfKind.GEOREFERENCED else PdfKind.PLAIN
        )
    }

    /**
     * The raw bytes plus any inflated object streams.
     *
     * Latin-1 maps bytes to characters one to one, so the PDF's binary
     * sections survive the decode unchanged and offsets stay meaningful.
     */
    private fun buildSearchableText(bytes: ByteArray): String {
        val builder = StringBuilder(String(bytes, Charsets.ISO_8859_1))
        for (streamStart in findObjectStreams(bytes)) {
            inflate(bytes, streamStart)?.let {
                builder.append('\n').append(String(it, Charsets.ISO_8859_1))
            }
        }
        return builder.toString()
    }

    private fun findObjectStreams(bytes: ByteArray): List<Int> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val results = mutableListOf<Int>()
        for (match in Regex("""/Type\s*/ObjStm""").findAll(text)) {
            val streamKeyword = text.indexOf("stream", match.range.first)
            if (streamKeyword < 0) continue
            var start = streamKeyword + "stream".length
            while (start < bytes.size && (bytes[start] == '\r'.code.toByte() ||
                    bytes[start] == '\n'.code.toByte())
            ) {
                start++
            }
            results += start
        }
        return results
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

    /**
     * Finds the viewports by looking for the coordinates, not for the label.
     *
     * The obvious way round is to search for `/Type /Viewport` and take the
     * dictionary it sits in. Finding that dictionary means walking backwards
     * to an opening `<<`, and which one that is depends on where `/Type` was
     * written -- which is a thing the PDF specification explicitly does not
     * fix. Dictionary keys are unordered.
     *
     * Two real incident products put it in opposite places. The Forest Service
     * transportation sheet writes `<</Type /Viewport/BBox ...`, where walking
     * back one step lands on the viewport. A CAL FIRE operations sheet writes
     * `<</BBox[...]/Measure<<...>>/Type/Viewport>>`, where walking back lands
     * inside the coordinate system dictionary buried in `/Measure` -- which
     * has no `/BBox` and no `/GPTS`, so every viewport on the sheet read as
     * unusable and a properly georeferenced map was shown as a plain PDF with
     * no position on it and no terrain under it.
     *
     * So anchor on `/GPTS`, which is the thing actually needed, and take the
     * innermost enclosing dictionary that also carries a `/BBox`. That is the
     * viewport whatever order it was written in.
     */
    private fun parseViewports(text: String): List<MapFrame> {
        val frames = mutableListOf<MapFrame>()
        var searchFrom = 0
        while (true) {
            val marker = text.indexOf("/GPTS", searchFrom)
            if (marker < 0) break
            searchFrom = marker + "/GPTS".length

            enclosingViewport(text, marker)?.let { dict ->
                parseFrame(dict)?.let { frames += it }
            }
        }
        return frames
    }

    /**
     * The dictionary around [marker] that looks like a viewport.
     *
     * Candidates are tried innermost first. A dictionary qualifies only if it
     * actually encloses the marker -- the nearest `<<` going backwards is
     * usually a sibling that has already closed -- and if it carries a
     * `/BBox`, which is what separates the viewport from the `/Measure` and
     * `/GCS` dictionaries nested inside it.
     */
    private fun enclosingViewport(text: String, marker: Int): String? {
        var candidate = text.lastIndexOf("<<", marker)
        var tried = 0
        while (candidate >= 0 && tried < MAX_ENCLOSING_CANDIDATES) {
            tried++
            val end = findDictionaryEnd(text, candidate)
            if (end != null && end > marker) {
                val dict = text.substring(candidate, end)
                if (dict.contains("/BBox")) return dict
            }
            if (candidate == 0) break
            candidate = text.lastIndexOf("<<", candidate - 1)
        }
        return null
    }

    /**
     * How far out to look for the enclosing viewport.
     *
     * A viewport nests two dictionaries deep, so three or four candidates is
     * the real answer; the rest is slack for a producer that nests further.
     * Bounded so a file where the search finds nothing cannot walk the whole
     * document back from every `/GPTS` on it.
     */
    private const val MAX_ENCLOSING_CANDIDATES = 12

    private fun parseFrame(dict: String): MapFrame? {
        val box = readNumberArray(dict, "/BBox")?.takeIf { it.size >= 4 } ?: return null
        val gpts = readNumberArray(dict, "/GPTS")?.takeIf { it.size >= 8 } ?: return null
        // /LPTS is optional in practice; the corner order it encodes is the
        // conventional one when absent.
        val lpts = readNumberArray(dict, "/LPTS")?.takeIf { it.size >= 8 }
            ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0).toList()

        val cornerCount = minOf(gpts.size / 2, lpts.size / 2)
        if (cornerCount < 3) return null

        return MapFrame(
            name = readTextString(dict, "/Name"),
            box = PageBox(
                left = minOf(box[0], box[2]),
                bottom = minOf(box[1], box[3]),
                right = maxOf(box[0], box[2]),
                top = maxOf(box[1], box[3])
            ),
            geoCorners = (0 until cornerCount).map {
                GeoPoint(gpts[it * 2], gpts[it * 2 + 1])
            },
            localCorners = (0 until cornerCount).map {
                lpts[it * 2] to lpts[it * 2 + 1]
            },
            wkt = readTextString(dict, "/WKT")
        )
    }

    /**
     * Drops frames that describe the same coverage.
     *
     * The operations sheet declares its single map frame three times. Left
     * alone those would look like three candidate frames to choose between.
     */
    private fun deduplicate(frames: List<MapFrame>): List<MapFrame> {
        val kept = mutableListOf<MapFrame>()
        for (frame in frames) {
            val duplicate = kept.any { existing ->
                sameBox(existing.box, frame.box) &&
                    existing.geoCorners.size == frame.geoCorners.size &&
                    existing.geoCorners.indices.all { i ->
                        abs(existing.geoCorners[i].latitude - frame.geoCorners[i].latitude) < 1e-7 &&
                            abs(existing.geoCorners[i].longitude - frame.geoCorners[i].longitude) < 1e-7
                    }
            }
            if (!duplicate) kept += frame
        }
        return kept
    }

    private fun sameBox(a: PageBox, b: PageBox): Boolean =
        abs(a.left - b.left) < 1e-6 && abs(a.bottom - b.bottom) < 1e-6 &&
            abs(a.right - b.right) < 1e-6 && abs(a.top - b.top) < 1e-6

    /** Walks a dictionary to its matching close, stepping over string literals. */
    private fun findDictionaryEnd(text: String, start: Int): Int? {
        var depth = 0
        var index = start
        while (index < text.length - 1) {
            when {
                text[index] == '(' -> {
                    index = skipLiteralString(text, index)
                    continue
                }
                text.startsWith("<<", index) -> {
                    depth++
                    index += 2
                    continue
                }
                text.startsWith(">>", index) -> {
                    depth--
                    index += 2
                    if (depth == 0) return index
                    continue
                }
            }
            index++
        }
        return null
    }

    /** Returns the index just past a literal string, honouring nesting and escapes. */
    private fun skipLiteralString(text: String, openParen: Int): Int {
        var index = openParen + 1
        var depth = 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        return text.length
    }

    private fun readNumberArray(dict: String, key: String): List<Double>? {
        val keyIndex = dict.indexOf(key)
        if (keyIndex < 0) return null
        val open = dict.indexOf('[', keyIndex)
        if (open < 0) return null
        val close = dict.indexOf(']', open)
        if (close < 0) return null
        return dict.substring(open + 1, close)
            .split(' ', '\n', '\r', '\t')
            .filter { it.isNotBlank() }
            .mapNotNull { it.toDoubleOrNull() }
    }

    /** Reads a literal string value, decoding UTF-16 when the BOM is present. */
    private fun readTextString(dict: String, key: String): String? {
        val keyIndex = dict.indexOf(key)
        if (keyIndex < 0) return null
        val open = dict.indexOf('(', keyIndex)
        if (open < 0) return null
        val end = skipLiteralString(dict, open)
        val raw = dict.substring(open + 1, (end - 1).coerceAtLeast(open + 1))
        val bytes = ByteArray(raw.length) { raw[it].code.toByte() }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE).trim()
        }
        return raw.replace("\\", "").trim().ifBlank { null }
    }
}
