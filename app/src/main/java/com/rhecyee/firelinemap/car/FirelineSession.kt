package com.rhecyee.firelinemap.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** One connection to a head unit. Opens on the map and stays there. */
class FirelineSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarMapScreen(carContext)
}
