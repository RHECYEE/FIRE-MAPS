plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    // The web module compiles the app's platform-free Kotlin to JavaScript.
    id("org.jetbrains.kotlin.multiplatform") version "2.1.0" apply false
}

/**
 * Use the Node that is already installed.
 *
 * Kotlin/JS downloads its own Node and Yarn by default, from repositories it
 * adds itself -- which this build forbids, and which would fail behind a
 * firewall anyway. The machine has both, so it uses those.
 */
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>()
        .download = false
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>()
        .download = false
}
