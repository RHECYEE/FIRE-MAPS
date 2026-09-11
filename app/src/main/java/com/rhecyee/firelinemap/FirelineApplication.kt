package com.rhecyee.firelinemap

import android.app.Application
import com.rhecyee.firelinemap.data.FirelineDatabase
import com.rhecyee.firelinemap.location.LocationRepository
import com.rhecyee.firelinemap.location.SegmentAnchor
import com.rhecyee.firelinemap.util.CrashLog

class FirelineApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before anything else, so a crash while the rest is being set up is
        // still written down. See CrashLog: there is no terminal on a phone in
        // a truck, and "it crashes" is a whole round trip short of a cause.
        CrashLog.install(this, BuildConfig.VERSION_NAME)
    }

    val database: FirelineDatabase by lazy { FirelineDatabase.create(this) }

    /**
     * The one live-position feed.
     *
     * Held here because the phone screen is no longer the only thing that
     * needs it: the Android Auto screen can be brought up with the activity
     * never having run this shift. A second repository would mean a second
     * fused-location subscription draining the same battery for the same fixes.
     */
    val location: LocationRepository by lazy { LocationRepository(this) }

    /**
     * Drop points read off the active sheet.
     *
     * Held in the application rather than the database because they are
     * derived from whichever sheet is loaded right now, not incident data, and
     * are re-derived whenever that changes. The recording service reads them
     * to decide where to segment travel.
     */
    @Volatile
    var dropPoints: List<SegmentAnchor> = emptyList()
}
