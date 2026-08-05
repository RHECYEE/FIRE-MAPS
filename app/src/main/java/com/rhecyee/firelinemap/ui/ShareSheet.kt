package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.share.ShareIntents
import com.rhecyee.firelinemap.share.SharePackage
import com.rhecyee.firelinemap.share.ShareText

/**
 * Choosing how positions leave the phone.
 *
 * Two paths, because they fail in different places. The file carries
 * everything and opens in any mapping app, but a picture message is capped by
 * the carrier and an unknown file type is sometimes refused outright, so it
 * can silently not arrive. The text carries only positions, but it reaches any
 * phone ever made, needs nothing installed at the other end, and can be read
 * out over a radio.
 *
 * Both say what they will actually do before they do it -- how many parts the
 * message will arrive in, how big the file is, and whether a track had to be
 * thinned to fit.
 */
@Composable
fun ShareSheet(
    pkg: SharePackage,
    onText: () -> Unit,
    onSendFile: () -> Unit,
    onSendFullFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = remember(pkg) { ShareText.message(pkg) }
    val segments = remember(message) { ShareText.segments(message) }
    val fullBytes = remember(pkg) { ShareIntents.sizeOf(pkg) }
    val prepared = remember(pkg) { ShareIntents.prepareForMessage(pkg) }
    val overBudget = fullBytes > ShareIntents.MESSAGE_BUDGET_BYTES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send ${pkg.describe()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                Text(
                    "TEXT IT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Positions in the message itself. Arrives on any phone, needs " +
                        "nothing installed, and reads out over a radio." +
                        if (pkg.tracks.isNotEmpty()) " Track shapes are not included." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onText, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (segments == 1) "TEXT IT — one message"
                        else "TEXT IT — $segments parts",
                        fontWeight = FontWeight.Bold
                    )
                }
                // Past about four parts phones start delivering out of order,
                // and a coordinate gets reassembled by hand.
                if (segments > 4) {
                    Text(
                        "That is a lot of parts. They can arrive out of order — " +
                            "consider sending the file, or fewer pins.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB74D)
                    )
                }

                Text(
                    "SEND THE FILE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "A GPX, through whatever you pick — Bluetooth, a message, email, " +
                        "a nearby phone. Opens in Gaia, CalTopo, Avenza or another " +
                        "copy of this app, including on an iPhone.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (overBudget) {
                    // The whole reason this dialog exists rather than a single
                    // button: over the carrier cap an attachment is refused,
                    // usually with nothing worth reading as an error.
                    Text(
                        "Full detail is ${kilobytes(fullBytes)}, over what most carriers " +
                            "will send in a picture message.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB74D)
                    )
                    if (prepared.fits) {
                        Button(onClick = onSendFile, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "SEND FILE — ${kilobytes(ShareIntents.sizeOf(prepared.pkg))}, " +
                                    "fits a text",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            prepared.describe(pkg),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "This will not fit a picture message even thinned. Send it " +
                                "over email, Bluetooth or a nearby phone instead.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFB74D)
                        )
                    }
                    OutlinedButton(onClick = onSendFullFile, modifier = Modifier.fillMaxWidth()) {
                        Text("SEND FULL DETAIL — ${kilobytes(fullBytes)}")
                    }
                } else {
                    Button(onClick = onSendFullFile, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "SEND FILE — ${kilobytes(fullBytes)}, full detail",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

private fun kilobytes(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 999) return "${kotlin.math.round(kb).toInt()} KB"
    return "${kotlin.math.round(kb / 1024.0 * 10) / 10.0} MB"
}
