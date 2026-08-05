package com.rhecyee.firelinemap.share

/**
 * Reading and writing GPX, with the app's own fields carried alongside.
 *
 * GPX rather than a format of this app's own, because the point of sharing is
 * that it arrives somewhere. A GPX opens in Gaia, CalTopo, Avenza, Garmin
 * Basecamp and every agency GIS -- including on an iPhone, today, with nothing
 * installed from us. A private format would only ever reach another copy of
 * this app.
 *
 * What GPX has no place for -- which symbol a pin is, what a resource's status
 * was, which incident it came off -- goes in the extensions block the standard
 * provides for exactly this. Anything else reading the file ignores it and
 * still gets the position and the name; another copy of this app gets all of
 * it back.
 *
 * Deliberately free of platform types so the same reader and writer serve the
 * phone app and the web one. A file written on one and read on the other has
 * to mean the same thing, and two implementations is how that stops being
 * true.
 */
object GpxFormat {

    const val EXTENSION = "gpx"
    const val MIME_TYPE = "application/gpx+xml"
    private const val NAMESPACE = "https://rhecyee.com/fireline/1"

    fun write(pkg: SharePackage): String {
        val out = StringBuilder(1024)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"Fireline Map\"\n")
        out.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        out.append("     xmlns:fireline=\"").append(NAMESPACE).append("\">\n")

        out.append("  <metadata>\n")
        out.append("    <name>").append(escape(pkg.incidentName)).append("</name>\n")
        if (pkg.createdAt > 0) {
            out.append("    <time>").append(IsoTime.format(pkg.createdAt)).append("</time>\n")
        }
        pkg.author?.takeIf { it.isNotBlank() }?.let {
            out.append("    <author><name>").append(escape(it)).append("</name></author>\n")
        }
        out.append("    <extensions>\n")
        out.append("      <fireline:incident>").append(escape(pkg.incidentName))
            .append("</fireline:incident>\n")
        out.append("    </extensions>\n")
        out.append("  </metadata>\n")

        pkg.pins.forEach { pin -> writePin(out, pin) }
        pkg.tracks.forEach { track -> writeTrack(out, track) }

        out.append("</gpx>\n")
        return out.toString()
    }

    private fun writePin(out: StringBuilder, pin: SharePin) {
        out.append("  <wpt lat=\"").append(coordinate(pin.latitude))
            .append("\" lon=\"").append(coordinate(pin.longitude)).append("\">\n")
        out.append("    <name>").append(escape(pin.title)).append("</name>\n")
        pin.note?.takeIf { it.isNotBlank() }?.let {
            out.append("    <desc>").append(escape(it)).append("</desc>\n")
        }
        pin.createdAt?.takeIf { it > 0 }?.let {
            out.append("    <time>").append(IsoTime.format(it)).append("</time>\n")
        }
        // Also written as <sym>, which is the field other programs actually
        // read, so a pin arriving in Gaia is not just an unlabelled dot.
        pin.symbolId?.let {
            out.append("    <sym>").append(escape(it)).append("</sym>\n")
        }
        out.append("    <extensions>\n")
        out.append("      <fireline:id>").append(escape(pin.id)).append("</fireline:id>\n")
        pin.symbolId?.let {
            out.append("      <fireline:symbol>").append(escape(it))
                .append("</fireline:symbol>\n")
        }
        pin.status?.takeIf { it.isNotBlank() }?.let {
            out.append("      <fireline:status>").append(escape(it))
                .append("</fireline:status>\n")
        }
        out.append("    </extensions>\n")
        out.append("  </wpt>\n")
    }

    private fun writeTrack(out: StringBuilder, track: ShareTrack) {
        out.append("  <trk>\n")
        out.append("    <name>").append(escape(track.name)).append("</name>\n")
        track.note?.takeIf { it.isNotBlank() }?.let {
            out.append("    <desc>").append(escape(it)).append("</desc>\n")
        }
        out.append("    <extensions>\n")
        out.append("      <fireline:id>").append(escape(track.id)).append("</fireline:id>\n")
        track.activityType?.let {
            out.append("      <fireline:activity>").append(escape(it))
                .append("</fireline:activity>\n")
        }
        if (track.distanceMeters > 0) {
            out.append("      <fireline:distanceMeters>")
                .append(round(track.distanceMeters, 1))
                .append("</fireline:distanceMeters>\n")
        }
        out.append("    </extensions>\n")
        out.append("    <trkseg>\n")
        track.points.forEach { point ->
            out.append("      <trkpt lat=\"").append(coordinate(point.latitude))
                .append("\" lon=\"").append(coordinate(point.longitude)).append("\">")
            point.elevationMeters?.let {
                out.append("<ele>").append(round(it, 1)).append("</ele>")
            }
            point.timeMillis?.takeIf { it > 0 }?.let {
                out.append("<time>").append(IsoTime.format(it)).append("</time>")
            }
            out.append("</trkpt>\n")
        }
        out.append("    </trkseg>\n")
        out.append("  </trk>\n")
    }

    /**
     * Reads a GPX back, whoever wrote it.
     *
     * Hand-scanned rather than run through an XML parser: there is no parser
     * common to both platforms this has to work on, the shape being read is
     * small and fixed, and a whole parser is a large thing to carry for six
     * element names. Tolerant by design -- a file from another program that
     * only has positions and names still produces usable tracks and pins.
     *
     * Returns null only when there is no GPX here at all.
     */
    fun read(xml: String): SharePackage? {
        if (!xml.contains("<gpx")) return null

        val incident = firstTag(xml, "fireline:incident")
            ?: firstTag(metadataOf(xml) ?: "", "name")
            ?: "Shared"
        val author = firstTag(metadataOf(xml) ?: "", "name", within = "author")
        val createdAt = firstTag(metadataOf(xml) ?: "", "time")?.let { IsoTime.parse(it) } ?: 0L

        val pins = blocks(xml, "wpt").mapNotNull { readPin(it) }
        val tracks = blocks(xml, "trk").mapNotNull { readTrack(it) }
        if (pins.isEmpty() && tracks.isEmpty()) return null

        return SharePackage(
            incidentName = incident,
            tracks = tracks,
            pins = pins,
            createdAt = createdAt,
            author = author
        )
    }

    private fun readPin(block: String): SharePin? {
        val latitude = attribute(block, "lat")?.toDoubleOrNull() ?: return null
        val longitude = attribute(block, "lon")?.toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return SharePin(
            id = firstTag(block, "fireline:id") ?: "",
            title = firstTag(block, "name") ?: "Pin",
            latitude = latitude,
            longitude = longitude,
            symbolId = firstTag(block, "fireline:symbol") ?: firstTag(block, "sym"),
            note = firstTag(block, "desc"),
            status = firstTag(block, "fireline:status"),
            createdAt = firstTag(block, "time")?.let { IsoTime.parse(it) }
        )
    }

    private fun readTrack(block: String): ShareTrack? {
        val points = POINT.findAll(block).mapNotNull { match ->
            // A point with no elevation or time is written self-closing by
            // plenty of programs, and that form carries its attributes in the
            // third group rather than the first.
            val head = match.groupValues[1].ifEmpty { match.groupValues[3] }
            val body = match.groupValues[2]
            val latitude = attribute(head, "lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val longitude = attribute(head, "lon")?.toDoubleOrNull() ?: return@mapNotNull null
            if (!latitude.isFinite() || !longitude.isFinite()) return@mapNotNull null
            SharePoint(
                latitude = latitude,
                longitude = longitude,
                timeMillis = firstTag(body, "time")?.let { IsoTime.parse(it) },
                elevationMeters = firstTag(body, "ele")?.toDoubleOrNull()
            )
        }.toList()
        if (points.isEmpty()) return null

        val times = points.mapNotNull { it.timeMillis }
        return ShareTrack(
            id = firstTag(block, "fireline:id") ?: "",
            name = firstTag(block, "name") ?: "Track",
            points = points,
            startedAt = times.minOrNull(),
            endedAt = times.maxOrNull(),
            distanceMeters = firstTag(block, "fireline:distanceMeters")?.toDoubleOrNull() ?: 0.0,
            activityType = firstTag(block, "fireline:activity"),
            note = firstTag(block, "desc")
        )
    }

    private fun metadataOf(xml: String): String? = blocks(xml, "metadata").firstOrNull()

    /** Every block for an element, including self-closing ones. */
    private fun blocks(xml: String, element: String): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        val open = "<$element"
        val close = "</$element>"
        while (true) {
            val start = xml.indexOf(open, index)
            if (start < 0) break
            // "<trk" must not match "<trkpt" or "<trkseg".
            val after = xml.getOrNull(start + open.length)
            if (after != null && after != ' ' && after != '>' && after != '\n' &&
                after != '\r' && after != '\t' && after != '/'
            ) {
                index = start + open.length
                continue
            }
            val openEnd = xml.indexOf('>', start)
            if (openEnd < 0) break

            // Whether it is self-closing has to be decided from this element's
            // own opening tag. Looking for a closing tag first finds the next
            // element's, and swallows everything in between.
            if (xml[openEnd - 1] == '/') {
                out += xml.substring(start, openEnd + 1)
                index = openEnd + 1
                continue
            }

            val end = xml.indexOf(close, openEnd)
            if (end < 0) {
                index = openEnd + 1
                continue
            }
            out += xml.substring(start, end + close.length)
            index = end + close.length
        }
        return out
    }

    private fun firstTag(xml: String, tag: String, within: String? = null): String? {
        val scope = if (within == null) xml else blocks(xml, within).firstOrNull() ?: return null
        val open = "<$tag>"
        val start = scope.indexOf(open)
        if (start < 0) return null
        val end = scope.indexOf("</$tag>", start)
        if (end < 0) return null
        return unescape(scope.substring(start + open.length, end)).trim().takeIf { it.isNotEmpty() }
    }

    private fun attribute(xml: String, name: String): String? {
        val match = Regex("""\b$name\s*=\s*"([^"]*)"""").find(xml) ?: return null
        return match.groupValues[1]
    }

    /**
     * Seven decimal places, which is about a centimetre.
     *
     * Enough that a position survives the trip exactly as far as it was ever
     * known, and short enough that a shift of tracks stays a file somebody can
     * send over a message.
     */
    private fun coordinate(value: Double): String = round(value, 7)

    private fun round(value: Double, places: Int): String {
        if (!value.isFinite()) return "0"
        var scale = 1L
        repeat(places) { scale *= 10 }
        val scaled = kotlin.math.round(value * scale).toLong()
        val negative = scaled < 0
        val magnitude = if (negative) -scaled else scaled
        val whole = magnitude / scale
        val fraction = magnitude % scale
        if (fraction == 0L) return (if (negative) "-" else "") + whole
        val digits = fraction.toString().padStart(places, '0').trimEnd('0')
        return (if (negative) "-" else "") + whole + "." + digits
    }

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // Ampersand last, or "&amp;lt;" turns into "<".
        .replace("&amp;", "&")

    // "[\s\S]" rather than "." with DOT_MATCHES_ALL: that option is only on
    // the JVM, and this reader has to run in a browser too.
    private val POINT = Regex("""<trkpt\b([^>]*)>([\s\S]*?)</trkpt>|<trkpt\b([^>]*)/>""")
}
