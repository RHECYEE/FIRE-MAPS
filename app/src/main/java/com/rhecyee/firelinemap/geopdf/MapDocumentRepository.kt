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
        val target = storageFileFor(name)
        val id = target.nameWithoutExtension

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
        val target = storageFileFor(displayName)
        val id = target.nameWithoutExtension
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
            ImportedMap(file.nameWithoutExtension, displayNameFrom(file.name), file, document)
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

    /**
     * Where a newly imported map is written.
     *
     * The name goes in the filename because the filename is the only thing
     * that survives. Products were stored as `<millis>-<hash>.pdf` and listed
     * back by reading the directory, so what an operator saw was the hash: the
     * import picker showed "Operations — DIV B-X" and the imported list showed
     * `1789102940988-a701ea76.pdf`, because the real name was never written
     * down anywhere.
     *
     * A timestamp still leads, so two sheets published under the same name on
     * different days stay separate files.
     */
    private fun storageFileFor(displayName: String): File {
        var stamp = System.currentTimeMillis()
        var candidate = File(mapsDir, storageName(displayName, stamp))
        // A second import in the same millisecond is not realistic, but
        // silently overwriting the first would be a map going missing.
        while (candidate.exists()) {
            stamp++
            candidate = File(mapsDir, storageName(displayName, stamp))
        }
        return candidate
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {

        /**
         * Separates the timestamp from the name it was imported under.
         *
         * Two underscores, because the scheme it replaces used a single
         * hyphen. A name that has one is one of these; a name that does not is
         * from before and is left exactly as it was rather than having a
         * meaningless fragment of it presented as a title.
         */
        private const val SEPARATOR = "__"

        /** Longest a stored name may be, leaving room for the stamp and suffix. */
        private const val MAX_NAME = 180

        /**
         * Characters no filesystem Android runs on will take.
         *
         * Spaces are kept: the division sheets are published with them, and
         * replacing them would make the stored name differ from the published
         * one for no reason.
         */
        private val ILLEGAL = Regex("""[/\\:*?"<>|\u0000-\u001f]""")

        /** The filename a map imported as [displayName] is stored under. */
        fun storageName(displayName: String, stamp: Long): String {
            val stem = displayName.substringBeforeLast('.')
                .let { ILLEGAL.replace(it, "_") }
                .trim()
                .take(MAX_NAME)
                .trim()
            return if (stem.isEmpty()) "$stamp.pdf" else "$stamp$SEPARATOR$stem.pdf"
        }

        /** The name a stored file was imported under, as far as it can be recovered. */
        fun displayNameFrom(storageName: String): String {
            val marker = storageName.indexOf(SEPARATOR)
            if (marker < 0) return storageName
            val name = storageName.substring(marker + SEPARATOR.length)
            return name.ifEmpty { storageName }
        }

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
