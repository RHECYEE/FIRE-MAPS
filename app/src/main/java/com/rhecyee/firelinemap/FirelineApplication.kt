package com.rhecyee.firelinemap

import android.app.Application
import com.rhecyee.firelinemap.data.FirelineDatabase

class FirelineApplication : Application() {
    val database: FirelineDatabase by lazy { FirelineDatabase.create(this) }
}
