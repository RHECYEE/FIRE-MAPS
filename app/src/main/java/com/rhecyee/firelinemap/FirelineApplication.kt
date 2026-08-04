package com.rhecyee.firelinemap

import android.app.Application
import com.rhecyee.firelinemap.data.FirelineDatabase
import com.rhecyee.firelinemap.location.SegmentAnchor

class FirelineApplication : Application() {
    val database: FirelineDatabase by lazy { FirelineDatabase.create(this) }

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
