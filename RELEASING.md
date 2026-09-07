# Releasing to Google Play

## One time: make an upload key

Play App Signing holds the real app signing key; this is the *upload* key, which
only proves an upload came from you. Make it once and keep it. If it is lost,
Google support can reset it, but that is a support ticket and a wait.

```bash
keytool -genkeypair -v \
  -keystore fireline-upload.jks \
  -alias fireline-upload \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12 \
  -dname "CN=Fireline Map, O=Rhecyee, C=US"
```

It asks for a password twice; PKCS12 uses the same one for the store and the
key. Keep the file somewhere backed up and out of the repository.

Then write `keystore.properties` in the project root — it is gitignored, and the
build reads it:

```properties
storeFile=/absolute/path/to/fireline-upload.jks
storePassword=…
keyAlias=fireline-upload
keyPassword=…
```

A build server can set `FIRELINE_STORE_FILE`, `FIRELINE_STORE_PASSWORD`,
`FIRELINE_KEY_ALIAS` and `FIRELINE_KEY_PASSWORD` instead. With neither present
the release build still runs and simply comes out unsigned, so the project
builds for anyone who only wants to run the tests.

## The package name is fixed

Play holds the app under `com.firelinemaps`, which is the `applicationId` in
`app/build.gradle.kts`. It cannot be changed after the first upload -- a
different package name is a different app, with its own listing, its own reviews
and its own installs.

It is deliberately not the same as `namespace`, which is still
`com.rhecyee.firelinemap`. The namespace says where the Kotlin lives and can be
changed whenever; the applicationId is the identity Play and every phone use.
Only the second one is a one-way door.

## Every release

1. Raise `versionCode` in `app/build.gradle.kts`. It must climb with every
   upload, and Play refuses a repeat. Raise `versionName` too if a crew would
   notice the difference.
2. Build and check:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:bundleRelease
```

3. Upload `app/build/outputs/bundle/release/app-release.aab`.

Confirm it is actually signed before uploading — an unsigned bundle is rejected
at the far end of a long upload:

```bash
jarsigner -verify -verbose:summary app/build/outputs/bundle/release/app-release.aab
```

## Start with internal testing

Not a public release. Internal testing takes an upload straight away, installs
through Play on testers' phones, and does not wait on review.

It is also the cheapest test of the Android Auto problem. Android Auto filters
its launcher on the installing package; a sideloaded build reports
`com.google.android.packageinstaller`, and one installed through Play reports
`com.android.vending`. If that is what has been keeping the app out of the car,
an internal-testing install settles it. Check afterwards with Settings →
Android Auto → Run the check: if "Has the car ever opened this app" changes
from Never, that was the cause.

## Minification

`isMinifyEnabled` is deliberately off. R8 would take several megabytes off the
bundle, and the libraries here ship their own keep rules, but Room, the Compose
runtime and the car app templates all fail at *runtime* rather than at build
time when a rule is missing — and nothing in the build path runs on a device to
catch that. Turn it on when there is an instrumented run over a minified build
to prove it, not before.
