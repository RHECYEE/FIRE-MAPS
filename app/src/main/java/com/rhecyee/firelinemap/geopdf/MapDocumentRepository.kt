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

    /**
     * Everything imported, newest first.
     *
     * Georeferencing is read once a file and then held. It means opening and
     * scanning the whole PDF, and a fortnight of published products is several
     * hundred sheets at four megabytes each -- re-reading all of it every time
     * the list is shown is most of a gigabyte of work to draw a menu, which is
     * felt as the app locking up each time a map is chosen.
     *
     * Keyed on length and modification time as well as the path, so a file
     * replaced under the same name is read again.
     */
    fun imported(): List<ImportedMap> {
        val files = mapsDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: return emptyList()

        val live = files.map { it.absolutePath }.toSet()
        synchronized(cache) { cache.keys.retainAll { it.substringBefore('\n') in live } }

        return files.mapNotNull { file ->
            val key = "${file.absolutePath}\n${file.length()}\n${file.lastModified()}"
            val document = synchronized(cache) { cache[key] }
                ?: runCatching { GeoPdfReader.read(file) }.getOrNull()?.also {
                    synchronized(cache) { cache[key] = it }
                }
                ?: return@mapNotNull null
            ImportedMap(file.nameWithoutExtension, file.name, file, document)
        }
    }

    /**
     * Removes an imported map from the device.
     *
     * Published products pile up fast: a single operational period is two
     * dozen sheets and they are posted again every day, so a fortnight of a
     * fire is several hundred of them. Without a way to throw one away the
     * list becomes unusable long before the assignment ends.
     *
     * Returns false when the file was already gone, which is not an error --
     * the map is absent either way.
     */
    fun delete(id: String): Boolean {
        val target = File(mapsDir, "$id.pdf")
        synchronized(cache) {
            cache.keys.retainAll { !it.startsWith(target.absolutePath + "\n") }
        }
        return runCatching { target.delete() }.getOrDefault(false)
    }

    private val cache = mutableMapOf<String, GeoPdfDocument>()

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
