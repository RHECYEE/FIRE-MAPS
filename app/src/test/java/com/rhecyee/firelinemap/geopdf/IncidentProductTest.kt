package com.rhecyee.firelinemap.geopdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read against the real folder these come out of: the Timber incident's
 * published products for one operational period. Twenty-seven sheets, posted
 * again every day.
 */
class IncidentProductTest {

    private val published = listOf(
        "airops_arch_e_land_20260910_1945_Timber_CALPF002271_0911day.pdf",
        "airops_overview_arch_e_land_20260910_1947_Timber_CALPF002271_0911day.pdf",
        "brief_arch_e_land_20260910_1945_Timber_CALPF002271_0911day.pdf",
        "evac_arch_e_land_20260910_1950_Timber_CALPF002271_0911day.pdf",
        "fhist_arch_e_land_20260910_1959_Timber_CALPF002271_0911day.pdf",
        "ops_arch_e_land_20260910_1941_Timber_CALPF002271_0911day.pdf",
        "ops_arch_e_land_20260910_1948_Timber_CALPF002271_0911day_Branch 20.pdf",
        "ops_arch_e_land_20260910_1948_Timber_CALPF002271_0911day_Branch 30.pdf",
        "ops_arch_e_land_20260910_1948_Timber_CALPF002271_0911day_Branch 50.pdf",
        "ops_arch_e_land_20260910_1948_Timber_CALPF002271_0911day_Branch 80.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV B-X.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV D.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV F-H.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV J.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV N.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV P.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV R.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV T.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV V.pdf",
        "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV W.pdf",
        "own_arch_e_land_20260910_2008_Timber_CALPF002271_0911day.pdf",
        "pilot_11x17_land_20260910_1958_Timber_CALPF002271_0911day.pdf",
        "pio_11x17_land_20260910_1951_Timber_CALPF002271_0911day.pdf",
        "pio_arch_e_land_20260910_1952_Timber_CALPF002271_0911day.pdf",
        "prog_11x17_land_20260910_2008_Timber_CALPF002271_0910day.pdf",
        "prog_arch_e_land_20260910_2002_Timber_CALPF002271_0911day.pdf",
        "trans_arch_e_land_20260910_2007_Timber_CALPF002271_0911day.pdf",
    )

    @Test
    fun `a division sheet leads with what it is and which division`() {
        val product = IncidentProduct.parse(
            "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV B-X.pdf"
        )
        assertEquals("Operations", product.kind)
        assertEquals("DIV B-X", product.unit)
        assertEquals("Timber", product.incident)
        assertEquals("0911day", product.period)
        assertEquals("20260910", product.publishedDate)
        assertEquals("1956", product.publishedTime)
        assertEquals("arch E", product.sheetSize)
        assertEquals("Operations — DIV B-X", product.title)
    }

    @Test
    fun `every sheet in the folder gets its own row`() {
        // The whole complaint. Shown as the first forty characters of the
        // filename, twenty of these twenty-seven collapse into six rows.
        //
        // Judged on the row rather than the title, because two of these really
        // do share a title: public information and progression are each
        // plotted at both sizes, and the same map on 11x17 and on arch E is
        // honestly the same map. The line underneath is what separates them,
        // and forcing the titles apart would be inventing a difference.
        val rows = published.map { IncidentProduct.parse(it) }.map { it.title to it.detail }
        assertEquals(
            "sheets are still indistinguishable: " +
                rows.groupingBy { it }.eachCount().filterValues { it > 1 },
            published.size,
            rows.distinct().size
        )
    }

    @Test
    fun `the same map at two plot sizes shares a title and differs below it`() {
        val small = IncidentProduct.parse(
            "pio_11x17_land_20260910_1951_Timber_CALPF002271_0911day.pdf"
        )
        val large = IncidentProduct.parse(
            "pio_arch_e_land_20260910_1952_Timber_CALPF002271_0911day.pdf"
        )
        assertEquals(small.title, large.title)
        assertTrue(small.detail != large.detail)
        assertTrue(small.detail.contains("11x17"))
        assertTrue(large.detail.contains("arch E"))
    }

    @Test
    fun `the old truncation really did collapse them`() {
        // Otherwise the test above passes for the wrong reason.
        val truncated = published.map { it.take(40) }.distinct()
        assertTrue(
            "the filenames no longer collide, so this proves nothing",
            truncated.size < published.size
        )
    }

    @Test
    fun `a whole-fire sheet has no unit and says so by omission`() {
        val product = IncidentProduct.parse(
            "brief_arch_e_land_20260910_1945_Timber_CALPF002271_0911day.pdf"
        )
        assertEquals("Briefing", product.kind)
        assertNull(product.unit)
        assertEquals("Briefing", product.title)
    }

    @Test
    fun `a two word kind is not mistaken for the shorter one inside it`() {
        val product = IncidentProduct.parse(
            "airops_overview_arch_e_land_20260910_1947_Timber_CALPF002271_0911day.pdf"
        )
        assertEquals("Air ops overview", product.kind)
    }

    @Test
    fun `a branch sheet reads as a branch`() {
        val product = IncidentProduct.parse(
            "ops_arch_e_land_20260910_1948_Timber_CALPF002271_0911day_Branch 20.pdf"
        )
        assertEquals("Operations — Branch 20", product.title)
    }

    @Test
    fun `the detail line says which operational period and when it was posted`() {
        val product = IncidentProduct.parse(
            "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV D.pdf"
        )
        // Working yesterday's division map is a real way to end up somewhere
        // nobody is expecting you.
        assertTrue(product.detail, product.detail.contains("09/11 day"))
        assertTrue(product.detail, product.detail.contains("09/10 19:56"))
        assertTrue(product.detail, product.detail.contains("arch E"))
    }

    @Test
    fun `a night period is not read as a day one`() {
        val product = IncidentProduct.parse(
            "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911night_DIV D.pdf"
        )
        assertEquals("0911night", product.period)
        assertTrue(product.detail.contains("09/11 night"))
    }

    @Test
    fun `the newest operational period sorts to the top`() {
        val older = IncidentProduct.parse(
            "ops_arch_e_land_20260909_1956_Timber_CALPF002271_0910day_DIV D.pdf"
        )
        val newer = IncidentProduct.parse(
            "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV D.pdf"
        )
        assertTrue(
            "${newer.sortKey} should sort before ${older.sortKey}",
            newer.sortKey < older.sortKey
        )
    }

    @Test
    fun `sorting groups a period together and orders divisions within it`() {
        val sorted = published.map { IncidentProduct.parse(it) }.sortedBy { it.sortKey }
        // The one sheet from the previous period lands last.
        assertEquals("0910day", sorted.last().period)
        // Divisions come out in order within their kind.
        val divisions = sorted.mapNotNull { it.unit }.filter { it.startsWith("DIV") }
        assertEquals(divisions.sorted(), divisions)
    }

    @Test
    fun `the sheet size is recognised on both plot sizes`() {
        assertEquals(
            "11x17",
            IncidentProduct.parse(
                "pilot_11x17_land_20260910_1958_Timber_CALPF002271_0911day.pdf"
            ).sheetSize
        )
        assertEquals(
            "arch E",
            IncidentProduct.parse(
                "pio_arch_e_land_20260910_1952_Timber_CALPF002271_0911day.pdf"
            ).sheetSize
        )
    }

    @Test
    fun `every published sheet is recognised as a known kind`() {
        for (name in published) {
            val product = IncidentProduct.parse(name)
            assertNotNull("unparsed: $name", product.kind)
            assertEquals("wrong incident on $name", "Timber", product.incident)
            assertNotNull("no period on $name", product.period)
        }
    }

    // ---- anything that is not one of these ----

    @Test
    fun `a file that is not an incident product keeps its own name`() {
        val product = IncidentProduct.parse("my scanned map.pdf")
        assertNull(product.kind)
        assertNull(product.period)
        assertEquals("my scanned map", product.title)
        assertEquals("my scanned map.pdf", product.detail)
    }

    @Test
    fun `an unfamiliar kind is shown rather than dropped`() {
        val product = IncidentProduct.parse(
            "smoke_arch_e_land_20260910_1956_Timber_CALPF002271_0911day.pdf"
        )
        assertEquals("Smoke", product.kind)
        assertEquals("0911day", product.period)
    }

    @Test
    fun `an incident whose name has two words survives`() {
        val product = IncidentProduct.parse(
            "ops_arch_e_land_20260910_1956_Bear_Creek_CALPF002271_0911day_DIV D.pdf"
        )
        assertEquals("Bear Creek", product.incident)
        assertEquals("DIV D", product.unit)
    }

    @Test
    fun `a name with nothing after the period still parses`() {
        val product = IncidentProduct.parse(
            "ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day.pdf"
        )
        assertNull(product.unit)
        assertEquals("Operations", product.title)
    }

    @Test
    fun `an empty name does not throw`() {
        val product = IncidentProduct.parse("")
        assertEquals("", product.title)
    }
}
