package com.rhecyee.firelinemap.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Fireline Map on the head unit.
 *
 * The case this exists for: driving a division with the phone in a cradle,
 * where the thing needed is not a menu but a map showing the road being driven,
 * the track being laid down behind, and the drop points ahead. Reaching for the
 * phone to see that is the behaviour worth designing out.
 *
 * Deliberately small. A car display is glanceable, not interactive: everything
 * that involves reading, typing or deciding stays on the phone.
 */
class FirelineCarAppService : CarAppService() {

    /**
     * Which hosts may drive this app.
     *
     * [HostValidator.ALLOW_ALL_HOSTS_VALIDATOR] is for development only and the
     * library says so. This app is sideloaded onto the company's own phones
     * rather than distributed, and the alternative -- pinning Google's signing
     * certificate -- makes it untestable on a desk with no head unit. If this
     * is ever published, this is the line that has to change.
     */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = FirelineSession()

    @Deprecated("Kept for hosts that have not moved to the SessionInfo overload.")
    override fun onCreateSession(): Session = FirelineSession()
}
