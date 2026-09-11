package com.rhecyee.firelinemap.geopdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an imported map is called on disk.
 *
 * Maps were stored as `<millis>-<hash>.pdf` and listed back by reading the
 * directory, so the name an operator chose from was thrown away at the moment
 * of import: the picker read "Operations — DIV B-X" and the imported list read
 * `1789102940988-a701ea76.pdf`.
 */
class MapStorageNameTest {

    private val published =
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV B-X.pdf"

    @Test
    fun `a published sheet keeps its name through a round trip`() {
        val stored = MapDocumentRepository.storageName(published, 1789102940988L)
        assertEquals(published, MapDocumentRepository.displayNameFrom(stored))
    }

    @Test
    fun `the recovered name still parses into the row it came from`() {
        // The whole point: what comes back has to be what the picker showed.
        val stored = MapDocumentRepository.storageName(published, 1789102940988L)
        val recovered = MapDocumentRepository.displayNameFrom(stored)
        assertEquals(published, recovered)
        val product = IncidentProduct.parse(recovered)
        assertEquals("Operations — DIV B-X", product.title)
        assertEquals("0911day", product.period)
    }

    @Test
    fun `the timestamp leads so the same sheet from two days stays two files`() {
        val monday = MapDocumentRepository.storageName(published, 1789102940988L)
        val tuesday = MapDocumentRepository.storageName(published, 1789189340988L)
        assertTrue(monday != tuesday)
        assertTrue(monday.startsWith("1789102940988"))
        assertEquals(
            MapDocumentRepository.displayNameFrom(monday),
            MapDocumentRepository.displayNameFrom(tuesday)
        )
    }

    @Test
    fun `spaces are kept because that is how the sheets are published`() {
        val stored = MapDocumentRepository.storageName(published, 1L)
        assertTrue(stored, stored.contains("DIV B-X"))
    }

    @Test
    fun `characters a filesystem will not take are replaced rather than dropped`() {
        val stored = MapDocumentRepository.storageName("ops/div: A*B?.pdf", 1L)
        assertTrue(stored, stored.none { it in "/\\:*?\"<>|" })
        assertTrue(stored, stored.contains("ops_div_ A_B_"))
    }

    @Test
    fun `a very long name is cut to something a filesystem will accept`() {
        val long = "x".repeat(4000) + ".pdf"
        val stored = MapDocumentRepository.storageName(long, 1789102940988L)
        assertTrue("stored name is $stored.length long", stored.length < 250)
        assertTrue(stored.endsWith(".pdf"))
    }

    @Test
    fun `a name that is nothing but a suffix still yields a usable file`() {
        val stored = MapDocumentRepository.storageName(".pdf", 1789102940988L)
        assertEquals("1789102940988.pdf", stored)
        // Nothing to recover, so it reports itself rather than an empty row.
        assertEquals("1789102940988.pdf", MapDocumentRepository.displayNameFrom(stored))
    }

    @Test
    fun `a file stored under the old scheme is left exactly as it was`() {
        // Those have no name in them to recover. Presenting the hash fragment
        // as a title would be worse than showing the filename.
        val old = "1789102940988-a701ea76.pdf"
        assertEquals(old, MapDocumentRepository.displayNameFrom(old))
    }

    @Test
    fun `a name that happens to contain a single hyphen is not confused for the old scheme`() {
        val stored = MapDocumentRepository.storageName("DIV B-X ops.pdf", 1789102940988L)
        assertEquals("DIV B-X ops.pdf", MapDocumentRepository.displayNameFrom(stored))
    }

    @Test
    fun `a name carrying the separator itself survives`() {
        val stored = MapDocumentRepository.storageName("ops__final.pdf", 1789102940988L)
        assertEquals("ops__final.pdf", MapDocumentRepository.displayNameFrom(stored))
    }

    @Test
    fun `every sheet in a published folder round trips`() {
        val folder = listOf(
            "airops_arch_e_land_20260910_1945_Timber_CALPF002271_0911day.pdf",
            "ops_arch_e_land_20260910_1948_Timber_CALPF002271_0911day_Branch 20.pdf",
            "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV F-H.pdf",
            "pio_11x17_land_20260910_1951_Timber_CALPF002271_0911day.pdf",
            "prog_11x17_land_20260910_2008_Timber_CALPF002271_0910day.pdf",
        )
        for ((index, name) in folder.withIndex()) {
            val stored = MapDocumentRepository.storageName(name, 1789102940988L + index)
            val recovered = MapDocumentRepository.displayNameFrom(stored)
            assertEquals(name, recovered)
            assertTrue(
                "unreadable row for $name",
                IncidentProduct.parse(recovered).kind != null
            )
        }
    }

    @Test
    fun `the id taken off a stored file rebuilds that exact filename`() {
        // How removal finds the file: the list hands back an id read as the
        // name without its extension, and delete puts the extension back. A
        // name carrying dots of its own must not break that.
        for (name in listOf(
            "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV B-X.pdf",
            "map.v2.pdf",
            "no extension",
            ".pdf",
        )) {
            val stored = MapDocumentRepository.storageName(name, 1789102940988L)
            val id = stored.substringBeforeLast('.')
            assertEquals("removal would miss $stored", stored, "$id.pdf")
        }
    }
}
