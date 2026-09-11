package com.rhecyee.firelinemap.location

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/**
 * Whether the phone is talking to a vehicle's head unit.
 *
 * Used as an arrival signal. A crew truck stops at a drop point, the engine
 * goes off, and the head unit goes with it -- so the connection dropping says
 * "we are here and somebody is getting out" more reliably than anything the
 * position stream can work out on its own. A drop point has to have been read
 * off a sheet, and read correctly, to exist at all; a stop threshold cannot
 * tell arriving apart from waiting at a gate. Turning the key off is not
 * ambiguous.
 *
 * Kept behind this small class so the recording service does not have to know
 * about the car library, and so the states it cares about are two rather than
 * three.
 */
class VehicleConnection(private val contextSource: () -> Context) {

    constructor(context: Context) : this({ context })

    /**
     * Resolved when watching starts, never when this is constructed.
     *
     * A Service's fields are initialised before the system attaches its base
     * context, so anything that reaches for the context while they run is
     * dereferencing a null. This class was a plain field on the recording
     * service and asked for `applicationContext` in its constructor, which
     * meant the service could not be created at all: every attempt to record
     * threw before onCreate, on the phone and on the car alike.
     *
     * The location client next to it is `by lazy` for exactly this reason.
     * Doing it here as well makes the class safe to hold whichever way it is
     * held, which is the part that can be tested.
     */
    private val app: Context by lazy { contextSource().applicationContext }
    private var source: LiveData<Int>? = null
    private var observer: Observer<Int>? = null

    /** Null until the first reading arrives. */
    var connected: Boolean? = null
        private set

    /**
     * Starts watching. [onChange] is called on the main thread with true when a
     * vehicle appears and false when it goes away, and only when that changes.
     *
     * Must be called from the main thread; LiveData requires it.
     */
    fun watch(onChange: (connected: Boolean) -> Unit) {
        if (observer != null) return
        val live = runCatching { CarConnection(app).type }.getOrNull() ?: return

        val watcher = Observer<Int> { type ->
            // Projection is Android Auto over a cable or wirelessly; native is
            // the app running on the head unit itself. Either one is a vehicle.
            val now = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
                type == CarConnection.CONNECTION_TYPE_NATIVE
            if (now == connected) return@Observer
            connected = now
            onChange(now)
        }
        source = live
        observer = watcher
        runCatching { live.observeForever(watcher) }
            .onFailure {
                source = null
                observer = null
            }
    }

    fun stop() {
        val live = source
        val watcher = observer
        source = null
        observer = null
        connected = null
        if (live != null && watcher != null) {
            runCatching { live.removeObserver(watcher) }
        }
    }
}
