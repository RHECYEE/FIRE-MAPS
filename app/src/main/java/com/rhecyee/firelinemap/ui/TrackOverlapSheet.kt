package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.location.OverlapReport
import com.rhecyee.firelinemap.location.TrackPass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every pass over one spot, read together.
 *
 * The question is travel time. A road driven five times is five answers to how
 * long it takes, and the useful number is not any one of them -- it is what
 * the road actually runs at, which only appears once they are read together.
 * Division supervisors ask this at every briefing and the honest answer has
 * always been a guess.
 *
 * Nothing is merged away. Each pass stays its own entry, because a pass on a
 * Tuesday in a tender and a pass on a Thursday in a buggy are two facts, and
 * deciding which to discard is not this app's call.
 */
@Composable
fun TrackOverlapSheet(
    report: OverlapReport,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tracks through here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${report.passes.size} pass" +
                                (if (report.passes.size == 1) "" else "es") + " through here",
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleMedium
                        )
                        // The totals, which is what the operator came for: how
                        // long this road takes and how fast it runs, across
                        // everybody who has been down it.
                        report.averageTrackSpeed?.let {
                            Figure("Average speed", OverlapReport.formatSpeed(it))
                        }
                        report.averageElapsedMillis?.let {
                            Figure("Average time", OverlapReport.formatElapsed(it))
                        }
                        if (report.totalDistanceMeters > 0) {
                            Figure(
                                "Total distance",
                                OverlapReport.formatDistance(report.totalDistanceMeters)
                            )
                        }
                        if (report.totalElapsedMillis > 0) {
                            Figure(
                                "Total time",
                                OverlapReport.formatElapsed(report.totalElapsedMillis)
                            )
                        }
                        report.averageSpeedMetersPerSecond?.let {
                            Figure("Speed at this spot", OverlapReport.formatSpeed(it))
                        }

                        // The average leaves stops out, and says so, because a
                        // number in a briefing that quietly included a lunch
                        // break is worse than no number.
                        if (report.stoppedCount > 0) {
                            Text(
                                "${report.stoppedCount} of ${report.passes.size} were " +
                                    "stopped here and are left out of the spot speed.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (report.passes.any { it.trackAverageSpeed == null }) {
                            Text(
                                "Some passes carry no times — recorded before times " +
                                    "were kept, or imported without them.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(report.passes, key = { it.trackId }) { pass -> PassRow(pass) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
private fun Figure(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Black)
    }
}

/**
 * One pass, as a line in the log.
 *
 * Its own distance, its own elapsed time and its own average -- not just what
 * it was doing at the spot that was tapped. A pass that took an hour and a
 * pass that took twenty minutes over the same road is the whole finding, and
 * it is invisible if only the corner speed is shown.
 */
@Composable
private fun PassRow(pass: TrackPass) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pass.trackName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(
                    when {
                        pass.trackAverageSpeed != null ->
                            OverlapReport.formatSpeed(pass.trackAverageSpeed!!)
                        else -> "no times"
                    },
                    fontWeight = FontWeight.Black,
                    color = if (pass.trackAverageSpeed != null) Color(0xFF69F0AE)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                listOfNotNull(
                    pass.atMillis?.let { whenItWas(it) },
                    OverlapReport.formatDistance(pass.trackDistanceMeters)
                        .takeIf { pass.trackDistanceMeters > 0 },
                    OverlapReport.formatElapsed(pass.trackElapsedMillis)
                        .takeIf { pass.trackElapsedMillis > 0 }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                listOfNotNull(
                    "${pass.closestMeters.toInt()} m off the tap",
                    when {
                        pass.stopped -> "stopped here"
                        pass.speedMetersPerSecond != null ->
                            OverlapReport.formatSpeed(pass.speedMetersPerSecond!!) + " here"
                        else -> null
                    }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (pass.stopped) Color(0xFFFFB74D)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun whenItWas(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(millis))
