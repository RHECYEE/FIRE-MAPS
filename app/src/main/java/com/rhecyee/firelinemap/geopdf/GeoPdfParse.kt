package com.rhecyee.firelinemap.geopdf

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
 * The part of reading a GeoPDF that is only string work.
 *
 * Split from [GeoPdfReader] so the browser can run it. Inflating a PDF's
 * compressed object streams needs a platform; finding the geospatial viewports
 * in the result and turning them into a transform does not. Sharing this means
 * a sheet that lines up on the phone lines up in the browser, and a sheet that
 * is a few hundred metres out in one of them is worse than no sheet at all.
 */
object GeoPdfParse {

    /** The georeferencing, from text already assembled. */
    fun readText(text: String): GeoPdfDocument {
        val frames = parseViewports(text)
        val distinct = deduplicate(frames)
        return GeoPdfDocument(
            frames = distinct,
            kind = if (distinct.isNotEmpty()) PdfKind.GEOREFERENCED else PdfKind.PLAIN
        )
    }

    /**
     * Where each compressed object stream's data begins.
     *
     * Exposed for callers that inflate for themselves. [latin1] must be the
     * file's bytes decoded as Latin-1, which maps bytes to characters one to
     * one, so offsets into the string are offsets into the file.
     */
    fun objectStreamOffsets(latin1: String): List<Int> {
        val results = mutableListOf<Int>()
        for (match in Regex("""/Type\s*/ObjStm""").findAll(latin1)) {
            val streamKeyword = latin1.indexOf("stream", match.range.first)
            if (streamKeyword < 0) continue
            var start = streamKeyword + "stream".length
            while (start < latin1.length &&
                (latin1[start] == '\r' || latin1[start] == '\n')
            ) {
                start++
            }
            results += start
        }
        return results
    }

    private fun parseViewports(text: String): List<MapFrame> {
        val frames = mutableListOf<MapFrame>()
        var searchFrom = 0
        while (true) {
            val marker = text.indexOf("/Viewport", searchFrom)
            if (marker < 0) break
            searchFrom = marker + "/Viewport".length

            val dictStart = text.lastIndexOf("<<", marker)
            if (dictStart < 0) continue
            val dictEnd = findDictionaryEnd(text, dictStart) ?: continue
            val dict = text.substring(dictStart, dictEnd)

            parseFrame(dict)?.let { frames += it }
        }
        return frames
    }

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
        // A name written as UTF-16, which is how these products spell theirs.
        // Decoded by hand rather than through a platform charset, so the phone
        // and the browser read the same bytes into the same name.
        if (raw.length >= 2 && raw[0].code == 0xFE && raw[1].code == 0xFF) {
            val out = StringBuilder()
            var index = 2
            while (index + 1 < raw.length) {
                out.append(((raw[index].code shl 8) or raw[index + 1].code).toChar())
                index += 2
            }
            return out.toString().trim()
        }
        return raw.replace("\\", "").trim().ifBlank { null }
    }
}
