package com.rhecyee.firelinemap.land

/** Who administers the surface, in the terms the agencies use for themselves. */
enum class LandAgency(
    val code: String,
    val label: String,
    val shortLabel: String,
    val colorArgb: Int
) {
    USFS("USFS", "US Forest Service", "USFS", 0xFF2E7D32.toInt()),
    BLM("BLM", "Bureau of Land Management", "BLM", 0xFFF9A825.toInt()),
    NPS("NPS", "National Park Service", "NPS", 0xFF00695C.toInt()),
    FWS("FWS", "Fish and Wildlife Service", "FWS", 0xFF0277BD.toInt()),
    BIA("BIA", "Bureau of Indian Affairs", "BIA", 0xFF6A1B9A.toInt()),
    BOR("BOR", "Bureau of Reclamation", "BOR", 0xFF00838F.toInt()),
    DOD("DOD", "Department of Defense", "DOD", 0xFF455A64.toInt()),
    USACE("USACE", "Army Corps of Engineers", "USACE", 0xFF546E7A.toInt()),
    STATE("STATE", "State", "State", 0xFF8D6E63.toInt()),
    PRIVATE("PVT", "Private", "Private", 0xFFB0BEC5.toInt()),
    OTHER("OTHER", "Other or mixed", "Other", 0xFF9E9E9E.toInt()),
    UNKNOWN("UNK", "Not established", "Unknown", 0xFF757575.toInt());

    val isFederal: Boolean
        get() = this in setOf(USFS, BLM, NPS, FWS, BIA, BOR, DOD, USACE)

    companion object {
        fun fromCode(code: String?): LandAgency {
            val needle = code?.trim()?.uppercase().orEmpty()
            if (needle.isEmpty() || needle == "NULL") return UNKNOWN
            entries.firstOrNull { it.code == needle }?.let { return it }
            return when (needle) {
                "FS" -> USFS
                "NP", "NPS " -> NPS
                "FW", "USFWS" -> FWS
                "IA" -> BIA
                "BR", "USBR" -> BOR
                "DOE", "DOI", "DOJ", "DOT" -> OTHER
                "ST", "STA" -> STATE
                "PVT", "PRI", "PRIVATE" -> PRIVATE
                else -> OTHER
            }
        }
    }
}

/**
 * What is administering a patch of ground.
 *
 * [approximate] is set whenever the answer came from the generalised national
 * layer. That layer is drawn for display at national scale, so a result near a
 * boundary can belong to the neighbour -- a query in Nebraska came back
 * carrying a Wyoming state code during testing. Good enough to know whose
 * ground a fire is on; not a substitute for a land status record.
 */
data class LandOwner(
    val agency: LandAgency,
    val departmentCode: String?,
    val unitName: String?,
    val stateCode: String?,
    val approximate: Boolean = true
) {
    /** "USFS · US Forest Service" or just "Private". */
    fun summary(): String = buildString {
        append(agency.label)
        unitName?.takeIf { it.isNotBlank() && !it.equals("Null", true) }?.let { append(" · $it") }
    }

    val isPrivate: Boolean get() = agency == LandAgency.PRIVATE
}

/**
 * Reads the BLM Surface Management Agency service.
 *
 * Federal land status, free, no key and no agreement -- which is why this is
 * built before any commercial parcel data. On a fire the question is usually
 * whose ground this is rather than who owns the deed: it decides who to
 * notify, whose resources respond, and whether structure protection applies.
 * That question is answerable for nothing.
 *
 * Parsing is separated from fetching so the shapes the service actually
 * returns can be pinned down in tests.
 */
object LandOwnershipParser {

    /** Reads an ArcGIS identify response. */
    fun parseIdentify(json: String): LandOwner? {
        val results = json.substringAfter("\"results\"", "").ifEmpty { return null }
        val agencyCode = field(results, "ADMIN_AGENCY_CODE")
        val departmentCode = field(results, "ADMIN_DEPT_CODE")
        if (agencyCode == null && departmentCode == null) return null

        // The department stands in where the agency is not filled out.
        val agency = LandAgency.fromCode(agencyCode).takeIf { it != LandAgency.UNKNOWN }
            ?: LandAgency.fromCode(departmentCode)

        return LandOwner(
            agency = agency,
            departmentCode = departmentCode?.takeIf { !it.equals("Null", true) },
            unitName = field(results, "ADMIN_UNIT_NAME")?.takeIf { !it.equals("Null", true) },
            stateCode = field(results, "ADMIN_ST")?.takeIf { !it.equals("Null", true) },
            approximate = true
        )
    }

    /**
     * Pulls a value out of the response.
     *
     * A deliberate scan rather than a JSON parse: `org.json` is only a stub on
     * the unit test classpath, and the shape wanted here is one flat
     * attributes object.
     */
    internal fun field(json: String, name: String): String? {
        val marker = "\"$name\""
        val at = json.indexOf(marker)
        if (at < 0) return null
        var index = at + marker.length
        while (index < json.length && json[index] != ':') index++
        index++
        while (index < json.length && json[index].isWhitespace()) index++
        if (index >= json.length) return null

        return if (json[index] == '"') {
            val end = json.indexOf('"', index + 1)
            if (end < 0) null else json.substring(index + 1, end).takeIf { it.isNotBlank() }
        } else {
            val end = generateSequence(index) { it + 1 }
                .first { it >= json.length || json[it] == ',' || json[it] == '}' }
            json.substring(index, end).trim().takeIf { it.isNotBlank() && it != "null" }
        }
    }

    const val ENDPOINT =
        "https://gis.blm.gov/arcgis/rest/services/lands/" +
            "BLM_Natl_SMA_Cached_without_PriUnk/MapServer/identify"

    /** The identify query for a single position. */
    fun identifyUrl(latitude: Double, longitude: Double): String {
        val extent = "${longitude - 0.1},${latitude - 0.1},${longitude + 0.1},${latitude + 0.1}"
        return "$ENDPOINT?geometry=%7B%22x%22:$longitude,%22y%22:$latitude%7D" +
            "&geometryType=esriGeometryPoint&sr=4326&layers=top&tolerance=1" +
            "&mapExtent=$extent&imageDisplay=400,400,96&returnGeometry=false&f=json"
    }
}
