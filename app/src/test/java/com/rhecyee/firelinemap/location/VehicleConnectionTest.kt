package com.rhecyee.firelinemap.location

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * When the vehicle watcher is allowed to touch its context.
 *
 * A Service's fields are initialised before the system attaches its base
 * context. Held as a plain field on the recording service, this class asked
 * for `applicationContext` in its constructor and dereferenced a null, so the
 * service could not be created at all -- every attempt to record threw before
 * onCreate ever ran, on the phone and on the car alike. The location client
 * beside it is `by lazy` for the same reason.
 */
class VehicleConnectionTest {

    @Test
    fun `constructing the watcher never reaches for the context`() {
        var asked = false
        VehicleConnection({
            asked = true
            error("the context was read while the service was still being built")
        })
        assertFalse("the context was read during construction", asked)
    }

    @Test
    fun `it is still not read when the watcher is simply held`() {
        var asked = false
        val connection = VehicleConnection({
            asked = true
            error("the context was read too early")
        })
        // Holding it, reading its state, letting it go: none of that is a
        // reason to resolve a context that may not exist yet.
        connection.connected
        connection.stop()
        assertFalse("the context was read before watching began", asked)
    }

    @Test
    fun `a context handed over directly is deferred the same way`() {
        // The convenience constructor the service uses must not be a way back
        // in to the eager behaviour.
        var built = false
        val supplier = {
            built = true
            @Suppress("UNCHECKED_CAST")
            (null as Context?)!!
        }
        VehicleConnection(supplier)
        assertFalse(built)
    }
}
