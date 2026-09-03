package com.rhecyee.firelinemap.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The entry point Android Auto binds to.
 *
 * Its presence in the manifest, with the navigation category on the intent
 * filter and the `com.google.android.gms.car.application` descriptor alongside
 * it, is what puts this app in the car launcher at all. Without this service
 * the host has nothing to discover and the app simply never appears.
 */
class FirelineCarAppService : CarAppService() {

    /**
     * Which hosts may drive this app.
     *
     * Debug builds accept any host so the app can be brought up against the
     * Desktop Head Unit while it is being worked on. Release builds accept only
     * the Android Auto and Automotive template hosts, whose signatures the
     * library ships; that is what stops another app on the phone binding this
     * service and reading the crew's positions off it. The array's name says
     * "sample" but it is the real list and is what it is there to be used for.
     */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = FirelineSession()
}
