package com.rhecyee.firelinemap.share

/**
 * Dropping copies of a record already held, and nothing else.
 *
 * The distinction this rests on is narrow and worth stating. It does not ask
 * whether two pins near each other are the same thing on the ground -- that is
 * a judgement about somebody's fire, it needs a radio and a person, and an app
 * that guesses it will sooner or later delete the pin that mattered.
 *
 * It asks a much smaller question: does this record encode to exactly the
 * bytes of one already here? If it does, it is not a second thing. It is the
 * same thing arriving twice, which happens constantly and for ordinary
 * reasons: a part pasted again after a message came through in the wrong
 * order, a package forwarded to somebody who already had it, and above all a
 * sender adding one track and re-sending the whole incident -- which without
 * this leaves the receiver with two of everything else.
 *
 * The comparison is on the encoded record rather than on the fields, because
 * the encoded record is what was actually agreed between the two phones. It
 * leaves out the id and the time a thing was placed, since those differ on
 * every phone holding the same pin.
 */
object ExactDuplicates {

    /** What came in, once the copies already held were taken out. */
    data class Result(
        val kept: SharePackage,
        val duplicatePins: Int,
        val duplicateTracks: Int
    ) {
        val duplicates: Int get() = duplicatePins + duplicateTracks

        /** "2 tracks · 1 pin added · 5 already here", for the confirmation. */
        fun describe(): String {
            val added = kept.describe().takeIf { !kept.isEmpty }
            return when {
                added == null && duplicates > 0 -> "Nothing new — all $duplicates already here"
                added == null -> "Nothing to add"
                duplicates > 0 -> "$added added · $duplicates already here"
                else -> "$added added"
            }
        }
    }

    /**
     * Filters [incoming] against what is already held.
     *
     * Duplicates within the incoming package itself are dropped too, so a
     * package that somehow carries the same record twice does not plant two.
     */
    fun filter(incoming: SharePackage, existing: SharePackage): Result {
        val heldPins = existing.pins.map { TextCodec.recordOf(it) }.toMutableSet()
        val heldTracks = existing.tracks.map { TextCodec.recordOf(it) }.toMutableSet()

        var duplicatePins = 0
        val pins = incoming.pins.filter { pin ->
            val record = TextCodec.recordOf(pin)
            if (!heldPins.add(record)) {
                duplicatePins++
                false
            } else {
                true
            }
        }

        var duplicateTracks = 0
        val tracks = incoming.tracks.filter { track ->
            val record = TextCodec.recordOf(track)
            if (!heldTracks.add(record)) {
                duplicateTracks++
                false
            } else {
                true
            }
        }

        return Result(
            kept = incoming.copy(pins = pins, tracks = tracks),
            duplicatePins = duplicatePins,
            duplicateTracks = duplicateTracks
        )
    }
}
