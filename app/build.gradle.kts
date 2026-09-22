import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Signing details never live in the repository, and neither does the key.
// They are read from a keystore.properties beside the root build file, or
// from the environment when CI supplies them as secrets. With neither present
// the release build still runs and simply produces an unsigned bundle, so a
// fresh clone builds for anyone.
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSetting(property: String, variable: String): String? =
    (signingProperties.getProperty(property) ?: System.getenv(variable))
        ?.takeIf { it.isNotBlank() }

val releaseStorePath = signingSetting("storeFile", "FIRELINE_KEYSTORE")

android {
    namespace = "com.rhecyee.firelinemap"
    compileSdk = 35

    defaultConfig {
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
        applicationId = "com.rhecyee.firelinemap"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (releaseStorePath != null) {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = signingSetting("storePassword", "FIRELINE_KEYSTORE_PASSWORD")
                keyAlias = signingSetting("keyAlias", "FIRELINE_KEY_ALIAS")
                keyPassword = signingSetting("keyPassword", "FIRELINE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Left off for the first release deliberately. Shrinking changes
            // what ships in ways that only show up at runtime, and nothing in
            // this build has been on a handset yet; a slightly larger bundle
            // that is known to be the code that was tested is the better
            // trade until it has been.
            isMinifyEnabled = false
            signingConfig =
                if (releaseStorePath != null) signingConfigs.getByName("release") else null
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
