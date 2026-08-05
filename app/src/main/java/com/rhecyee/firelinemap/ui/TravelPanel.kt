package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.location.LiveTrack
import com.rhecyee.firelinemap.location.TravelReadout

/**
 * Live travel readout.
 *
 * Present whenever recording is armed, including before a track opens, so
 * "armed but not yet moving" is visibly different from "not working". That
 * distinction is the whole reason automatic recording needs a readout at all.
 *
 * Every figure and every sentence comes from [TravelReadout], which the
 * browser also calls. The panel decides where things sit; it does not decide
 * what they say, because the two apps saying different things about the same
 * shift is worse than either saying nothing.
 */
@Composable
fun TravelPanel(
    live: LiveTrack,
    armed: Boolean,
    modifier: Modifier = Modifier
) {
    val figures = TravelReadout.of(
        recording = live.recording,
        paused = live.paused,
        armed = armed,
        elapsedMillis = live.elapsedMillis,
        distanceMeters = live.distanceMeters,
        movingMillis = live.movingMillis,
        pausedMillis = live.pausedMillis,
        pointCount = live.points.size,
        fixCount = live.fixCount,
        rejectedCount = live.rejectedCount,
        lastAccuracyMeters = live.lastAccuracyMeters.toDouble(),
        lastSpeedMetersPerSecond = live.lastSpeedMetersPerSecond,
        movingNow = live.movingNow,
        movingHeldMillis = live.movingHeldMillis
    )

    val accent = when (figures.accent) {
        TravelReadout.Accent.PAUSED -> Color(0xFFFFA000)
        TravelReadout.Accent.RECORDING -> Color(0xFFE91E63)
        TravelReadout.Accent.IDLE -> Color(0xFF90A4AE)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1E24), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            figures.state,
            color = accent,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelLarge
        )

        if (!figures.recording) {
            figures.waiting?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            figures.diagnostics?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Stat("ELAPSED", figures.elapsed)
            Stat("DISTANCE", figures.distance)
            Stat("POINTS", figures.points.toString())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Moving time beside elapsed, and an average for each. On a
            // division with gates and traffic these are different questions,
            // and quoting one without saying which is how a road gets reported
            // as half the speed it drives.
            Stat("MOVING", figures.moving)
            Stat("AVG", figures.averageSpeed)
            Stat("MOVING AVG", figures.movingSpeed)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Stat("CHAINS", figures.chains)
            figures.paused?.let { Stat("STOPPED", it) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            label,
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall
        )
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
