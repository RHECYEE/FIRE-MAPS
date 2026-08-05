package com.rhecyee.firelinemap.web

import com.rhecyee.firelinemap.land.LandOwnershipParser
import com.rhecyee.firelinemap.land.LandStatus
import com.rhecyee.firelinemap.land.LandStatusParser
import kotlin.js.json

/**
 * Whose ground this is.
 *
 * On a fire the question is usually who administers the ground rather than who
 * holds the deed: it decides who to notify, whose resources respond, and
 * whether structure protection applies. That question is answerable from
 * public services with no key and no agreement, which is why it is here at all.
 *
 * The browser does the fetching; the URLs and the parsing come from the phone's
 * own code. These services return a shape that has changed more than once, and
 * two apps reading it separately is two apps that disagree about a boundary.
 *
 * No owner name is reported, on either app. The federal layer is administrative
 * and names units, not people; private ground reads as "Private" and stops
 * there. Naming a landowner needs a commercial agreement that explicitly
 * authorises display, caching and export, and until there is one this does not
 * ask for it and has nowhere to put it.
 */
@JsExport
@JsName("FirelineLand")
object Land {

    fun ownerUrl(latitude: Double, longitude: Double): String =
        LandOwnershipParser.identifyUrl(latitude, longitude)

    fun protectedUnitUrl(latitude: Double, longitude: Double): String =
        LandStatusParser.protectedUnitUrl(latitude, longitude)

    fun countyUrl(latitude: Double, longitude: Double): String =
        LandStatusParser.countyUrl(latitude, longitude)

    fun stateUrl(latitude: Double, longitude: Double): String =
        LandStatusParser.stateUrl(latitude, longitude)

    /**
     * Reads whatever came back, in whatever order it arrived.
     *
     * Any of the three can be absent -- the protected-areas layer covering only
     * public land means an empty answer there usually means private rather than
     * broken, which is a distinction the readout has to keep.
     */
    fun statusOf(ownerJson: String?, unitJson: String?, countyJson: String?): String {
        val owner = ownerJson?.let { LandOwnershipParser.parseIdentify(it) }
        val unit = unitJson?.let { LandStatusParser.parseProtectedUnit(it) }
        val county = countyJson?.let { LandStatusParser.parseCounty(it) }
        val status = LandStatus(owner = owner, unit = unit, county = county)

        return JSON.stringify(
            json(
                "empty" to status.isEmpty,
                "headline" to status.headline(),
                "agency" to status.agency()?.label,
                "federal" to (status.agency()?.isFederal ?: false),
                "designation" to unit?.designationLabel(),
                "unit" to unit?.name,
                "county" to county?.label,
                "fips" to county?.fips,
                // Said out loud rather than implied by a blank: this layer is
                // generalised for national mapping and is not a land status
                // record, and a crew acting on it needs to know that.
                "surface" to when {
                    owner == null -> null
                    owner.isPrivate -> "Private land."
                    owner.agency.isFederal -> "Federal land."
                    else -> "Not federal."
                },
                "approximate" to (owner?.approximate ?: true)
            )
        )
    }
}
