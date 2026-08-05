plugins {
    kotlin("multiplatform")
}

/**
 * The web app's share of the phone app, compiled to JavaScript.
 *
 * This module owns no logic. It reads the Android app's own source files --
 * the ones with no platform imports -- and compiles them for the browser, so
 * the web app runs the same grid conversions, the same contour builder, the
 * same track detector and the same share codec as the phone. A second
 * implementation of any of those would eventually disagree with the first,
 * and the disagreement would be about where somebody is standing.
 *
 * Reading the sources rather than moving them is deliberate. The Android build
 * is untouched by this module existing, so a file that turns out not to be
 * portable breaks the web build loudly and leaves the working app alone.
 *
 * Adding a file here is a deliberate act. If it imports anything from the
 * platform, it does not belong.
 */
kotlin {
    js(IR) {
        moduleName = "fireline"
        browser {
            commonWebpackConfig {
                outputFileName = "fireline.js"
            }
        }
        // Tests run in Node, not a browser. Everything under test here is
        // arithmetic and string handling with no DOM in it, Node is faster,
        // and a headless browser needs sandbox flags that differ on every
        // machine -- which is a build that breaks for reasons unrelated to the
        // code. The bundle is still built for the browser.
        nodejs()
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            kotlin.setSrcDirs(listOf("src/jsMain/kotlin", "../app/src/main/java"))
            kotlin.include(
                // The web app's own entry point.
                "com/rhecyee/firelinemap/web/**",
                "com/rhecyee/firelinemap/geopdf/MapFrame.kt",
                "com/rhecyee/firelinemap/geopdf/UtmProjection.kt",
                "com/rhecyee/firelinemap/location/TrackColours.kt",
                "com/rhecyee/firelinemap/location/TrackDetector.kt",
                "com/rhecyee/firelinemap/location/TrackGeometry.kt",
                "com/rhecyee/firelinemap/location/TrackOverlap.kt",
                "com/rhecyee/firelinemap/map/Earth.kt",
                "com/rhecyee/firelinemap/map/GeoBounds.kt",
                "com/rhecyee/firelinemap/map/MapCoverage.kt",
                "com/rhecyee/firelinemap/map/MapProjection.kt",
                "com/rhecyee/firelinemap/map/TileMath.kt",
                "com/rhecyee/firelinemap/map/ViewClamp.kt",
                "com/rhecyee/firelinemap/measure/Measurement.kt",
                "com/rhecyee/firelinemap/resources/ResourceSymbol.kt",
                "com/rhecyee/firelinemap/share/GpxFormat.kt",
                "com/rhecyee/firelinemap/share/IsoTime.kt",
                "com/rhecyee/firelinemap/share/PolylineCodec.kt",
                "com/rhecyee/firelinemap/share/SharePackage.kt",
                "com/rhecyee/firelinemap/share/ShareText.kt",
                "com/rhecyee/firelinemap/share/TextCodec.kt",
                "com/rhecyee/firelinemap/share/TrackSimplify.kt",
                "com/rhecyee/firelinemap/terrain/ContourBuilder.kt",
                "com/rhecyee/firelinemap/terrain/ContourInterval.kt",
                "com/rhecyee/firelinemap/terrain/ContourRender.kt",
                "com/rhecyee/firelinemap/terrain/ElevationGrid.kt",
                "com/rhecyee/firelinemap/terrain/TerrainMath.kt",
                "com/rhecyee/firelinemap/util/CoordinateParser.kt",
                "com/rhecyee/firelinemap/util/GridCoordinates.kt",
            )
        }
        val jsTest by getting {
            kotlin.setSrcDirs(listOf("src/jsTest/kotlin"))
            dependencies { implementation(kotlin("test")) }
        }
    }
}

// The browser variant produces the bundle; it has no tests of its own.
tasks.matching { it.name == "jsBrowserTest" }.configureEach { enabled = false }
