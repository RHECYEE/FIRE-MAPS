package com.rhecyee.firelinemap.land

/**
 * Everything free sources will say about a patch of ground.
 *
 * Three answers rather than one, because no single free service gives a whole
 * one and they fail in different places. The surface layer knows which agency
 * administers ground but calls it "USDA/USFS/OR"; the protected-areas record
 * knows it as Whitman National Forest but has nothing at all to say about
 * private land; the census knows the county everywhere, which is what decides
 * who dispatches and who gets called for mutual aid.
 *
 * Named separately so a crew can see which part is confident. A unit name is
 * something to say on the radio. An agency code is something to plan around.
 * A county is something to call.
 */
data class LandStatus(
    val owner: LandOwner? = null,
    val unit: ProtectedUnit? = null,
    val county: CountyLocation? = null
) {
    val isEmpty: Boolean get() = owner == null && unit == null && county == null

    /**
     * The best single line for the ground.
     *
     * The unit name wins when there is one -- "Whitman National Forest" is
     * what anyone would actually say -- and the agency stands in when there is
     * not.
     */
    fun headline(): String? = unit?.name ?: owner?.summary() ?: county?.let { "${it.label}" }

    /** The agency to plan around, from whichever source knows it. */
    fun agency(): LandAgency? =
        unit?.agency?.takeIf { it != LandAgency.UNKNOWN && it != LandAgency.OTHER }
            ?: owner?.agency?.takeIf { it != LandAgency.UNKNOWN }
            ?: unit?.agency
}

/**
 * A named unit from the protected areas database.
 *
 * This is the layer that knows ground by the name people use for it. It only
 * covers protected and public land, so nothing coming back is a real answer --
 * it usually means private -- and never an error.
 */
data class ProtectedUnit(
    val name: String,
    val ownerCode: String?,
    val managerCode: String?,
    val managerType: String?,
    val designation: String?,
    val localOwner: String?
) {
    val agency: LandAgency get() = LandAgency.fromCode(managerCode ?: ownerCode)

    /** "National Forest", "National Park", from the designation code. */
    fun designationLabel(): String? = when (designation?.uppercase()) {
        "NF" -> "National Forest"
        "NP" -> "National Park"
        "NM" -> "National Monument"
        "WA" -> "Wilderness Area"
        "NWR" -> "National Wildlife Refuge"
        "NG" -> "National Grassland"
        "NRA" -> "National Recreation Area"
        "SRMA" -> "Special Recreation Management Area"
        "PUB" -> "Public land"
        "SF" -> "State Forest"
        "SP" -> "State Park"
        "LP" -> "Local park"
        "PRI" -> "Private conservation land"
        null, "" -> null
        else -> designation
    }

    /** Whether the manager is a state rather than a federal body. */
    val isState: Boolean get() = managerType?.uppercase() == "STAT"

    val isFederal: Boolean get() = managerType?.uppercase() == "FED"
}

/** Which county the ground is in, which is who to call. */
data class CountyLocation(
    val county: String,
    val stateCode: String?,
    val stateName: String?,
    val fips: String?
) {
    /** "Union County, OR". */
    val label: String
        get() = buildString {
            append(county)
            if (!county.endsWith("County", true) && !county.contains(" ")) append(" County")
            stateCode?.let { append(", $it") }
        }
}

/**
 * Reads the free land services.
 *
 * Kept apart from fetching so the shapes each service actually returns can be
 * pinned down against real responses rather than guessed at. Every parser here
 * was written against a live reply from the ground it describes.
 */
object LandStatusParser {

    /**
     * PAD-US, the USGS protected areas database.
     *
     * Public domain, no key. Gives the name people use for the ground --
     * Whitman National Forest, Newcastle Field Office, Glacier National Park
     * -- which is the thing worth having and which the surface layer does not
     * carry.
     */
    fun parseProtectedUnit(json: String): ProtectedUnit? {
        val attributes = firstAttributes(json) ?: return null
        val name = field(attributes, "Unit_Nm")?.takeIf { usable(it) } ?: return null
        return ProtectedUnit(
            name = name,
            ownerCode = field(attributes, "Own_Name")?.takeIf { usable(it) },
            managerCode = field(attributes, "Mang_Name")?.takeIf { usable(it) },
            managerType = field(attributes, "Mang_Type")?.takeIf { usable(it) },
            designation = field(attributes, "Des_Tp")?.takeIf { usable(it) },
            localOwner = field(attributes, "Loc_Own")?.takeIf { usable(it) }
        )
    }

    /** Census TIGERweb, which answers everywhere including on private ground. */
    fun parseCounty(json: String, stateJson: String? = null): CountyLocation? {
        val attributes = firstAttributes(json) ?: return null
        val name = field(attributes, "BASENAME")?.takeIf { usable(it) } ?: return null
        val stateFips = field(attributes, "STATE")
        val countyFips = field(attributes, "COUNTY")
        val state = stateJson?.let { firstAttributes(it) }
        return CountyLocation(
            county = name,
            stateCode = state?.let { field(it, "STUSAB") }?.takeIf { usable(it) },
            stateName = state?.let { field(it, "BASENAME") }?.takeIf { usable(it) },
            fips = if (stateFips != null && countyFips != null) "$stateFips$countyFips" else null
        )
    }

    /**
     * The first feature's attributes.
     *
     * A deliberate scan rather than a JSON parse: `org.json` is only a stub on
     * the unit test classpath, and what is wanted is one flat attributes
     * object out of a response whose shape is known.
     */
    internal fun firstAttributes(json: String): String? {
        val at = json.indexOf("\"attributes\"")
        if (at < 0) return null
        val open = json.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (index in open until json.length) {
            when (json[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open, index + 1)
                }
            }
        }
        return null
    }

    internal fun field(json: String, name: String): String? =
        LandOwnershipParser.field(json, name)

    /** These services write "Unknown" and "Null" where they mean nothing. */
    private fun usable(value: String): Boolean =
        value.isNotBlank() && !value.equals("null", true) && !value.equals("unknown", true)

    /**
     * PAD-US fee managers, queried for one point.
     *
     * Public domain, no key, no agreement -- the same test every source in
     * this app has to pass.
     */
    const val PROTECTED_ENDPOINT =
        "https://services.arcgis.com/v01gqwM5QqNysAAi/arcgis/rest/services/" +
            "Fee_Managers_PADUS/FeatureServer/0/query"

    const val COUNTY_ENDPOINT =
        "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/" +
            "State_County/MapServer/1/query"

    const val STATE_ENDPOINT =
        "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/" +
            "State_County/MapServer/0/query"

    fun protectedUnitUrl(latitude: Double, longitude: Double): String =
        "$PROTECTED_ENDPOINT?geometry=$longitude%2C$latitude" +
            "&geometryType=esriGeometryPoint&inSR=4326" +
            "&spatialRel=esriSpatialRelIntersects" +
            "&outFields=Own_Name%2CMang_Name%2CMang_Type%2CLoc_Own%2CUnit_Nm%2CDes_Tp" +
            "&returnGeometry=false&f=json"

    fun countyUrl(latitude: Double, longitude: Double): String =
        "$COUNTY_ENDPOINT?geometry=$longitude%2C$latitude" +
            "&geometryType=esriGeometryPoint&inSR=4326" +
            "&spatialRel=esriSpatialRelIntersects" +
            "&outFields=BASENAME%2CSTATE%2CCOUNTY&returnGeometry=false&f=json"

    fun stateUrl(latitude: Double, longitude: Double): String =
        "$STATE_ENDPOINT?geometry=$longitude%2C$latitude" +
            "&geometryType=esriGeometryPoint&inSR=4326" +
            "&spatialRel=esriSpatialRelIntersects" +
            "&outFields=BASENAME%2CSTUSAB&returnGeometry=false&f=json"

    /** Named on screen, because a crew should know what answered. */
    const val PROTECTED_ATTRIBUTION = "USGS Protected Areas Database (PAD-US)"
    const val COUNTY_ATTRIBUTION = "US Census TIGERweb"
}
