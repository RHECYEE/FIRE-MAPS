package com.rhecyee.firelinemap.car

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the declarations that put this app in the car at all.
 *
 * These are not testable by using the app: an app missing any one of them
 * simply never appears in the Android Auto launcher, with no error anywhere to
 * say why. That is exactly how they came to be missing, so they are asserted
 * here instead, where deleting one fails the build.
 */
class AndroidAutoManifestTest {

    private val manifest: Element by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("manifest not found from ${File(".").absolutePath}")
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attribute(name: String): String =
        getAttribute("android:$name")

    @Test
    fun `the car app service is declared, exported and in the navigation category`() {
        val service = elements("service").firstOrNull {
            it.attribute("name").endsWith(".car.FirelineCarAppService")
        } ?: error("FirelineCarAppService is not declared")

        assertTrue(
            "the host binds this service from another process, so it must be exported",
            service.attribute("exported") == "true"
        )

        val filters = service.getElementsByTagName("intent-filter")
        assertTrue("the service needs an intent filter to be discovered", filters.length > 0)
        val filter = filters.item(0) as Element

        val actions = (0 until filter.getElementsByTagName("action").length).map {
            (filter.getElementsByTagName("action").item(it) as Element).attribute("name")
        }
        assertTrue(
            "the host looks for the CarAppService action",
            actions.contains("androidx.car.app.CarAppService")
        )

        val categories = (0 until filter.getElementsByTagName("category").length).map {
            (filter.getElementsByTagName("category").item(it) as Element).attribute("name")
        }
        assertTrue(
            "a map app has to be in the navigation category to draw on the surface",
            categories.contains("androidx.car.app.category.NAVIGATION")
        )
    }

    @Test
    fun `the automotive descriptor is pointed at`() {
        val declared = elements("meta-data").any {
            it.attribute("name") == "com.google.android.gms.car.application" &&
                it.attribute("resource") == "@xml/automotive_app_desc"
        }
        assertTrue("Android Auto reads this descriptor while enumerating apps", declared)
    }

    @Test
    fun `the descriptor says this app uses templates`() {
        val candidates = listOf(
            File("src/main/res/xml/automotive_app_desc.xml"),
            File("app/src/main/res/xml/automotive_app_desc.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("automotive_app_desc.xml is missing")
        val root = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

        val uses = root.getElementsByTagName("uses")
        val names = (0 until uses.length).map { (uses.item(it) as Element).getAttribute("name") }
        assertTrue("the descriptor must declare the template app type", names.contains("template"))
    }

    @Test
    fun `a minimum car api level is declared`() {
        val declared = elements("meta-data").any {
            it.attribute("name") == "androidx.car.app.minCarApiLevel" &&
                it.attribute("value").isNotBlank()
        }
        assertTrue("the host refuses to load an app that does not state this", declared)
    }

    @Test
    fun `the permissions a navigation app needs to draw a map are requested`() {
        val requested = elements("uses-permission").map { it.attribute("name") }
        assertTrue(
            "NavigationTemplate is refused without this",
            requested.contains("androidx.car.app.NAVIGATION_TEMPLATES")
        )
        assertTrue(
            "the map is drawn onto the car surface, which needs this",
            requested.contains("androidx.car.app.ACCESS_SURFACE")
        )
    }

    @Test
    fun `the application has an icon`() {
        val application = elements("application").firstOrNull()
            ?: error("no application element")
        assertTrue(
            "with no icon the car launcher and the phone both fall back to the default",
            application.attribute("icon").isNotBlank()
        )
    }
}
