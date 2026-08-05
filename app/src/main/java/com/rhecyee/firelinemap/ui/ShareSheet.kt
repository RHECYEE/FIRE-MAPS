package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.share.ShareIntents
import com.rhecyee.firelinemap.share.SharePackage
import com.rhecyee.firelinemap.share.ShareText
import com.rhecyee.firelinemap.share.TextCodec

/**
 * Moving tracks and pins between phones, in both directions.
 *
 * Three ways out, because they fail in different places and the one that works
 * depends on where you are standing.
 *
 * The file carries everything and opens in any mapping app, but a carrier caps
 * a picture message and sometimes refuses an unfamiliar file type outright, so
 * it can quietly not arrive. The readable text carries positions only, but
 * reaches any phone and can be read over a radio. The pasted parts carry the
 * whole thing -- track shapes, times, symbols -- as characters in the body of
 * a message, which nothing between here and there can refuse.
 *
 * Receiving is in the same place as sending on purpose. It is one question --
 * how does this get to somebody else, and how does theirs get to me -- and
 * splitting it across two screens is how an operator ends up not knowing the
 * second half exists.
 */
@Composable
fun ShareSheet(
    pkg: SharePackage,
    assembly: TextCodec.Assembly,
    onText: () -> Unit,
    onSendFile: () -> Unit,
    onSendFullFile: () -> Unit,
    onTextAll: (List<String>) -> Unit,
    onCopyPart: (String) -> Unit,
    onPaste: (String) -> Unit,
    onApplyPasted: () -> Unit,
    onClearPasted: () -> Unit,
    onDismiss: () -> Unit
) {
    var receiving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (receiving) "Paste from a message" else "Send ${pkg.describe()}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !receiving,
                        onClick = { receiving = false },
                        label = { Text("SEND") }
                    )
                    FilterChip(
                        selected = receiving,
                        onClick = { receiving = true },
                        label = { Text("RECEIVE") }
                    )
                }
                HorizontalDivider()

                if (receiving) {
                    Receive(
                        assembly = assembly,
                        onPaste = onPaste,
                        onApply = onApplyPasted,
                        onClear = onClearPasted
                    )
                } else {
                    Send(
                        pkg = pkg,
                        onTextAll = onTextAll,
                        onText = onText,
                        onSendFile = onSendFile,
                        onSendFullFile = onSendFullFile,
                        onCopyPart = onCopyPart
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
private fun Send(
    pkg: SharePackage,
    onTextAll: (List<String>) -> Unit,
    onText: () -> Unit,
    onSendFile: () -> Unit,
    onSendFullFile: () -> Unit,
    onCopyPart: (String) -> Unit
) {
    val message = remember(pkg) { ShareText.message(pkg) }
    val segments = remember(message) { ShareText.segments(message) }
    val fullBytes = remember(pkg) { ShareIntents.sizeOf(pkg) }
    val prepared = remember(pkg) { ShareIntents.prepareForMessage(pkg) }
    val parts = remember(pkg) { TextCodec.parts(pkg) }
    var nextPart by remember(pkg) { mutableIntStateOf(0) }
    val overBudget = fullBytes > ShareIntents.MESSAGE_BUDGET_BYTES

    Heading("TEXT THE WHOLE INCIDENT")
    Note(
        "Every pin and every track, as ${parts.size} " +
            "message${if (parts.size == 1) "" else "s"}. They paste each one into " +
            "their copy and the whole map redraws. Nothing between here and there " +
            "can refuse it — no attachment, no file type, no size cap."
    )
    Button(onClick = { onTextAll(parts) }, modifier = Modifier.fillMaxWidth()) {
        Text(
            "TEXT IT ALL — ${parts.size} message${if (parts.size == 1) "" else "s"}",
            fontWeight = FontWeight.Bold
        )
    }

    // Kept for the cases texting cannot reach: a tablet with no SIM, a
    // recipient on a different app, or somewhere the operator would rather
    // paste it themselves.
    OutlinedButton(
        onClick = {
            onCopyPart(parts[nextPart])
            if (nextPart < parts.lastIndex) nextPart++
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (parts.size == 1) "COPY IT INSTEAD"
            else "COPY PART ${nextPart + 1} OF ${parts.size} INSTEAD"
        )
    }

    HorizontalDivider()

    Heading("TEXT IT — POSITIONS ONLY")
    Note(
        "Readable coordinates in the message itself, for somebody without this " +
            "app." + if (pkg.tracks.isNotEmpty()) " Track shapes are not included." else ""
    )
    OutlinedButton(onClick = onText, modifier = Modifier.fillMaxWidth()) {
        Text(if (segments == 1) "TEXT IT — one message" else "TEXT IT — $segments parts")
    }

    HorizontalDivider()

    Heading("SEND THE FILE")
    Note(
        "A GPX, through whatever you pick — Bluetooth, a message, email, a nearby " +
            "phone. Opens in Gaia, CalTopo, Avenza or another copy of this app."
    )
    if (overBudget) {
        Warn(
            "Full detail is ${kilobytes(fullBytes)}, over what most carriers will " +
                "send in a picture message."
        )
        if (prepared.fits) {
            OutlinedButton(onClick = onSendFile, modifier = Modifier.fillMaxWidth()) {
                Text("SEND FILE — ${kilobytes(ShareIntents.sizeOf(prepared.pkg))}, fits a text")
            }
            Note(prepared.describe(pkg))
        } else {
            Warn(
                "This will not fit a picture message even thinned. Use email, " +
                    "Bluetooth, a nearby phone — or the pasted parts above."
            )
        }
        OutlinedButton(onClick = onSendFullFile, modifier = Modifier.fillMaxWidth()) {
            Text("SEND FULL DETAIL — ${kilobytes(fullBytes)}")
        }
    } else {
        OutlinedButton(onClick = onSendFullFile, modifier = Modifier.fillMaxWidth()) {
            Text("SEND FILE — ${kilobytes(fullBytes)}, full detail")
        }
    }
}

@Composable
private fun Receive(
    assembly: TextCodec.Assembly,
    onPaste: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit
) {
    var typed by remember { mutableStateOf("") }

    Note(
        "Paste one part at a time from the message. Order does not matter, and " +
            "extra text around it is ignored."
    )
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text("Paste a part here") },
        placeholder = { Text("FL1;…") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 4
    )
    Button(
        onClick = {
            onPaste(typed)
            typed = ""
        },
        enabled = typed.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("ADD THIS PART", fontWeight = FontWeight.Bold)
    }

    Text(
        assembly.describe(),
        fontWeight = FontWeight.Bold,
        color = if (assembly.isComplete) Color(0xFF69F0AE)
        else MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (assembly.isComplete) {
        if (assembly.verified()) {
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("DRAW IT ON THIS INCIDENT", fontWeight = FontWeight.Bold)
            }
            // Said plainly. The pins and tracks are added to whatever incident
            // is open, and nothing is removed to make room.
            Note("Adds to the incident you have open. Nothing already here is changed.")
        } else {
            // The checksum is the only thing standing between a paste that
            // stopped halfway and a track that looks right and is not.
            Warn(
                "Those parts do not add up — something was cut short or came from " +
                    "a different message. Clear and paste them again."
            )
        }
    }

    if (assembly.parts.isNotEmpty()) {
        TextButton(onClick = onClear) { Text("CLEAR PASTED PARTS") }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Warn(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB74D))
}

private fun kilobytes(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 999) return "${kotlin.math.round(kb).toInt()} KB"
    return "${kotlin.math.round(kb / 1024.0 * 10) / 10.0} MB"
}
