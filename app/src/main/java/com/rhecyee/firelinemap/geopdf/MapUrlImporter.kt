package com.rhecyee.firelinemap.geopdf

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One PDF offered by a directory listing. */
data class RemotePdf(val name: String, val url: String)

/** What a fetched URL turned out to be. */
sealed interface UrlProbe {
    /** The URL is a PDF and has been downloaded. */
    data class Downloaded(val file: File, val name: String) : UrlProbe

    /** The URL is a listing; these are the PDFs it offers. */
    data class Listing(val entries: List<RemotePdf>) : UrlProbe

    data class Failed(val reason: String) : UrlProbe
}

/**
 * Fetches map products over HTTP.
 *
 * Handles both a direct link to a product and a link to the folder holding a
 * day's products, because that is how incident products are actually
 * published -- a dated directory containing a dozen sheets, not a single
 * predictable filename.
 *
 * Everything here blocks; callers run it off the main thread.
 */
class MapUrlImporter(private val cacheDir: File) {

    fun probe(rawUrl: String): UrlProbe {
        val normalised = normalise(rawUrl) ?: return UrlProbe.Failed("That is not a valid URL.")

        val connection = runCatching { open(normalised) }
            .getOrElse { return UrlProbe.Failed("Could not reach that address.") }

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                return UrlProbe.Failed("Server returned $status.")
            }
            val contentType = connection.contentType.orEmpty().lowercase()
            val looksLikePdf = contentType.contains("pdf") ||
                normalised.substringBefore('?').endsWith(".pdf", ignoreCase = true)

            if (looksLikePdf) {
                val name = fileNameOf(normalised)
                val target = File(cacheDir, name).apply { parentFile?.mkdirs() }
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() == 0L) {
                    target.delete()
                    UrlProbe.Failed("That file was empty.")
                } else {
                    UrlProbe.Downloaded(target, name)
                }
            } else {
                val body = connection.inputStream.use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                val entries = parseListing(body, normalised)
                if (entries.isEmpty()) {
                    UrlProbe.Failed("No PDFs found at that address.")
                } else {
                    UrlProbe.Listing(entries)
                }
            }
        } catch (error: Exception) {
            UrlProbe.Failed(error.message ?: "Download failed.")
        } finally {
            connection.disconnect()
        }
    }

    fun download(entry: RemotePdf): UrlProbe {
        val connection = runCatching { open(entry.url) }
            .getOrElse { return UrlProbe.Failed("Could not reach that address.") }
        return try {
            if (connection.responseCode !in 200..299) {
                return UrlProbe.Failed("Server returned ${connection.responseCode}.")
            }
            val target = File(cacheDir, entry.name).apply { parentFile?.mkdirs() }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            UrlProbe.Downloaded(target, entry.name)
        } catch (error: Exception) {
            UrlProbe.Failed(error.message ?: "Download failed.")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "FirelineMap/0.1")
        }

    private fun normalise(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            // Published product folders are served over HTTPS even when people
            // refer to them as FTP paths.
            trimmed.startsWith("ftp://", true) -> "https://" + trimmed.removePrefix("ftp://")
            else -> "https://$trimmed"
        }
        return runCatching { URL(withScheme).toString() }.getOrNull()
    }

    private fun fileNameOf(url: String): String {
        val candidate = url.substringBefore('?').substringAfterLast('/')
        return if (candidate.endsWith(".pdf", ignoreCase = true) && candidate.length > 4) {
            candidate
        } else {
            "download-${System.currentTimeMillis()}.pdf"
        }
    }

    /** Pulls PDF links out of an HTML index page. */
    internal fun parseListing(html: String, baseUrl: String): List<RemotePdf> {
        val hrefs = Regex("""href\s*=\s*["']([^"']+\.pdf)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

        val seen = LinkedHashMap<String, RemotePdf>()
        for (href in hrefs) {
            val absolute = runCatching { URL(URL(baseUrl), href).toString() }.getOrNull() ?: continue
            val name = absolute.substringBefore('?').substringAfterLast('/')
            if (name.isNotBlank()) seen.putIfAbsent(absolute, RemotePdf(decode(name), absolute))
        }
        return seen.values.toList()
    }

    private fun decode(name: String): String =
        runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
}
