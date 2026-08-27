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

The map canvas is currently a deliberate placeholder. GeoPDF metadata parsing, raster tiling, MapLibre display, measurement geometry, markers, drawings, photos, and incident package export are the next implementation increments.

## Open in Android Studio

1. Extract the project.
2. Open the `FIRE-MAPS` directory in a current Android Studio release.
3. Allow Android Studio to install the requested Android SDK 35 components and Gradle dependencies.
4. Connect an Android 8.0 or newer device with USB debugging enabled.
5. Select the `app` run configuration and press **Run**.
6. Grant location and notification permissions when prompted.

Android Studio can generate or repair the Gradle wrapper if one is not present. The project uses Android Gradle Plugin 8.7.3 and Kotlin 2.1.0.

## Field-status warning

This is an early engineering prototype, not a field-ready navigation or life-safety product. Do not rely on it for operational navigation until GeoPDF registration, track recovery, accuracy validation, storage-failure handling, and device testing meet the acceptance criteria.

## Architecture

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
