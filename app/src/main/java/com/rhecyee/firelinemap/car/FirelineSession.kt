package com.rhecyee.firelinemap.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.rhecyee.firelinemap.FirelineApplication

/**
 * One connection to the car.
 *
 * The renderer is owned here rather than by the screen so it outlives any
 * screen pushed on top of the map: the surface belongs to the connection, and
 * re-creating it every time a screen changes would blank the display.
 */
class FirelineSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val application = carContext.applicationContext as FirelineApplication
        // The car can be the first thing to bring the app up -- the phone may
        // never have been opened this shift -- so the GPS is started here as
        // well rather than only from the phone screen.
        application.location.start()

        val renderer = CarMapRenderer(carContext)
        renderer.attach(this)
        return FirelineMapScreen(carContext, renderer)
    }
}
