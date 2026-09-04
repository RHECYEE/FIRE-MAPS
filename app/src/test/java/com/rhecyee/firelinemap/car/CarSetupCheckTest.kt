package com.rhecyee.firelinemap.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the car setup.
 *
 * The point of the check is to say which side the problem is on, so the tests
 * are about the verdict rather than the wording: telling someone to go and
 * fiddle with Android Auto settings when the build itself is wrong wastes an
 * hour in a vehicle, and so does the reverse.
 */
class CarSetupCheckTest {

    private fun facts(
        serviceDeclared: Boolean = true,
        serviceExported: Boolean = true,
        serviceEnabled: Boolean = true,
        serviceResolvable: Boolean = true,
        navigationCategoryResolvable: Boolean = true,
        descriptorDeclared: Boolean = true,
        descriptorDeclaresTemplate: Boolean = true,
        minCarApiLevel: Int? = 1,
        navigationTemplatesPermission: Boolean = true,
        accessSurfacePermission: Boolean = true,
        applicationIcon: Boolean = true,
        androidAutoInstalled: Boolean = true,
        androidAutoEnabled: Boolean = true,
        userId: Int = 0
    ) = CarSetupFacts(
        serviceDeclared = serviceDeclared,
        serviceExported = serviceExported,
        serviceEnabled = serviceEnabled,
        serviceResolvable = serviceResolvable,
        navigationCategoryResolvable = navigationCategoryResolvable,
        descriptorDeclared = descriptorDeclared,
        descriptorDeclaresTemplate = descriptorDeclaresTemplate,
        minCarApiLevel = minCarApiLevel,
        navigationTemplatesPermission = navigationTemplatesPermission,
        accessSurfacePermission = accessSurfacePermission,
        applicationIcon = applicationIcon,
        androidAutoInstalled = androidAutoInstalled,
        androidAutoVersion = "12.4.6",
        androidAutoEnabled = androidAutoEnabled,
        otherTemplateApps = emptyList(),
        debuggable = true,
        userId = userId,
        connectionType = CONNECTION_NOT_CONNECTED
    )

    private fun verdictOf(facts: CarSetupFacts) =
        interpret(facts).first { it.label == "Verdict" }

    private fun lineNamed(facts: CarSetupFacts, label: String) =
        interpret(facts).first { it.label == label }

    @Test
    fun `a correct build on a phone with the host blames the host list`() {
        val verdict = verdictOf(facts())
        assertEquals(CheckState.WARN, verdict.state)
        assertTrue(
            "should name the setting that is left",
            verdict.detail.contains("Unknown sources")
        )
        assertTrue(verdict.detail.contains("not the reason"))
    }

    @Test
    fun `a build fault is never blamed on the phone settings`() {
        listOf(
            facts(serviceDeclared = false),
            facts(serviceExported = false),
            facts(serviceEnabled = false),
            facts(navigationCategoryResolvable = false),
            facts(descriptorDeclaresTemplate = false),
            facts(minCarApiLevel = null),
            facts(navigationTemplatesPermission = false),
            facts(accessSurfacePermission = false),
            facts(applicationIcon = false)
        ).forEach { broken ->
            val verdict = verdictOf(broken)
            assertEquals(CheckState.FAIL, verdict.state)
            assertTrue(
                "a broken build must not send anyone to Android Auto settings",
                !verdict.detail.contains("Unknown sources")
            )
        }
    }

    @Test
    fun `a missing host is reported as the answer`() {
        val verdict = verdictOf(facts(androidAutoInstalled = false))
        assertEquals(CheckState.FAIL, verdict.state)
        assertTrue(verdict.detail.contains("not installed"))
    }

    @Test
    fun `a disabled host is told apart from a missing one`() {
        val verdict = verdictOf(facts(androidAutoEnabled = false))
        assertEquals(CheckState.FAIL, verdict.state)
        assertTrue(verdict.detail.contains("disabled"))
    }

    @Test
    fun `a secondary profile is called out, because nothing else ever does`() {
        val verdict = verdictOf(facts(userId = 10))
        assertEquals(CheckState.FAIL, verdict.state)
        assertTrue(verdict.detail.contains("secondary profile"))

        val line = lineNamed(facts(userId = 10), "Android user")
        assertEquals(CheckState.FAIL, line.state)
        assertTrue(line.detail.contains("user 10"))
    }

    @Test
    fun `declared but not exported reads differently from not declared`() {
        assertTrue(
            lineNamed(facts(serviceDeclared = false), "Car app service")
                .detail.contains("Not declared")
        )
        assertTrue(
            lineNamed(facts(serviceExported = false), "Car app service")
                .detail.contains("not exported")
        )
    }

    @Test
    fun `resolving without the navigation category is distinguished`() {
        val line = lineNamed(
            facts(serviceResolvable = true, navigationCategoryResolvable = false),
            "Discoverable by the host"
        )
        assertEquals(CheckState.FAIL, line.state)
        assertTrue(line.detail.contains("navigation category"))
    }

    @Test
    fun `the report is plain text with a mark on every line`() {
        val report = interpret(facts()).asReport("Header")
        assertTrue(report.startsWith("Header"))
        val marked = report.lines().filter { it.startsWith("[") }
        assertEquals(interpret(facts()).size, marked.size)
        assertTrue(marked.all { it.contains("]") && it.contains(":") })
        assertTrue(report.contains("[PASS] Car app service"))
    }

    @Test
    fun `connection state is reported without being a failure`() {
        listOf(
            CONNECTION_NOT_CONNECTED to "Not connected",
            CONNECTION_PROJECTION to "Projecting",
            CONNECTION_NATIVE to "head unit"
        ).forEach { (type, expected) ->
            val line = interpret(facts().copy(connectionType = type))
                .first { it.label == "Host connection" }
            assertEquals(CheckState.INFO, line.state)
            assertTrue("$type -> ${line.detail}", line.detail.contains(expected))
        }
    }
}
