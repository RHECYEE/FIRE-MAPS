package com.rhecyee.firelinemap.geopdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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

    private val rootDir: File
        get() = File(context.filesDir, "maps").apply { mkdirs() }

    /**
     * Sheets are held per incident, in a folder named for it.
     *
     * They used to share one directory, which meant every sheet ever imported
     * showed up on every fire afterwards -- last month's division map sitting
     * in the list next to today's IAP, with nothing but the filename to tell
     * them apart. A folder per incident also makes deleting an incident's maps
     * a directory delete rather than a filename convention nobody maintains.
     */
    private fun dirFor(incidentId: String): File =
        File(rootDir, folderName(incidentId)).apply { mkdirs() }

    /** A directory name that cannot escape the maps folder or surprise a filesystem. */
    private fun folderName(incidentId: String): String =
        incidentId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(64)
            .ifEmpty { "unfiled" }

    fun importFrom(uri: Uri, incidentId: String): ImportedMap? {
        val name = displayNameOf(uri) ?: "map-${System.currentTimeMillis()}.pdf"
        val id = "${System.currentTimeMillis()}-${name.hashCode().toUInt().toString(16)}"
        val target = File(dirFor(incidentId), "$id.pdf")

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
    fun importFromFile(source: File, displayName: String, incidentId: String): ImportedMap? {
        if (!source.exists() || source.length() == 0L) return null
        val id = "${System.currentTimeMillis()}-${displayName.hashCode().toUInt().toString(16)}"
        val target = File(dirFor(incidentId), "$id.pdf")
        val copied = runCatching { source.copyTo(target, overwrite = true) }.isSuccess
        if (!copied) return null

        val document = runCatching { GeoPdfReader.read(target) }
            .getOrDefault(GeoPdfDocument(emptyList(), PdfKind.PLAIN))
        return ImportedMap(id, displayName, target, document)
    }

    fun imported(incidentId: String): List<ImportedMap> = read(dirFor(incidentId))

    private fun read(directory: File): List<ImportedMap> =
        directory.listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                runCatching {
                    ImportedMap(file.nameWithoutExtension, file.name, file, GeoPdfReader.read(file))
                }.getOrNull()
            }
            ?: emptyList()

    /**
     * Moves sheets imported before incidents had folders into one.
     *
     * Runs once, on the incident that is open the first time this build starts.
     * The alternative -- leaving them where they are -- means an operator who
     * updates mid-season opens the app and finds their maps gone, which is
     * indistinguishable from the app having lost them.
     */
    fun adoptLooseSheets(incidentId: String): Int {
        val loose = rootDir.listFiles { f ->
            f.isFile && f.extension.equals("pdf", ignoreCase = true)
        } ?: return 0
        if (loose.isEmpty()) return 0
        val destination = dirFor(incidentId)
        var moved = 0
        loose.forEach { file ->
            val target = File(destination, file.name)
            val ok = runCatching {
                if (!file.renameTo(target)) {
                    // Rename fails across storage boundaries; a copy still
                    // gets the map in front of whoever needs it.
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
                true
            }.getOrDefault(false)
            if (ok) moved++
        }
        return moved
    }

    /** Drops every sheet held for an incident. Used when the incident is deleted. */
    fun forget(incidentId: String) {
        runCatching { dirFor(incidentId).deleteRecursively() }
    }

    /** Bytes of sheets held for an incident, so deleting one can say what it costs. */
    fun bytesHeld(incidentId: String): Long =
        dirFor(incidentId).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * How many sheets an incident holds, without opening any of them.
     *
     * [imported] parses every PDF it lists, which is seconds of work for a
     * folder of product maps. A list of incidents only needs the number.
     */
    fun sheetCount(incidentId: String): Int =
        dirFor(incidentId).listFiles { f ->
            f.isFile && f.extension.equals("pdf", ignoreCase = true)
        }?.size ?: 0

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {
        /**
         * Renders page one to a bitmap.
         *
         * Deliberately a whole-page render rather than a tile pyramid. It is
         * enough to prove registration on a real product and to be carried
         * into the field, and it removes the tiling pipeline from the path to
         * a testable build. Large sheets will want tiling before this is fast
         * enough to pan comfortably.
         */
        fun renderPage(file: File, targetWidth: Int): Bitmap? = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val scale = targetWidth.toFloat() / page.width
                        val width = targetWidth.coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }.getOrNull()

        /** Page height in PDF points, needed to flip into bitmap coordinates. */
        fun pageSize(file: File): Pair<Int, Int>? = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page -> page.width to page.height }
                }
            }
        }.getOrNull()
    }
}
