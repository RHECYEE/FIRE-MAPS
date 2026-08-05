package com.rhecyee.firelinemap.share

/**
 * A whole incident as text somebody can paste into a message.
 *
 * The last thing that cannot fail. A file can be refused by a carrier for its
 * size, refused by a messaging app for its type, or land on a phone with
 * nothing that opens it. Text in the body of a message has none of those
 * failure modes: it arrives, it can be forwarded, and it can be pasted back.
 *
 * Unlike [ShareText], which is written for a person to read, this is written
 * to be pasted back and redrawn -- tracks with their shape and their times,
 * pins with their symbols. It is deliberately still plain characters, so it
 * survives every transport a message goes through.
 *
 * Split into numbered parts so each one fits a message on its own. Parts can
 * be pasted in any order and the app says how many are still missing, because
 * messages arrive out of order and somebody will paste the third one first.
 */
object TextCodec {

    /** Marks the format, and its version, so a later change can be told apart. */
    const val PREFIX = "FL1"

    /**
     * Characters per part.
     *
     * Set high on purpose. A text is split into 153 character segments by the
     * sending phone and put back together by the receiving one, so a long
     * message arrives as a single message that can be pasted in a single go --
     * the splitting is the network's problem, not the operator's.
     *
     * So the only reason to break an incident into parts at all is that
     * carriers do not carry an unbounded message. Three thousand characters is
     * about twenty segments, which every carrier reassembles, and it puts a
     * typical incident in one message and a heavy one in two.
     */
    const val DEFAULT_PART_LENGTH = 3_000

    /**
     * Separators chosen from below the polyline alphabet.
     *
     * An encoded shape only ever contains characters from '?' upward, so
     * these can never appear inside one. That is what makes it safe to split
     * on them without escaping the payload, which is most of the bulk.
     */
    private const val FIELD = ';'
    private const val RECORD = '!'

    // ---------------------------------------------------------------- write

    /** The whole package as one string, before it is split into parts. */
    fun encode(pkg: SharePackage): String {
        val records = mutableListOf<String>()
        records += listOf("i", escape(pkg.incidentName), escape(pkg.author ?: "")).join()

        pkg.pins.forEach { pin ->
            records += listOf(
                "p",
                escape(pin.title),
                PolylineCodec.encodePoints(listOf(pin.latitude to pin.longitude)),
                escape(pin.symbolId ?: ""),
                escape(pin.note ?: "")
            ).join()
        }

        pkg.tracks.forEach { track ->
            records += listOf(
                "t",
                escape(track.name),
                PolylineCodec.encodePoints(track.points.map { it.latitude to it.longitude }),
                PolylineCodec.encodeTimes(track.points.map { it.timeMillis ?: 0L })
            ).join()
        }

        return records.joinToString(RECORD.toString())
    }

    /**
     * The package as numbered parts, each ready to paste into a message.
     *
     * Every part carries the checksum of the whole, so parts from two
     * different sends cannot be combined into a track that was never walked.
     */
    fun parts(pkg: SharePackage, partLength: Int = DEFAULT_PART_LENGTH): List<String> {
        val body = encode(pkg)
        val checksum = checksum(body)
        val room = (partLength - HEADER_ALLOWANCE).coerceAtLeast(64)
        val slices = body.chunked(room).ifEmpty { listOf("") }
        return slices.mapIndexed { index, slice ->
            "$PREFIX$FIELD$checksum$FIELD${index + 1}$FIELD${slices.size}$FIELD$slice"
        }
    }

    /** Roughly what a header costs, so a part lands near the length asked for. */
    private const val HEADER_ALLOWANCE = 24

    // ----------------------------------------------------------------- read

    /** One pasted part, once it has been recognised. */
    data class Part(
        val checksum: String,
        val index: Int,
        val total: Int,
        val slice: String
    )

    /**
     * Reads a pasted part.
     *
     * Whitespace is thrown away first. Messages get wrapped, quoted, indented
     * and re-flowed on the way through, and none of that changes what was
     * sent -- but a reader that has not allowed for it rejects a perfectly
     * good paste, and the operator has no way to tell why.
     *
     * Anything before the marker is ignored too, so pasting a whole message
     * with "here you go" in front of it works.
     */
    fun readPart(pasted: String): Part? {
        val text = pasted.filterNot { it.isWhitespace() }
        val start = text.indexOf(PREFIX + FIELD)
        if (start < 0) return null
        val fields = text.substring(start).split(FIELD, limit = 5)
        if (fields.size < 5) return null
        val index = fields[2].toIntOrNull() ?: return null
        val total = fields[3].toIntOrNull() ?: return null
        if (index < 1 || total < 1 || index > total) return null
        return Part(fields[1], index, total, fields[4])
    }

    /** What a collection of pasted parts still needs. */
    data class Assembly(
        val parts: Map<Int, Part> = emptyMap()
    ) {
        val total: Int get() = parts.values.firstOrNull()?.total ?: 0
        val missing: List<Int>
            get() = if (total == 0) emptyList() else (1..total).filter { it !in parts }
        val isComplete: Boolean get() = total > 0 && missing.isEmpty()

        fun describe(): String = when {
            parts.isEmpty() -> "Nothing pasted yet"
            isComplete -> "All $total parts — ready"
            missing.size == 1 -> "Have ${parts.size} of $total — still need part ${missing.first()}"
            else -> "Have ${parts.size} of $total — still need ${missing.joinToString(", ")}"
        }

        /**
         * Adds a part.
         *
         * A part from a different send replaces everything rather than joining
         * it. Two halves of two different packages would assemble into a
         * checksum failure at best and a track nobody walked at worst, and the
         * operator pasting them has no reason to suspect it.
         */
        fun plus(part: Part): Assembly {
            val sameSend = parts.values.firstOrNull()?.checksum == part.checksum
            val base = if (sameSend) parts else emptyMap()
            return Assembly(base + (part.index to part))
        }

        /** The reassembled body, or null while anything is missing. */
        fun body(): String? {
            if (!isComplete) return null
            return (1..total).joinToString("") { parts.getValue(it).slice }
        }

        /** Whether what arrived is what was sent. */
        fun verified(): Boolean {
            val body = body() ?: return false
            return checksum(body) == parts.values.first().checksum
        }
    }

    /** Decodes a reassembled body. */
    fun decode(body: String): SharePackage? {
        if (body.isBlank()) return null
        var incident = "Shared"
        var author: String? = null
        val pins = mutableListOf<SharePin>()
        val tracks = mutableListOf<ShareTrack>()

        body.split(RECORD).forEach { record ->
            val fields = record.split(FIELD)
            when (fields.firstOrNull()) {
                "i" -> {
                    incident = unescape(fields.getOrElse(1) { "" }).ifBlank { "Shared" }
                    author = unescape(fields.getOrElse(2) { "" }).takeIf { it.isNotBlank() }
                }
                "p" -> {
                    val point = PolylineCodec.decodePoints(fields.getOrElse(2) { "" })
                        .firstOrNull() ?: return@forEach
                    pins += SharePin(
                        id = "",
                        title = unescape(fields.getOrElse(1) { "" }).ifBlank { "Pin" },
                        latitude = point.first,
                        longitude = point.second,
                        symbolId = unescape(fields.getOrElse(3) { "" }).takeIf { it.isNotBlank() },
                        note = unescape(fields.getOrElse(4) { "" }).takeIf { it.isNotBlank() }
                    )
                }
                "t" -> {
                    val shape = PolylineCodec.decodePoints(fields.getOrElse(2) { "" })
                    if (shape.size < 2) return@forEach
                    val times = PolylineCodec.decodeTimes(
                        fields.getOrElse(3) { "" }, shape.size
                    )
                    val points = shape.mapIndexed { index, position ->
                        SharePoint(
                            latitude = position.first,
                            longitude = position.second,
                            timeMillis = times[index].takeIf { it > 0 }
                        )
                    }
                    val stamps = points.mapNotNull { it.timeMillis }
                    tracks += ShareTrack(
                        id = "",
                        name = unescape(fields.getOrElse(1) { "" }).ifBlank { "Track" },
                        points = points,
                        startedAt = stamps.minOrNull(),
                        endedAt = stamps.maxOrNull()
                    )
                }
            }
        }

        if (pins.isEmpty() && tracks.isEmpty()) return null
        return SharePackage(
            incidentName = incident,
            tracks = tracks,
            pins = pins,
            author = author
        )
    }

    // -------------------------------------------------------------- helpers

    private fun List<String>.join() = joinToString(FIELD.toString())

    /**
     * Hides the characters that hold the format together.
     *
     * Whitespace too, because reading throws all of it away -- a pin called
     * "DP 12" has to come back with its space rather than as "DP12".
     */
    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when {
                character == '%' -> append("%25")
                character == FIELD -> append("%3B")
                character == RECORD -> append("%21")
                character == ' ' -> append("%20")
                character.isWhitespace() -> append("%20")
                else -> append(character)
            }
        }
    }

    private fun unescape(text: String): String {
        if (!text.contains('%')) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '%' && index + 2 < text.length) {
                val code = text.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    index += 3
                    continue
                }
            }
            out.append(text[index])
            index++
        }
        return out.toString()
    }

    /**
     * A short check that the parts belong together and arrived whole.
     *
     * FNV-1a, in base 36. Not a security measure -- nobody is attacking a
     * track. It is there to catch a part pasted from the wrong message and a
     * paste that stopped halfway, both of which otherwise produce a plausible
     * looking track that is wrong.
     */
    fun checksum(text: String): String {
        var hash = 0x811C9DC5u
        text.forEach { character ->
            hash = hash xor (character.code.toUInt() and 0xFFu)
            hash *= 0x01000193u
        }
        return hash.toString(36)
    }
}
