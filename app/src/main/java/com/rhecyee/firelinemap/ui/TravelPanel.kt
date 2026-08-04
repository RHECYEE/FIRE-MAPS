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
import com.rhecyee.firelinemap.measure.DistanceUnit

/**
 * Live travel readout.
 *
 * Present whenever recording is armed, including before a track opens, so
 * "armed but not yet moving" is visibly different from "not working". That
 * distinction is the whole reason automatic recording needs a readout at all.
 */
@Composable
fun TravelPanel(
    live: LiveTrack,
    armed: Boolean,
    unit: DistanceUnit,
    modifier: Modifier = Modifier
) {
    val accent = when {
        live.paused -> Color(0xFFFFA000)
        live.recording -> Color(0xFFE91E63)
        else -> Color(0xFF90A4AE)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1E24), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            when {
                live.paused -> "TRAVEL PAUSED"
                live.recording -> "TRAVEL RECORDING"
                armed -> "WATCHING FOR TRAVEL — start moving"
                else -> "NOT RECORDING"
            },
            color = accent,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelLarge
        )

        if (!live.recording) {
            Text(
                if (armed) {
                    "A track opens once movement holds for about 30 seconds."
                } else {
                    "Press auto record to arm."
                },
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Stat("ELAPSED", clock(live.elapsedMillis))
            Stat("DISTANCE", "%.2f %s".format(unit.from(live.distanceMeters), unit.label))
            Stat("POINTS", live.points.size.toString())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Stat("MOVING", clock(live.movingMillis))
            Stat("AVG", speed(live.averageSpeedMetersPerSecond))
            Stat("MOVING AVG", speed(live.movingSpeedMetersPerSecond))
        }
        if (live.pausedMillis > 0) {
            Text(
                "Paused ${clock(live.pausedMillis)} of this track",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
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

private fun clock(millis: Long): String {
    val total = millis / 1000
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

/** Miles per hour, which is what a vehicle speedometer reads. */
private fun speed(metersPerSecond: Double): String =
    "%.1f mph".format(metersPerSecond * 2.236936)
