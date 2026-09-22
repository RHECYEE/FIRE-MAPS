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
- Field-sized Measure, Where, Fireline, Resources, and Layers controls
- Tap-anywhere position readout in DDM, decimal degrees and the sheet's own
  UTM grid, each line copyable on its own
- Fire perimeter inference from dropped observations
- Foreground travel-recording service
- Persistent track records with GeoJSON line geometry
- Process-restart-compatible foreground service declaration

Imported sheets are rasterised at the resolution they are being viewed at
rather than as one fixed-size thumbnail, so an Arch E incident product stays
legible all the way in. MapLibre display, drawings, photos, and incident
package export are the next implementation increments.

## Where is this

Arm **Where** and tap any spot. It reports that position in
degrees-decimal-minutes, decimal degrees, and the active sheet's own UTM grid,
plus range and bearing from the current fix and the ground elevation. Any one
line copies on its own, or the whole block copies together, because which of
those is wanted depends on whether it is going over a radio or into a form.

## Fireline

For a fire with no published product yet, where the only thing anyone knows is
where people have been and what they saw from there.

Drop **FIRE** on ground that is burning or has burnt, and **NOT FIRE** on
ground you know is clean. Either can be a single point or, with **LINE ON**, a
run of them — a road walked with the tool going says far more than its two
ends do, and is treated as one observation along its whole length.

From those the app infers a perimeter and reports its acreage. Ground the
observations surround is inside it even if nobody stood there, so walking or
driving an edge gives a filled polygon rather than a ring. Negatives cut the
perimeter back towards themselves, and where they separate two groups of fire
the result comes back as two polygons — the split is inferred from them rather
than declared.

**Reach** is the one assumption in the result: how far apart observations may
be and still be joined up, and how far past them the fire is taken to run. It
is shown on the panel and adjustable, because past the last place anybody
stood, nobody knows. The acreage always carries what it was inferred from
alongside it; it is an interpolation between observations and never a surveyed
perimeter.

Observations are saved against the incident. The perimeter is not — it is
recomputed from them on load, so correcting one bad point later corrects the
shape.

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
- `fireline/`: perimeter inference from dropped observations
- `location/`: live location and foreground track recording
- `ui/`: Compose operational screen and theme
- `util/`: coordinate formatting

## Release builds

Signing details are never committed. `./gradlew :app:bundleRelease` reads them
from a `keystore.properties` beside this file:

```
storeFile=/absolute/path/to/upload-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

or, for CI, from `FIRELINE_KEYSTORE`, `FIRELINE_KEYSTORE_PASSWORD`,
`FIRELINE_KEY_ALIAS` and `FIRELINE_KEY_PASSWORD`. With neither present the
release build still runs and produces an unsigned bundle, so a fresh clone
builds for anyone.

## Planned sequence

1. MapLibre offline canvas and map-source abstraction
2. Local tile-pyramid importer
3. Track distance calculation and process-death recovery
4. Drawings, photos, and incident export/import
