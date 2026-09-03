2026 # Fireline Map

Offline-first Android incident mapping and field movement recorder for wildfire operations.

## Current prototype

This initial Android Studio project includes:

- Kotlin and Jetpack Compose application shell
- Incident-first Room database
- Seeded `Burnt Creek 2026` incident for testing
- Live fused-GPS coordinate panel
- Degrees/decimal-minutes and decimal-degree formats
- Tap-to-copy coordinates
- GPS accuracy and elevation display
- Field-sized Measure, Drop, Resources, and Draw controls
- Foreground travel-recording service
- Persistent track records with GeoJSON line geometry
- Process-restart-compatible foreground service declaration
- Android Auto map screen sharing the phone's sheet, position and track

The map canvas is currently a deliberate placeholder. GeoPDF metadata parsing, raster tiling, MapLibre display, measurement geometry, markers, drawings, photos, and incident package export are the next implementation increments.

## Open in Android Studio

1. Extract the project.
2. Open the `FIRE-MAPS` directory in a current Android Studio release.
3. Allow Android Studio to install the requested Android SDK 35 components and Gradle dependencies.
4. Connect an Android 8.0 or newer device with USB debugging enabled.
5. Select the `app` run configuration and press **Run**.
6. Grant location and notification permissions when prompted.

Android Studio can generate or repair the Gradle wrapper if one is not present. The project uses Android Gradle Plugin 8.7.3 and Kotlin 2.1.0.

## Android Auto

The app registers with Android Auto as a navigation app and draws the incident
map on the car display: terrain, the imported sheet, live position with an
accuracy ring, the track being recorded, and placed resources. Zoom, pan,
recentre, north-up/heading-up and arming travel recording are on the car's own
controls. The sheet shown is whichever one is open on the phone, and it follows
a change made there while the car screen is up.

### It has to be turned on for an unpublished build

A build that did not come from the Play Store is not offered by Android Auto
until the phone is told to accept one. This is a phone setting, not something
the app can ask for, and it is the usual reason a correctly built app is still
missing from the car:

1. On the phone, open **Settings → Apps → Android Auto → Additional settings**.
2. Scroll to the bottom and tap **Version** ten times to enable developer mode.
3. From the overflow menu, choose **Developer settings**.
4. Enable **Unknown sources**.
5. Reconnect the phone to the head unit.

Fireline Map then appears in the Android Auto launcher. Without step 4 the app
is installed and working and simply never listed.

### What has to be present for the app to be listed at all

Four declarations, all in `app/src/main/AndroidManifest.xml`. Missing any one of
them removes the app from the car launcher with no error reported anywhere,
which is why `AndroidAutoManifestTest` asserts them:

- a `CarAppService` (`car/FirelineCarAppService.kt`), exported, with the
  `androidx.car.app.CarAppService` action and the
  `androidx.car.app.category.NAVIGATION` category
- the `com.google.android.gms.car.application` metadata pointing at
  `res/xml/automotive_app_desc.xml`, which declares this a template app
- an `androidx.car.app.minCarApiLevel` metadata value
- the `androidx.car.app.NAVIGATION_TEMPLATES` and `androidx.car.app.ACCESS_SURFACE`
  permissions

The `androidx.car.app:app-projected` dependency is what makes the app
discoverable by phone-projected Android Auto specifically; `androidx.car.app:app`
alone builds and does nothing in the car.

### Trying it without a vehicle

The Desktop Head Unit runs the car display against a connected phone. Debug
builds accept any host so this works; release builds only accept the Google
hosts the Car App Library ships signatures for.

## Field-status warning

This is an early engineering prototype, not a field-ready navigation or life-safety product. Do not rely on it for operational navigation until GeoPDF registration, track recovery, accuracy validation, storage-failure handling, and device testing meet the acceptance criteria.

## Architecture

- `car/`: Android Auto service, map screen, and surface renderer
- `data/`: Room entities, DAO, and database
- `location/`: live location and foreground track recording
- `ui/`: Compose operational screen and theme
- `util/`: coordinate formatting

## Planned sequence

1. MapLibre offline canvas and map-source abstraction
2. GeoPDF geospatial dictionary reader
3. Local tile-pyramid importer
4. Track distance calculation and process-death recovery
5. Markers, resource history, and measurement tools
6. Drawings, photos, and incident export/import
