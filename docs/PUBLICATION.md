# Maven Central publication

The Maven Central namespace supplied for this project is `io.github.orthoepiccrown0`.
All five Android artifacts use the root `sdkGroup` and `sdkVersion` properties:

| Module            | Coordinate                                              |
| ----------------- | ------------------------------------------------------- |
| Starter           | `io.github.orthoepiccrown0:directstream:1.0.0`          |
| API               | `io.github.orthoepiccrown0:directstream-api:1.0.0`      |
| Runtime           | `io.github.orthoepiccrown0:directstream-runtime:1.0.0`  |
| Compose UI        | `io.github.orthoepiccrown0:directstream-ui:1.0.0`       |
| Firebase provider | `io.github.orthoepiccrown0:directstream-firebase:1.0.0` |

The Maven group is independent of the Kotlin/Android package names, which remain
`com.epiccrown.directstream`. No public Central release is claimed by configuring
these coordinates. The showcase and standalone consumer resolve from Maven Central
by default. For SDK development, the showcase supports `-PshowcaseUseLocalSdk=true`;
the standalone consumer supports `-PuseLocalSdkRepository=true`.

## Artifacts, licensing and isolation

The root Gradle convention uses the Vanniktech Maven Publish **base** plugin,
preserving the existing `release` publications and publishing only the five SDK
modules. The showcase, integration consumer, Worker, Firebase credentials and
application signing material are not Maven publications.

Each publication contains an unminified release AAR, sources JAR, documentation
JAR, POM and Gradle module metadata with checksums. The documentation JAR includes
an HTML entry point, integration guides and reviewed public JVM declarations;
it does not claim to be a generated Dokka API website. Kotlin KDoc is available
in the sources JAR. Apache 2.0 and developer/SCM metadata are present in each POM.

[LICENSE](../LICENSE) covers original SDK source. Dependencies retain their own
licenses. AARs include SDK license/notices under artifact-specific `META-INF`
paths; the UI AAR also carries [Material Icons attribution](../sdk-ui/licenses/material-icons-NOTICE.txt).
Review [the dependency inventory](DEPENDENCIES.md) and bundled WebRTC/native
notices before distribution. Cached Maven license declarations are a review aid,
not completed third-party distribution clearance.

## Validate locally without credentials

```sh
./gradlew spotlessCheck verifySdkBoundaries sdkApiCheck
./gradlew :sdk-api:testDebugUnitTest :sdk:testDebugUnitTest :showcase:testDebugUnitTest
./gradlew publishSdkLocally
./gradlew -p integration-consumer -PuseLocalSdkRepository=true assembleDebug assembleRelease --refresh-dependencies
./gradlew verifySdkPublication
```

Set `ANDROID_HOME` or provide ignored `integration-consumer/local.properties` with
`sdk.dir` before building the separate consumer. All five publications are under
`build/sdk-maven/io/github/orthoepiccrown0/`. The consumer uses only published
coordinates and no direct WebRTC/serialization dependency. Both starter and
Firebase-free debug/optimized release variants must build.

`verifySdkPublication` checks namespace/version/dependency edges in POM and Gradle
metadata, Central metadata fields, AAR/source/documentation/license artifacts,
checksums, the runtime version constant, WebRTC ABIs/JNI classes in all four
consumer APKs and effective release R8 rules.

## Private publication credentials

Generate a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/)
and create a dedicated OpenPGP publication key. Distribute its public key as
required by [Central's signing guide](https://central.sonatype.org/publish/requirements/gpg/).
The showcase APK keystore is a different identity and is never used for Maven signing.

Supply these properties through your **user-level** `~/.gradle/gradle.properties`
or CI secrets mapped to environment variables:

| Gradle property                                 | CI environment variable                         |
| ----------------------------------------------- | ----------------------------------------------- |
| `mavenCentralUsername`                          | `ORG_GRADLE_PROJECT_mavenCentralUsername`       |
| `mavenCentralPassword`                          | `ORG_GRADLE_PROJECT_mavenCentralPassword`       |
| `signingInMemoryKey`                            | `ORG_GRADLE_PROJECT_signingInMemoryKey`         |
| `signingInMemoryKeyId` (optional, 8 hex digits) | `ORG_GRADLE_PROJECT_signingInMemoryKeyId`       |
| `signingInMemoryKeyPassword` (if protected)     | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` |

Use the token-generated username/password, not the Portal login password.
`signingInMemoryKey` contains the ASCII-armored **private** key. Preserve its
newlines using your secret store. In Java properties files, put the entire armor
block on one physical line, replacing each newline with literal `\n`. If supplied,
`signingInMemoryKeyId` must be the final 8 hexadecimal digits of the signing key ID.
Never paste keys/tokens into project files, shell arguments, build logs or chat.
Keep Gradle caches/build outputs private; they are Git-ignored.

Do not put `sdkCentralRelease=true` in tracked configuration. It is a per-command
opt-in so ordinary builds and unsigned local publication do not need signing secrets.

## Prepare a signed release without uploading

After configuring the private signing key, run:

```sh
./gradlew -PsdkCentralRelease=true prepareSdkCentralRelease
```

This signs all publications and writes them to the local Maven repository. It does
not run Central upload tasks and does not need a Portal token. Inspect the artifacts
and signatures; after building the consumer, verify signature-file presence with:

```sh
./gradlew -PsdkCentralRelease=true verifySdkPublication
# Optional explicit signed-artifact checks using the configured JDK:
python3 scripts/verify_sdk_publication.py --signed
```

Cryptographically verify signatures with your OpenPGP tooling and the intended
public key. Normal unsigned local builds do not establish that real signing
credentials are valid.

## Upload, inspect, then release manually

From a reviewed, clean release checkout with signing and Portal credentials set:

```sh
./gradlew -PsdkCentralRelease=true publishSdkToMavenCentral
```

The aggregate task uploads **all five modules together** to a Central Portal
deployment. Automatic release is disabled. Wait for Portal validation, inspect the
deployment, and publish it manually in [Central Portal](https://central.sonatype.com/publishing/deployments).
Missing opt-in, signing key or token properties fail before Central preparation/upload.
No command requires your showcase Firebase configuration or APK signing key.

Once Central reports the deployment published, verify a clean external consumer
using only `google()` and `mavenCentral()`, then tag the matching Git commit
`v1.0.0`, create release notes and update the README availability status.
Published Central versions cannot be overwritten; fixes require a new version.
See [Central requirements](https://central.sonatype.org/publish/requirements/) and
[the publishing plugin workflow](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

## Versioning and compatibility

Root `sdkGroup` / `sdkVersion` drive all five publications; `sdkVersion` also drives
`DIRECT_STREAM_VERSION`. Update pinned consumer coordinates and examples for each
release. The runtime version remains a compile-time constant, so rebuild consumers
when upgrading. Patch releases are compatible fixes; minor releases add compatible
APIs/capabilities; major releases contain breaking API/behavior changes or raised
minimum Android/build requirements.

`api/*.api` is the reviewed public JVM facade baseline, including post-login name
activation/updates. Both aggregate and direct publication require boundary/API
checks. Never regenerate the baseline solely to silence a failure: review the change
first, then explicitly run `sdkApiDump`. JVM declaration checks do not replace
Kotlin source, behavior or custom-provider protocol compatibility review. SDK
versions are independent of the existing v1 admission/push/codec wire protocols.

## Release evidence and remaining device gates

Local validation on 18 October 2026 established 75 JVM unit tests and 20 isolated
SDK emulator tests, including name updates during a call, restart persistence,
offline logout and updates racing logout. All five artifacts were published locally
under the new namespace; starter and Firebase-free debug/optimized release APKs
built successfully. Metadata, checksums, license/source/documentation packaging,
four WebRTC ABIs, JNI classes and effective R8 rules were inspected. Artifact
scanning found no known local credential values or common credential-pattern matches.
Missing release opt-in and signing key checks failed as intended. On 18 October 2026, all 25 publication signatures were cryptographically verified
against release-key fingerprint `72812521D13A0BF610AB8AB7C89C78F7EA64BA09`.
The five modules were uploaded together to Central deployment
`aef52c54-2722-47af-9548-45a60029dcc6`, which reached `VALIDATED` with no errors.
The user completed the public release in Central Portal on 18 October 2026.
All five coordinates now resolve from the public Maven Central repository; each
public AAR matches the SHA-256 digest of its verified local release artifact.
The showcase debug/optimized release APKs, instrumentation APK, lint and 19 host
unit tests passed using the public SDK coordinates. All 20 isolated emulator
contract/lifecycle tests passed against that release. The starter and Firebase-free
standalone consumer debug/optimized release variants also built from Central,
without enabling the local Maven repository. Dependency inspection confirmed
external SDK artifacts; native/JNI packaging and effective WebRTC R8 rules passed.
Showcase-only debug helpers for service inspection and SDK preview rendering now
live in the showcase, since release AARs exclude SDK debug sources. These helpers
inspect SDK internals for repository tests and are not integration APIs.

The separate consumer checks packaging
and compile-time integration; it does not prove cross-process in-memory calling.

Physical-device audio/echo, headset behavior, OEM lock/background policies, actual
FCM cold starts, restrictive-network TURN and sustained hardware-codec/thermal
behavior remain explicit checks in [VALIDATION.md](VALIDATION.md). Confirm those
and third-party notice obligations when qualifying production deployments. Maven
artifact validation does not establish physical-device or cloud-service behavior.
