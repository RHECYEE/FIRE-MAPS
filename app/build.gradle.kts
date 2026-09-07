import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Upload signing, kept out of the repository.
 *
 * Read from keystore.properties next to the root build file, or from the
 * environment when a build server holds them instead. Both are gitignored: a
 * key checked in is a key that has to be reset with Google support, and the
 * only warning is somebody else shipping an update.
 */
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun credential(property: String, variable: String): String? =
    signing.getProperty(property) ?: System.getenv(variable)

android {
    namespace = "com.rhecyee.firelinemap"
    compileSdk = 36

    defaultConfig {
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
        // The identity Play holds the app under. Deliberately not the same as
        // the namespace above: the namespace is where the code lives and can be
        // changed whenever, while this is fixed for the life of the listing and
        // cannot be altered after the first upload.
        applicationId = "com.firelinemaps"
        minSdk = 26
        targetSdk = 36
        // Bumped for the first store upload. versionCode has to climb with
        // every upload; versionName is what a crew reads in the listing.
        versionCode = 4
        versionName = "0.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("upload") {
            val store = credential("storeFile", "FIRELINE_STORE_FILE")
            // Absent on a machine without the key. The release build then falls
            // back to unsigned rather than failing to configure, so the project
            // still builds for anyone who only wants to run the tests.
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = credential("storePassword", "FIRELINE_STORE_PASSWORD")
                keyAlias = credential("keyAlias", "FIRELINE_KEY_ALIAS")
                keyPassword = credential("keyPassword", "FIRELINE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            /*
             * Shipped unminified on purpose, for now.
             *
             * R8 would take a few megabytes off, and the libraries here carry
             * their own keep rules, but the reflection-driven parts -- Room,
             * the Compose runtime and the car app templates -- fail at runtime
             * rather than at build time when a rule is missing. There is no
             * device in the build path to catch that, and this is software
             * somebody navigates a fire road with. Turn it on when there is an
             * instrumented run over a minified build to prove it.
             */
            /*
             * Set for whenever this app grows native code of its own.
             *
             * It produces nothing today, and Play's warning about missing
             * debug symbols cannot be answered: the only .so files in the
             * bundle come prebuilt and already stripped from the Compose
             * graphics-path artifact, with no symbol table left to extract.
             * Nothing here builds them, so nothing here can supply symbols
             * for them. The warning does not block a release.
             */
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }

            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("upload").takeIf {
                it.storeFile != null
            }
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Exported schemas are checked in. Room compares the entity definitions
// against them at build time, so a field added without a version bump shows
// up as a diff in review rather than as a crash on someone's device.
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Android Auto. `app` is the template library the car screen is built from;
    // `app-projected` is the host-side piece that makes the app discoverable by
    // Android Auto specifically, as opposed to a built-in Automotive OS head
    // unit. Without the second one the app builds and does nothing in the car.
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
