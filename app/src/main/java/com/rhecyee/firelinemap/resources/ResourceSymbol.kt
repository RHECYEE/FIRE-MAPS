package com.rhecyee.firelinemap.resources

/**
 * The operational symbols that can be dropped on a map.
 *
 * Deliberately a short, fixed list rather than a configurable symbology set.
 * This is an internal tool for one outfit, so the palette can be exactly what
 * that outfit runs and nothing else -- every symbol not on the list is a
 * decision nobody has to make with gloves on.
 *
 * [glyph] is drawn inside the pin, so it has to read at a glance in sunlight:
 * three characters at most.
 */
enum class ResourceSymbol(
    val id: String,
    val label: String,
    val glyph: String,
    val category: ResourceCategory,
    val colorArgb: Int
) {
    HAND_CREW("hand_crew", "Hand crew", "CRW", ResourceCategory.CREW, 0xFF2E7D32.toInt()),
    HOTSHOT("hotshot", "Hotshots", "IHC", ResourceCategory.CREW, 0xFF1B5E20.toInt()),
    HELITACK("helitack", "Helitack", "HTK", ResourceCategory.CREW, 0xFF00695C.toInt()),
    MEDIC("medic", "Medic", "MED", ResourceCategory.CREW, 0xFFC62828.toInt()),
    REMS("rems", "REMS", "REM", ResourceCategory.CREW, 0xFFAD1457.toInt()),
    DIVISION("division", "Division", "DIV", ResourceCategory.CREW, 0xFF4527A0.toInt()),

    ENGINE("engine", "Engine", "ENG", ResourceCategory.EQUIPMENT, 0xFFD84315.toInt()),
    TRUCK("truck", "Truck", "TRK", ResourceCategory.EQUIPMENT, 0xFFEF6C00.toInt()),
    UTV("utv", "UTV", "UTV", ResourceCategory.EQUIPMENT, 0xFFF9A825.toInt()),
    AMBULANCE("ambulance", "Ambulance", "AMB", ResourceCategory.EQUIPMENT, 0xFFB71C1C.toInt()),
    DOZER("dozer", "Dozer", "DOZ", ResourceCategory.EQUIPMENT, 0xFF5D4037.toInt()),
    WATER_TENDER("tender", "Water tender", "WT", ResourceCategory.EQUIPMENT, 0xFF0277BD.toInt()),
    HELICOPTER("helicopter", "Helicopter", "HEL", ResourceCategory.EQUIPMENT, 0xFF00838F.toInt()),

    ICP("icp", "ICP", "ICP", ResourceCategory.FACILITY, 0xFF283593.toInt()),
    STAGING("staging", "Staging", "STG", ResourceCategory.FACILITY, 0xFF3949AB.toInt()),
    DROP_POINT("drop_point", "Drop point", "DP", ResourceCategory.FACILITY, 0xFF1565C0.toInt()),
    HELISPOT("helispot", "Helispot", "H", ResourceCategory.FACILITY, 0xFF00ACC1.toInt()),

    /**
     * Where a medical happened, as opposed to the medic who is treating it.
     *
     * Kept apart from [MEDIC] because they answer different questions on a
     * radio -- one is a resource to send somewhere, the other is a place to
     * send it to -- but they used to carry the same three letters and sat next
     * to each other in the palette, which read as the same pin listed twice.
     */
    MEDICAL_INCIDENT(
        "medical_incident", "Medical incident", "911", ResourceCategory.POINT,
        0xFFD50000.toInt()
    ),
    HAZARD("hazard", "Hazard", "!", ResourceCategory.POINT, 0xFFE65100.toInt()),
    SNAG("snag", "Snag", "SNG", ResourceCategory.POINT, 0xFF8D6E63.toInt()),
    WATER("water", "Water", "H2O", ResourceCategory.POINT, 0xFF0288D1.toInt()),
    CAMP("camp", "Camp", "CMP", ResourceCategory.POINT, 0xFF388E3C.toInt()),
    INJURY("injury", "Injury", "+", ResourceCategory.POINT, 0xFFB71C1C.toInt()),
    PHOTO("photo", "Photo", "PIC", ResourceCategory.POINT, 0xFF7B1FA2.toInt()),
    ROCK("rock", "Rock", "RCK", ResourceCategory.POINT, 0xFF616161.toInt()),
    OTHER("other", "Point", "•", ResourceCategory.POINT, 0xFF546E7A.toInt());

    companion object {
        /**
         * Symbols no longer offered, and what they became.
         *
         * Retired rather than deleted. A pin dropped last season carries its
         * identifier into the database, and an identifier that resolves to
         * nothing turns a dozer somebody placed into a generic dot. Nothing
         * already on a map is allowed to change meaning because a list was
         * tidied.
         */
        private val RETIRED = mapOf("dozer_crew" to "dozer")

        fun byId(id: String?): ResourceSymbol {
            val wanted = RETIRED[id] ?: id
            return entries.firstOrNull { it.id == wanted } ?: OTHER
        }

        /**
         * The one palette.
         *
         * Crews and equipment lead because they are what gets placed most,
         * with hazards and facilities behind them. A separate point tool was
         * split off earlier and put back: two palettes meant deciding which
         * kind of thing a mark was before placing it, which is a filing
         * question asked at the worst possible moment.
         */
        val RESOURCES: List<ResourceSymbol> = run {
            val leading = listOf(HAND_CREW, ENGINE, DOZER, MEDIC, HAZARD, MEDICAL_INCIDENT)
            // distinct() rather than trusting the two lists not to overlap:
            // an entry appearing in both is how the palette came to show a
            // medic and a dozer twice each.
            (leading + entries).distinct()
        }

        /**
         * What the point tool offers.
         *
         * Not simply the POINT category. Hand crew, dozer and engine are the
         * marks that actually get dropped in the field, so they lead the list
         * even though the same symbols also exist as tracked resources with
         * identifiers and a position history.
         */
        val POINTS: List<ResourceSymbol> = (
            listOf(HAND_CREW, DOZER, ENGINE) +
                entries.filter { it.category == ResourceCategory.POINT }
            ).distinct()
    }
}

enum class ResourceCategory(val label: String) {
    CREW("Crews"),
    EQUIPMENT("Equipment"),
    FACILITY("Facilities"),

    /** Generic marks: hazards, water, gates. Not an assigned resource. */
    POINT("Points")
}
