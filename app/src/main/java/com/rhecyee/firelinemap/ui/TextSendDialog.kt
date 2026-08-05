package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.rhecyee.firelinemap.share.SharePackage
import com.rhecyee.firelinemap.share.SmsSender

/**
 * Confirming a run of texts before any of them goes.
 *
 * The one screen in this app that spends the operator's money and cannot be
 * undone. Everything it is about to do is on it before the button is
 * available: who it goes to, how many messages, and what they cost in
 * segments, which is what a carrier actually bills.
 *
 * Deliberately not a toast-and-hope. Seven texts to the wrong number is not
 * something an undo can fix.
 */
@Composable
fun TextSendDialog(
    pkg: SharePackage,
    parts: List<String>,
    initialNumber: String,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var number by remember { mutableStateOf(initialNumber) }
    val segments = remember(parts) { SmsSender.segmentsFor(parts) }
    val valid = SmsSender.looksLikeNumber(number)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text the whole incident") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${pkg.incidentName} — ${pkg.describe()}",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Goes as ${parts.size} message${if (parts.size == 1) "" else "s"}, " +
                        "$segments SMS segment${if (segments == 1) "" else "s"} in total. " +
                        "They paste each one into their copy and the whole map redraws.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Send to") },
                    placeholder = { Text("541 555 0134") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )

                // Said before the button, not after. This is real money and
                // the messages cannot be recalled.
                Text(
                    "Sends real texts at your carrier's rate. They cannot be " +
                        "recalled once sent.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB74D)
                )

                if (!canSend) {
                    Text(
                        "This device cannot send texts. Use COPY PART instead and " +
                            "paste them into any messaging app.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF8A80)
                    )
                }
                if (segments > 40) {
                    // Worth a second look before somebody sends a hundred
                    // segments to a phone on a hilltop.
                    Text(
                        "That is a lot of segments. If they have any data at all, the " +
                            "file is cheaper.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB74D)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(number.trim()) },
                enabled = valid && canSend
            ) {
                Text(
                    "SEND ${parts.size} TEXT${if (parts.size == 1) "" else "S"}",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}
