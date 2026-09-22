package com.rhecyee.firelinemap.geopdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapUrlImporterTest {

    private val importer = MapUrlImporter(File(System.getProperty("java.io.tmpdir")!!))

    /** The shape an Apache directory index actually takes. */
    private val listingHtml = """
        <html><body><h1>Index of /Products/20260728</h1><table>
        <tr><td><a href="/Products/">Parent Directory</a></td></tr>
        <tr><td><a href="ops_arch_c_land_20260728_0506_BurntCreek.pdf">ops_arch_c_land.pdf</a></td></tr>
        <tr><td><a href="trans_arch_e_land_20260728_0506_BurntCreek.pdf">trans_arch_e_land.pdf</a></td></tr>
        <tr><td><a href="notes.txt">notes.txt</a></td></tr>
        </table></body></html>
    """.trimIndent()

    @Test
    fun listingYieldsOnlyPdfs() {
        val entries = importer.parseListing(
            listingHtml,
            "https://example.gov/Products/20260728/"
        )
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.name.endsWith(".pdf") })
    }

    @Test
    fun relativeLinksResolveAgainstTheFolder() {
        val entries = importer.parseListing(
            listingHtml,
            "https://example.gov/Products/20260728/"
        )
        assertEquals(
            "https://example.gov/Products/20260728/ops_arch_c_land_20260728_0506_BurntCreek.pdf",
            entries.first().url
        )
    }

    @Test
    fun duplicateLinksCollapse() {
        val html = """
            <a href="a.pdf">one</a><a href="a.pdf">one again</a><a href="b.pdf">two</a>
        """.trimIndent()
        assertEquals(2, importer.parseListing(html, "https://example.gov/x/").size)
    }

    @Test
    fun percentEncodedNamesAreDecodedForDisplay() {
        val entries = importer.parseListing(
            """<a href="ops%20arch%20c.pdf">x</a>""",
            "https://example.gov/x/"
        )
        assertEquals("ops arch c.pdf", entries.single().name)
    }

    @Test
    fun aPageWithNoPdfsYieldsNothing() {
        assertTrue(importer.parseListing("<html><a href='x.txt'>t</a></html>", "https://e.gov/")
            .isEmpty())
    }

    // ---- the real published folder ----

    private fun timberListing(): String =
        javaClass.getResourceAsStream("/listing/wildfire_gov_timber_qr.html")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("missing listing fixture")

    private val timberUrl =
        "https://ftp.wildfire.gov/public/incident_specific_maps/calif_s/2026/2026_Timber/QR/"

    @Test
    fun everyProductInTheRealFolderIsOffered() {
        val entries = MapUrlImporter(File("/tmp")).parseListing(timberListing(), timberUrl)
        // One operational period of a real fire: twenty-seven sheets, and the
        // folder is posted again every day.
        assertEquals(27, entries.size)
        assertTrue(entries.all { it.url.startsWith(timberUrl) })
        assertTrue(entries.all { it.name.endsWith(".pdf") })
    }

    @Test
    fun namesWithSpacesComeBackReadableRatherThanEscaped() {
        // The division sheets are published with a space in the name, which
        // arrives percent-encoded in the href.
        val entries = MapUrlImporter(File("/tmp")).parseListing(timberListing(), timberUrl)
        val division = entries.firstOrNull { it.name.contains("DIV B-X") }
        assertTrue("no readable division name in $entries", division != null)
        assertTrue(division!!.url.contains("%20"))
    }

    @Test
    fun theParentDirectoryLinkIsNotOfferedAsAProduct() {
        val entries = MapUrlImporter(File("/tmp")).parseListing(timberListing(), timberUrl)
        assertTrue(entries.none { it.name.contains("Parent") })
        assertTrue(entries.none { it.url.endsWith("/") })
    }

    @Test
    fun everyOfferedProductParsesIntoAReadableRow() {
        // What the picker shows. If these did not parse the dialog would be
        // twenty-seven rows of identical text again.
        val entries = MapUrlImporter(File("/tmp")).parseListing(timberListing(), timberUrl)
        val rows = entries.map { IncidentProduct.parse(it.name) }
        assertTrue("some products did not parse", rows.all { it.kind != null })
        assertEquals(entries.size, rows.map { it.title to it.detail }.distinct().size)
    }
}
