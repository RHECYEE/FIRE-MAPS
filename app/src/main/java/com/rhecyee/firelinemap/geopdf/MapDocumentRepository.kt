package com.rhecyee.firelinemap.geopdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** An imported map, ready to draw. */
data class ImportedMap(
    val id: String,
    val displayName: String,
    val file: File,
    val document: GeoPdfDocument
) {
    val kind get() = document.kind
    val frame get() = document.primaryFrame

    /** Operator-facing label. The distinction is never implied, only stated. */
    val kindLabel: String
        get() = when (kind) {
            PdfKind.GEOREFERENCED -> "GeoPDF — location enabled"
            PdfKind.PLAIN -> "Regular PDF — view only"
        }
}

/**
 * Copies imported PDFs into app storage and reads their georeferencing.
 *
 * The file is copied rather than referenced by URI on purpose. A content URI
 * granted by the share sheet stops resolving once the source app clears its
 * cache or the device reboots, and a map that disappears mid-shift is worse
 * than one that was never imported.
 */
class MapDocumentRepository(private val context: Context) {

    private val mapsDir: File
        get() = File(context.filesDir, "maps").apply { mkdirs() }

    fun importFrom(uri: Uri): ImportedMap? {
        val name = displayNameOf(uri) ?: "map-${System.currentTimeMillis()}.pdf"
        val id = "${System.currentTimeMillis()}-${name.hashCode().toUInt().toString(16)}"
        val target = File(mapsDir, "$id.pdf")

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)

        if (!copied || !target.exists() || target.length() == 0L) {
            target.delete()
            return null
        }

        val document = runCatching { GeoPdfReader.read(target) }
            .getOrDefault(GeoPdfDocument(emptyList(), PdfKind.PLAIN))

        return ImportedMap(id, name, target, document)
    }

    /** Takes a file already on disk, such as one fetched from a URL. */
    fun importFromFile(source: File, displayName: String): ImportedMap? {
        if (!source.exists() || source.length() == 0L) return null
        val id = "${System.currentTimeMillis()}-${displayName.hashCode().toUInt().toString(16)}"
        val target = File(mapsDir, "$id.pdf")
        val copied = runCatching { source.copyTo(target, overwrite = true) }.isSuccess
        if (!copied) return null

        val document = runCatching { GeoPdfReader.read(target) }
            .getOrDefault(GeoPdfDocument(emptyList(), PdfKind.PLAIN))
        return ImportedMap(id, displayName, target, document)
    }

    fun imported(): List<ImportedMap> =
        mapsDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                runCatching {
                    ImportedMap(file.nameWithoutExtension, file.name, file, GeoPdfReader.read(file))
                }.getOrNull()
            }
            ?: emptyList()

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {
        // Drawing a sheet is MapSheetRenderer's job, not this class's. There
        // used to be a whole-page render here, taken at a fixed 2048 pixels
        // across. On the Arch E sheets incidents actually publish, that is 43
        // DPI, so every imported map was unreadable the moment it was zoomed,
        // whatever detail was in the file. It is gone rather than tuned
        // because no one fixed width can be right: the renderer draws the
        // window being looked at, at the resolution it is being looked at.

        /** Page size in PDF points, needed to flip into bitmap coordinates. */
        fun pageSize(file: File): Pair<Int, Int>? = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page -> page.width to page.height }
                }
            }
        }.getOrNull()
    }
}
