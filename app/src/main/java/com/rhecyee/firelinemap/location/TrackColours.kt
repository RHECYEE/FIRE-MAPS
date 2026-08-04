package com.rhecyee.firelinemap.location

/**
 * A colour per track, so a day's worth of them can be told apart.
 *
 * Every saved track used to be the same purple. Two runs up the same road, a
 * shuttle to camp and the drive out all drew over each other as one shape, and
 * the only way to find out which was which was to tap each in turn.
 *
 * Derived from the track's identifier rather than from its position in a list,
 * so a track keeps its colour when others are added, deleted or reordered. A
 * colour that moves is worse than no colour: it silently re-labels the map
 * between one look and the next.
 *
 * Deliberately avoids everything else on the map. Pink is live recording, blue
 * is the operator, yellow is a measurement, brown is terrain, cyan is a drop
 * point and red means medical or off-sheet -- so none of those appear here.
 */
object TrackColours {

    /**
     * Chosen to stay apart from each other on a topographic background, in
     * daylight, on a screen that has been rained on.
     */
    val PALETTE = listOf(
        0xFF7B1FA2, // purple
        0xFF00838F, // teal
        0xFF558B2F, // olive
        0xFF4527A0, // indigo
        0xFF00695C, // deep green
        0xFF283593, // navy
        0xFF6A1B9A, // violet
        0xFF01579B  // steel blue
    ).map { it.toInt() }

    /** The colour for a track, stable for the life of its identifier. */
    fun forId(id: String): Int {
        if (id.isEmpty()) return PALETTE.first()
        // A plain sum rather than String.hashCode: the JVM's is stable, but
        // this is written into how a map looks and is worth not depending on.
        var accumulated = 0
        for (character in id) accumulated = (accumulated * 31 + character.code) and 0x7FFFFFFF
        return PALETTE[accumulated % PALETTE.size]
    }
}
