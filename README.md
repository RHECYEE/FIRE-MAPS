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
- USGS topographic base map, with or without an incident sheet
- Renameable incident
- Field-sized Measure, Where, Fireline, Resources and Layers controls
- Tap-anywhere position readout in DDM, decimal degrees and the sheet's own
  UTM grid, each line copyable on its own
- Fire perimeter inference from dropped observations
- Slope shading and shaded relief, drawn from elevation
- Measured legs and committed perimeters left on the map, phone and car
- Foreground travel-recording service
- Persistent track records with GeoJSON line geometry
- Process-restart-compatible foreground service declaration
- Android Auto map screen sharing the phone's sheet, position and track

## The base map

Terrain is a map in its own right, not only the fill around an imported sheet.
With no GeoPDF open the app draws USGS topo around the operator and places
position, tracks, markers and measurements on it; an incident sheet drops on top
when one is imported. **Layers → Terrain only** selects it deliberately when
sheets are present, and the choice survives a restart.

This works by handing the canvas a synthetic georeferenced sheet
(`geopdf/TerrainSheet.kt`) covering 40 km of ground around the operator, with a
blank page behind it. Everything downstream already places things by converting
through a frame, so nothing needed a second rendering path. Before this the
canvas returned "NO MAP IMPORTED" and drew nothing at all without a GeoPDF,
terrain included.

The topography switch in Layers now actually stops the terrain drawing. It
previously only gated the background pre-download, so turning it off left the
tiles on screen.

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

## Slope shading

Contour lines are the exact reading, but they have to be counted and
interpolated between, and the question a crew boss actually asks -- *can we
get on that* -- wants answering in the time it takes to glance at a phone on a
dashboard.

**Layers -> Slope shading** tints ground by how steep it is, in the bands
equipment gets talked about in:

| Band | | |
|---|---|---|
| 0-25% | Gentle | most equipment travels |
| 25-40% | Moderate | dozer work gets directional |
| 40-55% | Steep | past most dozer limits |
| 55-75% | Very steep | handline and hose lays only |
| 75%+ | Extreme | chutes and chimneys |

Gentle ground is left untinted deliberately: most of most maps is gentle, and
colouring it would put a wash over the whole sheet to say nothing. The tint
starting at all is the signal.

The figures are Horn's method over the same 3DEP elevation the contours are
traced from, so a band and the lines crossing it can never disagree. They
describe the ground, not what may be done on it -- what a machine or a crew
can hold depends on soil, fuel, aspect, weather and who is driving, none of
which is in a terrain model.

**Layers -> Shaded relief** lights the ground from the northwest underneath
the tint. Off by default: both basemaps already carry relief of their own, so
a second one mostly muddies the first. It earns its place where the printed
shading is too faint to read.

Both are drawn on the Android Auto screen as well, where they matter most --
somebody driving cannot count contour lines.

## Leaving things on the map

Both tools produce a shape that used to stop existing the moment the tool was
put away, which was wrong the same way for both: a leg somebody measured is a
leg somebody is about to drive, and a perimeter radioed in at fourteen thirty
happened whether or not anyone is still editing it.

The measuring tool's **KEEP** leaves the current leg up, labelled with its
figure, and clears itself so the next one can be laid down straight away.
Several can be up at once, which is the point -- three measured legs is how a
route gets followed. The perimeter tool's **SAVE** leaves the polygon it
worked out, labelled with the time and the acreage, so an 0600 and a 1400
perimeter on one map show growth.

A kept shape is a snapshot, not a live view of whatever the tool currently
holds. The fireline observations stay separately editable underneath, so
correcting a bad point still corrects the working perimeter without
disturbing one already committed.

Everything kept is drawn on the Android Auto screen too, and labelled there
whatever the zoom -- a driver cannot pinch to find out which leg is which.
**Layers -> Left on the map** lists them and takes them off again.

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
- `geopdf/`: GeoPDF reading, and the synthetic terrain sheet
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

or, for CI, from `FIRELINE_STORE_FILE`, `FIRELINE_STORE_PASSWORD`,
`FIRELINE_KEY_ALIAS` and `FIRELINE_KEY_PASSWORD`. With neither present the
release build still runs and produces an unsigned bundle, so a fresh clone
builds for anyone.

## Planned sequence

1. MapLibre offline canvas and map-source abstraction
2. Local tile-pyramid importer
3. Track distance calculation and process-death recovery
4. Drawings, photos, and incident export/import
