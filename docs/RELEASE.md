# Optimized Android release builds

The runnable showcase (`:showcase`) enables whole-app R8 optimization in
`buildTypes.release.optimization`. With the project's AGP 9.4, `enable = true`
enables code shrinking, optimization, obfuscation and optimized resource shrinking,
including the Android default optimization rules. There is no package allowlist:
the showcase and SDK dependencies can all be optimized.

Debug builds remain unoptimized for debugging and Compose tooling. Release builds
are not debuggable. The supported native ABIs are `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`, supplied by the transitive WebRTC AAR. SDK AARs remain unminified; the consuming application performs
whole-program optimization and retains the public API that it uses.

## Exported application

Export the full demo from **`:showcase`**. Its debug and release application ID is
**`com.epiccrown.webrtctest`**, with no build-type suffix. In Android Studio's
**Generate Signed App Bundle / APK** wizard, select the `showcase` module and its
`release` variant. The showcase is the only runnable repository application.

```sh
./gradlew :showcase:assembleRelease :showcase:bundleRelease
```

The full demo outputs are:

- `showcase/build/outputs/apk/release/showcase-release.apk`
- `showcase/build/outputs/bundle/release/showcase-release.aab`

## Build

Install the NDK version pinned in `gradle/libs.versions.toml` for reproducible native
stripping/symbol extraction (currently `sdkmanager "ndk;29.0.14033849"`). No custom
WebRTC native compilation is introduced.

```sh
./gradlew :showcase:assembleRelease :showcase:bundleRelease :showcase:lintRelease
```

APK outputs are in `<module>/build/outputs/apk/release/`; App Bundles are in
`<module>/build/outputs/bundle/release/`. The showcase uses the local release signing
identity when configured, producing signed APKs and App Bundles. See the signing
configuration below. SDK library artifacts do not use the showcase APK signing identity; Central publication requires its own PGP configuration.

Use the App Bundle for store distribution. Device-specific ABI, density and
language delivery avoids installing all native WebRTC binaries on every device.
Universal APKs retain all supported ABIs for direct distribution and emulator
compatibility. This configuration does not remove codecs, locales or CPU targets.
R8 optimizes DEX and resources, not the prebuilt native WebRTC implementation.

## Local release signing

The showcase reads a host-owned release signing identity when configured. A clean checkout does not include private signing credentials. SDK library artifacts do not use the APK signing key.

Private, Git-ignored files:

- `signing/directstream-release.p12`: PKCS#12 keystore containing the private key.
- `signing.properties`: keystore path, alias and passwords.
- `signing/directstream-release.cer`: exported public certificate; no private key.

Back up the keystore and properties securely before distributing the app. Retain
the same identity for future updates; generating a replacement key does not update
apps signed with the previous key. Restrict private-file permissions to their owner. Do not paste their contents into logs or commit them.

Restore your existing identity or copy `signing.properties.example`
to `signing.properties` and supply your own identity. Paths in `storeFile` are
relative to the repository root, or absolute. Standard Gradle release tasks sign
automatically; no passwords need to be passed on the command line.

For CI, each environment variable overrides its corresponding local property:

| Property        | Environment variable             |
| --------------- | -------------------------------- |
| `storeFile`     | `DIRECTSTREAM_KEYSTORE_FILE`     |
| `storePassword` | `DIRECTSTREAM_KEYSTORE_PASSWORD` |
| `keyAlias`      | `DIRECTSTREAM_KEY_ALIAS`         |
| `keyPassword`   | `DIRECTSTREAM_KEY_PASSWORD`      |

Incomplete credentials or a missing keystore fail configuration without printing
secrets. With no credentials, a clean checkout can still build an unsigned release
APK (with the `-unsigned.apk` suffix). Verify signed exports using
`apksigner verify --verbose --print-certs <apk>` and `jarsigner -verify <aab>`.

An existing debug installation has a different certificate and cannot be replaced
in place by this release APK. On a test device, uninstalling the debug app first
allows installation, but clears its local identity, preferences and history. No app is
uninstalled automatically. If Firebase API-key restrictions require signing
fingerprints, register this release certificate's fingerprints for
`com.epiccrown.webrtctest` in the corresponding Firebase/Google project.

## Keep rules and logging

Host release-only rules live in `showcase/src/release/keepRules/`. They remove verbose, debug and informational
Timber calls; warnings and errors remain eligible for a host-provided logger.
The showcase does not plant a Timber tree in release. Do not move this logging policy into
SDK consumer rules: SDK integrators own their application's logging policy.

Dependencies supply their own consumer rules. In particular, the bundled WebRTC
AAR preserves its JNI entry points; Kotlin serialization, Firebase and Hilt rules
are merged automatically. No additional package-wide SDK/Firebase/Compose keep
rules or missing-class warning suppression are added here. If a future integration
uses reflection, add a narrow consumer rule to the library owning that reflection.

Keep rules in the AGP 9.3+ `keepRules` source-set directories use the `.keep` suffix.
Do not also add a legacy default ProGuard configuration: AGP already includes the
optimized Android defaults. R8 full mode and optimized resource shrinking are
already defaults with this AGP, so no compatibility-mode opt-outs or obsolete
Gradle flags are needed.

## Crash symbols and verification

Archive each released version's complete
`<module>/build/outputs/mapping/release/` directory alongside its APK/AAB. In
particular, `mapping.txt` is required to retrace obfuscated JVM crash stacks.
`configuration.txt` records the effective merged rules; `usage.txt` records removed
code. Resource-shrinker reports are emitted by AGP in the release outputs.

Release builds request `SYMBOL_TABLE` native debug metadata for dependencies that
provide it. Pre-stripped vendor `.so` files cannot supply missing debug information;
this option does not recreate native source symbols. Preserve any generated native
symbol archive with the matching release as well.

After changing dependencies or keep rules, build and lint the showcase release, then
exercise the optimized artifacts: startup/initialization, Navigation 3 restoration,
codec capability/serialization, approved calls, native video/audio, notifications
and cleanup. Debug-only tests do not establish release compatibility. Physical
device audio, hardware codec behavior and background delivery still require device
verification.

See [Enable app optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
and [Optimization for library authors](https://developer.android.com/topic/performance/app-optimization/library-optimization).

## SDK 1.0.0 publication

APK signing is separate from library publication. Use [PUBLICATION.md](PUBLICATION.md) for Maven Central publishing, private PGP signing credentials, local builds, ABI checks and dependency review. The Apache 2.0 SDK is distributed through Maven Central. The showcase uses that published SDK by default; pass `-PshowcaseUseLocalSdk=true` only when testing SDK source changes. Use the [service deployment instructions](DEPLOYMENT.md) for cloud service configuration and operations. Release APK signing does not deploy those services.

## Automatic versions from tags

See [CI and tag releases](CI.md). Showcase `versionName` follows `sdkVersion`;
`versionCode` is `major * 1,000,000 + minor * 1,000 + patch`, so `v1.0.1` produces
name `1.0.1` and code `1000001`. CI publishes the five SDK libraries to Central;
it does not publish a signed showcase APK to an app store. APK signing remains
a separate host-owned configuration.
