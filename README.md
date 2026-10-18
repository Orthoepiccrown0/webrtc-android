# DirectStream SDK

Approved one-to-one audio and video calls for Android **API 30+**, with a ready-made Compose experience and APIs for custom interfaces.

**Version:** `1.0.0`. Apache 2.0 licensed. Install the SDK from [Maven Central](https://central.sonatype.com/artifact/io.github.orthoepiccrown0/directstream/1.0.0) under `io.github.orthoepiccrown0`. The runnable demonstration is `showcase`, installed as **`com.epiccrown.webrtctest`**.

## Contents

- [Quick start](#quick-start)
- [What you integrate](#what-you-integrate)
- [Run the showcase](#run-the-showcase)
- [Configure the services](#configure-the-services)
- [Integrate into your application](#integrate-into-your-application)
- [Android compatibility](#android-compatibility)
- [Identity, names and registration](#identity-names-and-registration)
- [Calls, lifecycle and custom UI](#calls-lifecycle-and-custom-ui)
- [Codecs, TURN and diagnostics](#codecs-turn-and-diagnostics)
- [Service plans, costs and capacity](#service-plans-costs-and-capacity)
- [Troubleshooting](#troubleshooting)
- [Build, test and documentation](#build-test-and-documentation)

## Quick start

This is an Android library, initialized once by your application. **`calls` is a `DirectStreamClient` instance**, not a separate service or an object created for each screen.

### 1. Install the SDK

Use Maven Central in your application’s `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Then add this to your Compose-enabled application module (`minSdk = 30`, `compileSdk = 37`):

```kotlin
dependencies {
    implementation("io.github.orthoepiccrown0:directstream:1.0.0")
}
```

The starter brings in the runtime, Compose UI and Firebase provider transitively; one SDK dependency is sufficient. The host supplies its Compose UI and FCM dependencies; see [the complete Gradle setup](#1-add-the-starter). For a custom backend or interface, see [advanced integration](#advanced-module-selection). To run the demo, follow [Run the showcase](#run-the-showcase).

### 2. Initialize in Application, before login

For real calls, first [configure Firebase and the Worker](#configure-the-services). Use your host's Firebase-generated resources plus `direct_stream_database_url`, `direct_stream_worker_url`, `app_name` and a monochrome notification icon. Register `.CallingApplication` in your [host manifest](#6-manifest-and-permissions).

```kotlin
import android.app.Application
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.DirectStreamConfiguration
import com.epiccrown.directstream.firebase.FirebaseWorkerConfiguration
import com.epiccrown.directstream.firebase.FirebaseWorkerProvider

class CallingApplication : Application() {
    lateinit var calls: DirectStreamClient
        private set

    override fun onCreate() {
        super.onCreate()
        calls = DirectStreamClient.initialize(
            this,
            DirectStreamConfiguration(
                provider = FirebaseWorkerProvider(
                    this,
                    FirebaseWorkerConfiguration(
                        projectId = getString(R.string.project_id),
                        applicationId = getString(R.string.google_app_id),
                        apiKey = getString(R.string.google_api_key),
                        databaseUrl = getString(R.string.direct_stream_database_url),
                        senderId = getString(R.string.gcm_defaultSenderId),
                        workerUrl = getString(R.string.direct_stream_worker_url),
                    ),
                ),
                appName = getString(R.string.app_name),
                notificationIcon = R.drawable.ic_call_notification,
                returnActivity = MainActivity::class.java,
                // Leave unset before login; supply it later with calls.activate(displayName).
                participantName = null,
                showDebugIdentifiers = false,
            ),
        )
    }
}
```

### 3. Retrieve the client and follow your login lifecycle

`DirectStreamClient.current()` retrieves the same process-owned instance; it throws if initialization has not run. `currentOrNull()` is available when initialization is uncertain. Injecting the initialized instance is equally valid.

```kotlin
import com.epiccrown.directstream.CallResult
import com.epiccrown.directstream.DirectStreamClient

// Call from your auth callbacks in a host-owned coroutine.
suspend fun onLoggedIn(displayName: String?): CallResult {
    val calls = DirectStreamClient.current()
    return calls.activate(participantName = displayName)
}

suspend fun onProfileNameChanged(displayName: String?): CallResult {
    val calls = DirectStreamClient.current()
    return calls.updateParticipantName(displayName)
}

suspend fun onLoggedOut(): CallResult {
    val calls = DirectStreamClient.current()
    return calls.deactivate()
}
```

Handle each `CallResult.Failure` and observe `calls.registration` for the registered device/code. Logout clears the local name and disables calling; offline remote revocation must complete before reactivation. Your app separately removes its own authenticated user/device mapping. A name update affects future requests; current calls keep their original snapshot. See [login and logout details](#login-profile-updates-and-logout).

### 4. Show call UI and forward push

Wrap your Activity's Compose content in `DirectStreamHost`; its `callUi` actions handle permission preflight:

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.api.CallMode
import com.epiccrown.directstream.ui.DirectStreamHost

@Composable
fun CallingContent(recipientCode: String) {
    val calls = DirectStreamClient.current()
    MaterialTheme {
        DirectStreamHost(client = calls) { callUi ->
            Button(onClick = { callUi.startCall(recipientCode, CallMode.Audio) }) {
                Text("Call")
            }
        }
    }
}
```

Render this inside a `ComponentActivity` and supply the other installation's current device code. `DirectStreamHost` supplies waiting, incoming and in-call screens with accept/decline/cancel/hangup controls. Invoke `callUi.requestIncomingAlerts()` from your own contextual UI to enable incoming alerts. For background/cold-start incoming calls, also [forward FCM messages and token changes](#5-forward-push-messages-and-token-changes), including the current token after activation. Use two configured installations to test a real call.

## What you integrate

The normal integration has four parts: initialize a process-owned client, activate calling when your app permits it, wrap your Compose content in `DirectStreamHost`, and forward FCM messages/token changes.

| SDK owns                                                        | Your application owns                                        |
| --------------------------------------------------------------- | ------------------------------------------------------------ |
| Installation registration and reusable device codes             | Business sign-in, user accounts and contacts                 |
| Explicit acceptance, expiry and one-call admission              | Mapping SDK registrations to your users/devices              |
| Incoming alerts, Android call integration and foreground media  | Firebase/Cloudflare accounts, deployment, quotas and billing |
| WebRTC audio/video, routing, mute, camera and codec negotiation | Host Activity, branding, theme and push forwarding           |
| Default call screens, permission preflight and call lifecycle   | Recipient selection and any application-specific history     |

There is no server to self-host. The default provider still needs trusted server-side code: a managed Cloudflare Worker and D1 database, plus Firebase Authentication, Realtime Database and FCM. Device codes locate installations; acceptance authorizes calls; ICE finds a network route. The code alone does not grant signaling or media access.

| Module                 | Responsibility                                                                                |
| ---------------------- | --------------------------------------------------------------------------------------------- |
| `sdk-api`              | Public models, state and identity/admission/signaling/push/relay contracts                    |
| `sdk`                  | `DirectStreamClient`, session/media runtime, Core-Telecom and incoming/background integration |
| `sdk-ui`               | `DirectStreamHost`, default Compose screens and video-rendering composables                   |
| `sdk-firebase`         | Named Firebase installation identity, realtime signaling and Worker adapter                   |
| `sdk-starter`          | Single dependency combining the default runtime, UI and provider                              |
| `showcase`             | Configured demonstration, QR testing, recents, settings and screen gallery                    |
| `integration-consumer` | Separate Gradle build checking Maven artifacts without project dependencies                   |

Incoming calls are part of the runtime. Hilt and Navigation 3 are not required by the SDK. The showcase uses both for its own wiring and navigation. QR scanning, pairing links, recents and the gallery are showcase features; production hosts normally select a recipient from their own user/device database.

## Run the showcase

The showcase consumes `io.github.orthoepiccrown0:directstream:1.0.0` from Maven Central by default, using the same SDK artifact as an external application. No local SDK publication or Maven credentials are needed to run it. Keep the existing Firebase/Worker configuration below.

### Prerequisites and configuration

Use Android Studio with the SDK/NDK versions pinned in [the version catalog](gradle/libs.versions.toml), its compatible bundled JDK, and the checked-in Gradle wrapper. Worker/Firebase tooling uses Node.js 24 LTS and `npm ci` with the committed lockfiles. Firebase emulator tests also require Java.

1. Open this repository and select the **showcase** run configuration.
2. Keep your existing `showcase/google-services.json`. It must contain an Android client for `com.epiccrown.webrtctest`. A clean checkout needs a download from your Firebase project's Android app settings.
3. Create root `stream.properties` from [stream.properties.example](stream.properties.example). Supply the deployed HTTPS Worker URL and the database URL if it is missing from the Firebase JSON:

```properties
WORKER_URL=https://your-worker.your-subdomain.workers.dev
DATABASE_URL=https://your-project-default-rtdb.your-region.firebasedatabase.app
FORCE_RELAY=false
```

The showcase derives `PROJECT_ID`, `APPLICATION_ID`, `API_KEY`, `DATABASE_URL` and `SENDER_ID` from the matching Firebase JSON client. Nonblank `stream.properties` values override those defaults. `APPLICATION_ID` here means the Firebase app ID (`1:…:android:…`), not the Android package. The showcase generates its Firebase resources itself; do not add a second Google Services plugin just for this sample.

For an already configured demo, retain its Worker URL, D1 binding, Firebase JSON and signing files from your local configuration. Do not provision replacement services merely to run the showcase. Cloud deployment is a separate explicit operation; Android builds do not deploy anything.

Local configuration and signing material are Git-ignored. Missing configuration can still be inspected through the showcase setup screens, but calls cannot initialize successfully.

```sh
./gradlew :showcase:assembleDebug :showcase:installDebug
```

For SDK development, opt in to local project modules with `-PshowcaseUseLocalSdk=true`. Ordinary showcase builds use Maven Central; this switch does not publish anything.

`installDebug` requires an available Android device/emulator. Use two installations to test a real call. The debug and release variants share an application ID, so installing one over the other requires a compatible signing identity.

### What the showcase demonstrates

| Screen/feature     | How it works                                                                                        |
| ------------------ | --------------------------------------------------------------------------------------------------- |
| Home               | Readiness, own device code, inline QR, Copy/Share and confirmed code regeneration                   |
| Call a device      | Manual code, QR or pairing-link normalization; never automatically places a call after scanning     |
| Waiting / Incoming | Matching verification value, five-minute deadline, explicit Cancel or Accept/Decline                |
| In call            | Full-screen remote video, cropped local preview, mute, audio output, camera flip and hangup         |
| Recent calls       | Newest 50 devices with incoming/outgoing attempts, outcomes, peer names, redial and removal         |
| Settings           | SDK/provider setup status, actual permissions/alerts, next-call codec preferences and Clear recents |
| Integration guide  | In-app API/setup examples; this README is the full reference                                        |
| Screen gallery     | Synthetic screen/component states without a second device or live calls                             |

The showcase chooses one of ten bundled participant names on first launch and persists it. Calls and history use the host-assigned peer name, falling back to the device code. Private nickname editing is not part of the app. Its own code/QR remains visible for testing, and debug builds explicitly enable labeled peer codes; release participant displays hide codes when a name exists.

Recents and preferences are local, with history excluded from backup. Redial still requires permissions and the peer's acceptance. An unavailable saved code must be replaced by the user; no directory lookup discovers a replacement automatically. An approved peer identity can merge entries after code regeneration without treating history as authorization.

[StreamingModule.kt](showcase/src/main/java/com/epiccrown/directstream/showcase/StreamingModule.kt) provides the singleton client through Hilt. [SampleConfiguration.kt](showcase/src/main/java/com/epiccrown/directstream/showcase/SampleConfiguration.kt) supplies provider settings, branding, the persisted name and codec defaults. [StreamingApplication.kt](showcase/src/main/java/com/epiccrown/directstream/showcase/StreamingApplication.kt) activates the demo and forwards its startup token. The showcase uses its own Navigation 3 adapter around the shared SDK runtime/screens; an ordinary host can use `DirectStreamHost` without copying that adapter.

## Configure the services

Skip provisioning when using the already configured demo. For a new integration, create services in the customer's own accounts. This is a fresh setup; there is no old-data import or cutover procedure to run.

### 1. Firebase

1. Create a Firebase project and register your host application's Android package.
2. Enable **Authentication → Sign-in method → Anonymous** for the SDK installation identity.
3. Create a **Realtime Database**, starting with denied access. Copy its exact regional HTTPS URL.
4. Publish [firebase/database.rules.json](firebase/database.rules.json). Root `read: false` / `write: false` alone blocks legitimate SDK signaling too; use the complete supplied participant rules. Do not switch the database to public/test mode.
5. Configure your host's default Firebase app and FCM using the [official Android setup](https://firebase.google.com/docs/android/setup). The SDK's Firebase provider and host FCM must use the same project/sender. Existing hosts retain their Firebase initialization and messaging service.
6. Create server credentials for the Worker in that same project. Its service account needs authorized Realtime Database REST access and FCM HTTP v1 send permission. Keep the JSON outside the repository and APK. Review least-privilege IAM for these operations; Android Firebase client configuration is not a server credential.

You can publish rules from the Firebase console or, after installing the pinned development tooling and authenticating the CLI, from the repository root:

```sh
npm --prefix firebase ci
./firebase/node_modules/.bin/firebase login
./firebase/node_modules/.bin/firebase deploy --only database --project YOUR_PROJECT_ID
```

If the host already uses Realtime Database, merge these paths/rules with its existing rules and run the security tests before publishing. The root Firebase config targets the database; confirm the selected project/instance before deploying.

### 2. Cloudflare Worker and D1

Install tooling, authenticate and create an empty private database:

```sh
cd worker
npm ci
npx wrangler login
npx wrangler d1 create directstream-coordination
```

Edit [worker/wrangler.toml](worker/wrangler.toml): set your Worker name, Firebase project/database URL and the returned D1 ID under `COORDINATION_DB`. For the existing demo, retain its configured values and use its existing database instead of creating another one.

Create the tables and mark the **new empty** database ready:

```sh
npx wrangler d1 migrations apply directstream-coordination --remote
npx wrangler d1 execute directstream-coordination --remote \
  --command "INSERT INTO service_metadata (key, value) VALUES ('ready', '1');"
```

Wrangler calls versioned SQL schema files “migrations”; this command creates the current schema. It does not import old registrations. The ready marker is a one-time initialization step after the schema succeeds, not an error-recovery command for an existing database.

For a first deployment, publish in maintenance mode, install the private Google secret and then enable requests:

```sh
npx wrangler deploy --var MAINTENANCE_MODE:true
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON < /absolute/private/path/service-account.json
npx wrangler deploy --keep-vars --var MAINTENANCE_MODE:false
```

Use the resulting HTTPS URL in the host configuration. Keep `TURN_ENABLED=false` and `FORCE_RELAY=false` for the direct-only demo. Existing deployments with a valid secret do not need the secret re-entered. [Deployment and operations](docs/DEPLOYMENT.md) documents every environment setting, local development, API routes and safe updates.

### 3. Verify setup before testing media

Check configuration validity, then activate and confirm registration. Verify push reaches `Registered`, and confirm notifications/channel access on the receiving device. Test request → accept → call → hangup with two clients, followed by cancellation while the receiver is locked. A locally valid URL, a successful build or a visible device code is not proof that all cloud services and Android alert permissions work.

## Integrate into your application

The examples use application-owned configuration and Android resource strings. Substitute your package, resources and service URLs. No QR implementation or sample controller is required.

### 1. Add the starter

Configure Maven Central in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Then add the SDK and the host's push/coroutine dependencies:

```kotlin
android {
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    buildFeatures { compose = true }
}

dependencies {
    implementation("io.github.orthoepiccrown0:directstream:1.0.0")
    // These versions match this repository; align with your application's dependency policy.
    implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
```

Use your normal Compose-enabled Android application configuration, including Activity Compose and Material 3 dependencies. See [publication checks](docs/PUBLICATION.md) and the runnable [standalone consumer](integration-consumer/build.gradle.kts).

### Advanced module selection

Use the starter above for the default integration. Advanced hosts can select modules instead:

| Integration                           | SDK dependency                                                       |
| ------------------------------------- | -------------------------------------------------------------------- |
| Runtime and custom backend/UI         | `io.github.orthoepiccrown0:directstream-runtime:1.0.0`               |
| Default Compose UI and custom backend | `io.github.orthoepiccrown0:directstream-ui:1.0.0`                    |
| Firebase provider with custom UI      | Runtime plus `io.github.orthoepiccrown0:directstream-firebase:1.0.0` |
| Provider contracts only               | `io.github.orthoepiccrown0:directstream-api:1.0.0`                   |

Gradle resolves shared SDK and WebRTC dependencies automatically. Kotlin packages remain `com.epiccrown.directstream`.

### 2. Initialize once in Application

This example uses Firebase resources generated by the host's Firebase setup. Define `direct_stream_database_url`, `direct_stream_worker_url` and `app_name` in the host's resources. Use a monochrome notification icon owned by your app.

Use the complete `CallingApplication` example in [Quick start](#quick-start), which initializes the client once with `participantName = null`. The `calls` property there is the returned `DirectStreamClient`; other components can retrieve that same client with `DirectStreamClient.current()` after Application initialization. `currentOrNull()` returns null before initialization instead of throwing.

Initialization also runs during cold-start push delivery. It validates configuration and discovers codecs without permission prompts, registration or capture. Equivalent initialization reuses the process client; conflicting configuration fails clearly. Keep the client above Activities/ViewModels and never create another native media engine for the same integration.

For Hilt, provide `DirectStreamClient.initialize(applicationContext, configuration)` from a `@Singleton` provider and inject that same instance into your Application, UI and messaging service. The showcase demonstrates this; Hilt is optional.

### 3. Activate when the host permits calling

Use an application/session-owned coroutine scope after your user has enabled calling or signed in:

```kotlin
import com.epiccrown.directstream.CallResult
import com.epiccrown.directstream.DirectStreamClient

// Retrieve the client initialized in CallingApplication; displayName comes from your signed-in user.
val calls = DirectStreamClient.current()
val displayName: String? = null // Replace with your authenticated user's display name.
when (val result = calls.activate(participantName = displayName)) {
    CallResult.Success -> Unit // Observe calls.registration for the registered device.
    is CallResult.Failure -> {
        // Map result.error.kind to your localized error/retry UI.
    }
}
```

These are suspending APIs. Collect `calls.registration` to sync the current device code to your own authenticated user/device endpoint. Fetch and forward the existing FCM token after activation as well as subsequent token changes:

```kotlin
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

// Run in a host-owned coroutine; catch failures and arrange retry on connectivity/resume.
val tokenResult = withTimeout(10_000) {
    calls.updatePushToken(FirebaseMessaging.getInstance().token.await())
}
// Registered confirms server registration; Queued does not. Handle Failure explicitly.
```

The demonstration autoactivates because it is a test app. A business application should persist calling eligibility and deactivate on logout; merely creating the client does not opt a user into incoming calls.

### 4. Wrap your Compose content

```kotlin
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.stringResource
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.api.CallMode
import com.epiccrown.directstream.ui.DirectStreamHost

data class CallRecipient(val displayName: String, val deviceCode: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val calls = DirectStreamClient.current()
        setContent {
            MaterialTheme {
                DirectStreamHost(client = calls) { callUi ->
                    // Replace this empty list with your user/device database state.
                    Contacts(
                        recipients = emptyList(),
                        onVideo = { callUi.startCall(it.deviceCode, CallMode.Video) },
                        onAudio = { callUi.startCall(it.deviceCode, CallMode.Audio) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Contacts(
    recipients: List<CallRecipient>,
    onVideo: (CallRecipient) -> Unit,
    onAudio: (CallRecipient) -> Unit,
) {
    Column {
        recipients.forEach { peer ->
            Text(peer.displayName)
            Button(onClick = { onVideo(peer) }) { Text(stringResource(R.string.video_call)) }
            Button(onClick = { onAudio(peer) }) { Text(stringResource(R.string.audio_call)) }
        }
    }
}
```

Add `video_call` and `audio_call` to your string resources. In a real contact list, use your own lazy list/navigation and state collection. `DirectStreamHost` wraps that content, supplies default waiting/incoming/in-call presentation and a return-to-call banner, and does not replace your navigation stack.

It validates recipients before permissions or server calls, handles separate microphone consent/receive-only fallback and binds call visibility/unlock state. Use `callUi.requestIncomingAlerts()` from a contextual UI action to request incoming-alert access. Activation alone does not grant notification permission.

### 5. Forward push messages and token changes

Keep your existing `FirebaseMessagingService` if the app has one. Route SDK results and other application messages there; do not register competing FCM services. These callbacks need a bounded lifetime. A minimal forwarding pattern is:

```kotlin
import android.util.Log
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.api.PushHandlingResult
import com.epiccrown.directstream.api.PushTokenResult
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class HostMessagingService : FirebaseMessagingService() {
    private val calls get() = DirectStreamClient.current()

    override fun onMessageReceived(message: RemoteMessage) {
        val result = runBlocking {
            withTimeoutOrNull(8_000) { calls.handlePush(message.data) }
        }
        when (result) {
            PushHandlingResult.Unrelated -> {
                // Hand off to your existing non-SDK message handling.
            }
            null, is PushHandlingResult.RetryableFailure -> {
                Log.w("Calling", "Incoming request validation needs retry")
                // Schedule bounded durable retry in your host (for example WorkManager).
                // Revalidate on retry; never display an alert from unvalidated payloads.
            }
            else -> Unit // Handled, Stale or Inactive.
        }
    }

    override fun onNewToken(token: String) {
        val result = runBlocking {
            withTimeoutOrNull(8_000) { calls.updatePushToken(token) }
        }
        if (result == null || result is PushTokenResult.Failure) {
            Log.w("Calling", "Push token registration needs retry")
            // Schedule retry; also forward the current token at the next activation.
        }
        // Forward to your own push infrastructure too, if your app has one.
    }
}
```

The getter retrieves the initialized process client directly, without an Application cast or a cached reference. Initialize synchronously in `Application.onCreate()` before this service handles callbacks, including cold-start push delivery. `current()` fails clearly if setup was skipped; using `currentOrNull()?.handlePush(...)` would silently discard the message when the client is missing. The showcase uses Hilt injection of that same singleton, which is also valid.

The retry scheduling comments are host integration points, not an implemented persistent job. Use your app's retry mechanism before relying on cold-start delivery in production. Treat `Queued` as pending registration, not success. Do not log token values, payloads, ownership proofs or SDK error objects containing private context. Realtime state and foreground reconciliation complement push but cannot guarantee delivery to a killed process.

### 6. Manifest and permissions

Merge your Application, return Activity and host FCM service into the host manifest:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Only declare this if your application qualifies for full-screen calling alerts. -->
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <application
        android:name=".CallingApplication"
        android:allowBackup="false"
        android:theme="@style/Theme.YourApp">
        <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service android:name=".HostMessagingService" android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

The runtime merges its private incoming Activity/service and camera, microphone, network, notification, own-call and foreground-service declarations. The SDK does **not** declare full-screen-intent permission for the host. Check the final merged manifest and [Android/Play eligibility](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en) before enabling it.

Camera is required only for Video. Microphone permission is separate; denial permits receiving audio. Notifications, applicable local-network access and full-screen access are contextual platform requirements. Refresh status on return from system settings. Disabling backup is the simple sample policy; if you retain backup, exclude SDK installation/auth/registration/session persistence so restored state cannot impersonate a fresh installation.

## Android compatibility

| Requirement              | SDK 1.0.0                                                                                                                        |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------- |
| Minimum Android version  | API 30 (Android 11)                                                                                                              |
| Consumer compile SDK     | API 37 minimum, declared in the published AAR metadata                                                                           |
| Native ABIs              | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` from WebRTC `1.3.9`                                                                  |
| Verified build toolchain | AGP `9.4.1`, Gradle `9.6.0`, daemon JDK `25`, Kotlin/Compose plugin `2.2.10`, NDK `29.0.14033849`                                |
| JVM bytecode target      | Java 11                                                                                                                          |
| Release shrinking        | Dependency consumer rules merge automatically, including WebRTC JNI preservation; no extra host package-wide keep rules required |

The toolchain above is the repository's tested configuration, not a claim that every older tool version is unsupported. Hosts may filter ABIs, but must retain a WebRTC native library for every ABI they ship. SDK AARs remain unminified; optimize the final host application. See [release verification](docs/RELEASE.md#keep-rules-and-logging).

Manifest declarations merge transitively. Custom UI hosts must request camera permission for Video and applicable local-network permission (the SDK requests it on API 37+); microphone permission is needed only when transmitting audio. Handle Bluetooth access when using Bluetooth routing and notification access for incoming alerts. The SDK's default host handles media permission preflight. Full-screen calling access is an explicit host integration described above. Use `CallFailure.requiredPermissions` and `requiresVisibleUnlockedUi` to present requirements and retry only after they are satisfied.

## Identity, names and registration

The Firebase provider uses a **dedicated named Firebase app** and anonymous installation identity. It does not reuse, sign out or delete your default Firebase Auth user. FCM uses the host's default Firebase app, so its project and sender must match the SDK provider. Separate-project push requires a custom integration.

- Codes contain six random uppercase characters, displayed as `ABC-234`. Ambiguous characters are excluded. Normalization accepts lowercase and display separators.
- Ordinary restarts preserve an active installation's code. Clearing data, reinstalling without preserved identity or revocation requires a new registration. There is no account recovery for installation identity.
- `calls.regenerateCode()` retires the old code and cancels pending setup requests; an approved call continues. Observe registration and update your directory mapping.
- `calls.deactivate()` immediately blocks local alerts, ends local calls and persists inactivity. Remote revocation is bounded; `RegistrationPhase.CleanupPending` means it is incomplete while offline. Subsequent activation finishes cleanup before issuing a fresh registration.
- `participantName` is optional, trimmed, blank-to-absent, limited to 40 Unicode code points and rejects control characters. Names need not be unique and do not verify identity.
- Names are snapshotted at request creation and used for waiting, incoming, ongoing, Telecom, notifications and outcomes. Named peers hide their codes unless `showDebugIdentifiers=true`; unnamed peers fall back to their code. Internal Firebase identifiers remain hidden.
- Initialize before login with `participantName = null`; call `activate(displayName)` after login. `updateParticipantName(displayName)` changes the active registration for future requests without restarting media or changing the device code. Passing null or a blank name clears the label.
- The selected name survives process restarts in SDK local storage. `DirectStreamConfiguration.participantName` is only the initial seed when no stored name selection exists. `configuration` describes initialization settings; observe `registration.device.participantName` for the registered name.
- `deactivate()` clears the stored label immediately and revokes the registration, including its name association. An initialization seed is not restored on later activations. Await logout before activating another account; no disposal/reinitialization is needed for login, logout or display-name changes.

### Login, profile updates and logout

Keep one client initialized in `Application` even when nobody is logged in. From your host-owned coroutine, use `calls.activate(participantName = displayName)` when login permits calling. A display-name edit uses `calls.updateParticipantName(newDisplayName)`; use null to clear it. You can also stage a name with `updateParticipantName` while inactive, then call `activate()` later. Staging a name does not register or enable incoming calls.

All three APIs return `CallResult`: handle typed failures and retry when appropriate. An active name update is confirmed by the provider before it is saved locally; a lost response can leave the server updated, so retry rather than assuming rollback. Existing pending requests and established calls retain their original name snapshots. Updating a live call's registration does not end its room or restart media.

On logout, await `calls.deactivate()`. Local calling and the stored name are cleared before remote cleanup; `CleanupPending` means the server association remains until connectivity/retry permits revocation. Fresh activation must complete that revocation first. Clearing only the display label with `updateParticipantName(null)` does not log out or revoke calling access. Retained call snapshots/history are not erased by clearing the registration label.

Your host must separately remove its authenticated user-to-device mapping on its own server, and retain/retry that removal if offline. Do not represent an SDK display name as proof of user identity. The [compiled lifecycle adapter](integration-consumer/src/main/java/com/epiccrown/directstream/consumer/CallingActions.kt) below includes `onLogin`, `onProfileNameChanged` and `onLogout` methods for this flow.

### Associate a device with your user securely

Your server owns the user/device mapping. Use `calls.ownershipProof()` to obtain a typed `ValueResult<DeviceOwnershipProof>`, send the proof over HTTPS to your authenticated server, and verify its Firebase token signature, issuer, audience, expiry and installation UID. Using private authorized service access, confirm that UID owns the submitted active registration in the coordination store before binding it to the business user. There is no public directory/ownership-query endpoint; implement this verification in your trusted server integration. Never trust a submitted code/UID alone or store the proof token as a durable contact identifier.

Support multiple devices per account and update mappings on regeneration/revocation. The SDK does not discover your users or synchronize your contact database.

## Calls, lifecycle and custom UI

Requests expire after five minutes. Both participants are reserved during pending setup and an active call, preventing concurrent admission. Acceptance creates a fresh participant-authorized room. SDP, ICE, capture and session TURN credentials begin only after approval. Session authorization has a one-hour deadline; a call is not an unlimited room lease.

Audio calls never request/create camera capture or enforce video codecs. Video calls support two-way video/audio and microphone-denied receive-only audio. Default controls provide mute, audio output, flip camera and end; there is no live Audio-to-Video upgrade.

While an established call is backgrounded or locked, audio/signaling continue and camera transmission pauses. Returning to the visible call screen resumes video. Back from waiting cancels; Back from incoming declines; Back from an established call returns to host content with a return banner. Dialogs do not pause capture. Process restoration never automatically resumes media. Incoming pushes, terminal events and acceptance all revalidate server state; cancellation/expiry dismiss alerts rather than reopen them.

Wake/full-screen presentation requires notification permission, an alerting incoming channel and applicable full-screen access. Android normally presents a heads-up notification while unlocked. A valid locked-device call can wake/show over the lock screen when allowed; acceptance and unlock are still required before capture. DND, OS/OEM restrictions and FCM delivery apply. See [Android full-screen changes](https://developer.android.com/about/versions/14/behavior-changes-14).

### Stable call operations

| Operation   | Public API                                              | Meaning                                                                    |
| ----------- | ------------------------------------------------------- | -------------------------------------------------------------------------- |
| Initialize  | `DirectStreamClient.initialize(context, configuration)` | Reuse the process client; no registration, permission prompts or capture   |
| Activate    | `calls.activate(participantName)`                       | Register after the host authorizes calling                                 |
| Connect     | `calls.startCall(code, mode, microphone)`               | Request a call; peer acceptance is required before media negotiation       |
| Accept      | `calls.accept(callId, microphone)`                      | Approve the displayed incoming request                                     |
| Update name | `calls.updateParticipantName(participantName)`          | Update/clear the label for future requests; inactive updates only stage it |
| Reject      | `calls.decline(callId)`                                 | Decline the displayed incoming request                                     |
| Cancel      | `calls.cancel(callId)`                                  | Cancel the displayed outgoing request                                      |
| Disconnect  | `calls.hangup(callId)`                                  | End the displayed call and release its media/session resources             |

Except for initialization, these operations suspend: launch them in a host-owned coroutine. `CallResult.Success` acknowledges an operation; observe `callState.session` for connection progress. Equivalent initialization reuses the client; conflicting configuration fails. Pass the call ID captured from the rendered state so a delayed action cannot affect a newer call.

The following custom UI adapter uses the client initialized in `CallingApplication` above. It is compiled verbatim in the [standalone consumer](integration-consumer/src/main/java/com/epiccrown/directstream/consumer/CallingActions.kt). Create it with `CallingActions(calls, onFailure)` and launch its methods from your host coroutine scope. Collect `observe` in a lifecycle-aware coroutine; render incoming Accept/Decline, outgoing Cancel, and connected Hangup controls from the observed session. Handle typed failures in your localized UI, including permission requests before retrying. The default `DirectStreamHost` already supplies those controls and lifecycle orchestration.

```kotlin
import com.epiccrown.directstream.CallResult
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.api.CallFailure
import com.epiccrown.directstream.api.CallMode
import com.epiccrown.directstream.api.CallState

class CallingActions(
    private val calls: DirectStreamClient,
    private val onFailure: (CallFailure) -> Unit,
) {
    suspend fun activate() = handle(calls.activate())

    suspend fun onLogin(displayName: String?) = handle(calls.activate(displayName))

    suspend fun onProfileNameChanged(displayName: String?) =
        handle(calls.updateParticipantName(displayName))

    suspend fun onLogout() = handle(calls.deactivate())

    // Collect in a lifecycle-aware host coroutine; cancellation stops collection.
    suspend fun observe(onState: (CallState) -> Unit) {
        calls.callState.collect { onState(it) }
    }

    // false means receive-only audio; enable the microphone only after permission is granted.
    suspend fun startCall(code: String) =
        handle(calls.startCall(code, CallMode.Audio, microphone = false))

    // Capture callId from the state rendered by the UI, before launching an action coroutine.
    suspend fun accept(callId: String) = handle(calls.accept(callId, microphone = false))

    suspend fun decline(callId: String) = handle(calls.decline(callId))

    suspend fun cancel(callId: String) = handle(calls.cancel(callId))

    suspend fun hangup(callId: String) = handle(calls.hangup(callId))

    private fun handle(result: CallResult) {
        when (result) {
            CallResult.Success ->
                Unit // Observe callState; success does not mean media is connected.
            is CallResult.Failure -> onFailure(result.error)
        }
    }
}
```

### Custom commands and observable state

| State flow     | Meaning                                                                     |
| -------------- | --------------------------------------------------------------------------- |
| `registration` | Activation phase, registered device and pending cleanup                     |
| `callState`    | Current session/mode/call ID, peer display and retained terminal attempt    |
| `media`        | Microphone/mute, audio endpoints, interruption and video visibility         |
| `codecs`       | Local capabilities, next-call defaults, active policies and switch progress |
| `diagnostics`  | Local transport and per-direction audio/video statistics                    |
| `setup`        | Local configuration check, initialization, push and relay readiness         |
| `events`       | Transient changes; not a replacement for the current state flows            |

A locally valid configuration is not successful cloud initialization. Likewise `PushReadiness.Queued` is not registered push and `RelayReadiness.DirectOnly` is not relay readiness.

For custom interfaces use `startCall(code, mode, microphone)`, `accept(callId, microphone)`, `decline(callId)`, `cancel(callId)`, `hangup(callId)`, `setMuted(callId, muted)`, `selectAudioOutput(callId, outputId)` and `flipCamera(callId)`. Bind actions to the displayed `callState.callId`; stale identifiers fail instead of acting on a replacement call. Commands return typed `CallResult` failures; permission failures carry requirements for the host to satisfy before retrying. Never claim microphone availability without its actual permission.

`dismissOutcome()` clears retained terminal feedback. `setVisible`, `setCallScreenVisible` and `configureMicrophone` are lifecycle/media inputs for custom adapters. `execute(CallCommand)` is library plumbing; hosts should use the scoped public functions above.

Theme the default host with your Material theme, replace its `callContent` slot, or use individual `DirectStreamIncoming`, `DirectStreamWaiting` and `DirectStreamInCall` composables and the renderer abstraction. Hosts omitting `DirectStreamHost` must supply equivalent permission, notification-intent, lifecycle and unlock orchestration. Native WebRTC objects are private. See [architecture/provider contracts](docs/ARCHITECTURE.md).

For logout deactivate first. For permanent feature teardown, remove video composables and call suspending `calls.dispose()` from an owned scope. Do not dispose on rotation, backgrounding or routine navigation. Failed remote cleanup is persisted for reconciliation rather than reported as completed.

## Codecs, TURN and diagnostics

### Codec policies

`CallCodecPreferences` contains independent audio/video `Auto`, `Prefer(codec)` or `Enforce(codec)` policies. VP8, H.264, VP9 and AV1 availability comes from the bundled WebRTC factories, with encoding/decoding and hardware/software availability reported separately. Explicit choices can use exposed software implementations; Android platform support alone does not establish WebRTC support.

Auto selects compatible families in both directions, prioritizing mutually hardware-supported AV1, H.264, VP9, VP8; otherwise VP8, H.264, VP9, AV1. Audio prefers Opus. Enforcement is checked against negotiation; incompatible policies return typed failures rather than silently falling back.

```kotlin
import com.epiccrown.directstream.api.CallCodecPreferences
import com.epiccrown.directstream.api.CodecId
import com.epiccrown.directstream.api.CodecPolicy

// Suspends; affects only the next submitted/accepted call.
calls.setNextCallCodecs(
    CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.AV1)),
)

// Explicit established-call switch; observe calls.codecs for commit/rejection/rollback.
val callId = calls.callState.value.callId
if (callId != null) {
    calls.switchCodecs(callId, CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.VP9)))
}
```

Showcase **Settings → Media codecs** persists next-call preferences and explains unsupported choices. It has no in-call codec picker. A switch command being accepted is not proof of commit; observe switch state and actual RTP/SDP results. See [codec policies and signaling](docs/CODECS.md).

### Relay configuration

The showcase is direct-only by default. STUN discovers addresses; it cannot relay blocked media. Production hosts should configure a session-authorized TURN credential service, either through the default Worker's TURN adapter or `DirectStreamConfiguration.relayProvider`. Credentials must be short-lived, issued only to approved participants and refreshed before expiry. Keep master API credentials on the server.

For the built-in Cloudflare adapter set `TURN_KEY_ID`, store `TURN_API_TOKEN` as a Worker secret, and explicitly enable `TURN_ENABLED=true`. Only then use `forceRelay=true` for deliberate relay tests. Provider setup and billing remain customer decisions; see [deployment settings](docs/DEPLOYMENT.md#turn).

### Debugging a call

Tap the in-call status chip to open grouped transport/media/codec diagnostics. Samples refresh every two seconds, with audio/video bitrates and actual per-direction codecs, resolution/FPS, RTT, loss, dropped frames, route and implementation details when available. Missing values show “—”; stale values are marked. Muted/silent audio is not a transport failure.

Debug showcase builds plant a Timber tree; release builds do not. Filter Logcat by `PairingApi`, `StreamSession`, `Push`, `Signaling`, `WebRtc` and `WebRtcStats`. Stream summaries are bounded to 15-second intervals with duplicate suppression. Application diagnostics exclude credentials, SDP, candidate addresses and internal identities. Peer-code debug labels are a separate explicit presentation option. No battery/CPU/compression improvement is claimed without measurement.

## Service plans, costs and capacity

**Checked 17 October 2026; USD before tax.** These are vendor service allowances, not SDK subscription tiers. Customers own the accounts, quotas and billing settings. The repository neither upgrades plans nor enables paid services. Verify linked pricing before a launch; allowances are shared with other workloads in the same account/project.

| Service                     | Free/demo plan                                                          | Paid path                                                                                                                                             | Used for                                                                |
| --------------------------- | ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Firebase Realtime Database  | Spark: 100 concurrent connections, 1 GB stored, 10 GB downloaded/month  | Blaze: up to 200,000 connections/database; first 1 GB storage then $5/GB-month; 360 MB/day download allowance then $1/GB                              | Participant events, room authorization, SDP/ICE                         |
| Firebase Authentication     | Anonymous installation identity; subject to Auth quotas                 | Review Identity Platform pricing if enabled                                                                                                           | SDK installation authentication                                         |
| Firebase Cloud Messaging    | No-cost messaging service                                               | Delivery remains best-effort                                                                                                                          | Incoming/terminal request hints                                         |
| Cloudflare Workers          | 100,000 requests/day, 10 ms CPU/invocation                              | Standard from $5/month: 10 million requests and 30 million CPU ms/month; overages $0.30/million requests and $0.02/million CPU ms                     | Authenticated admission, projection delivery, FCM and relay credentials |
| Cloudflare D1               | 5 million rows read/day, 100,000 rows written/day, 5 GB account storage | 25 billion rows read/month and 50 million written/month included; overages $0.001/million read, $1/million written; storage above 5 GB $0.75/GB-month | Private device/request/session records and durable jobs                 |
| Cloudflare TURN, if enabled | Current Realtime allowance: first 1,000 GB/month shared with SFU        | $0.05/GB egress beyond allowance                                                                                                                      | Relayed WebRTC media; not used in the default demo                      |

Sources: [Firebase pricing](https://firebase.google.com/pricing), [Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/), [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [Realtime/TURN pricing](https://developers.cloudflare.com/realtime/sfu/platform/pricing/). TURN account/billing requirements must be checked before enabling it; this SDK does not use an SFU.

D1 Free also limits each database to **500 MB** and each Worker invocation to **50 queries**; Paid raises those limits to 10 GB and 1,000 queries. An indexed write can account for additional rows written. A single D1 database still processes queries serially; targeted admission removes global record contention but does not create unlimited write capacity. [D1 limits](https://developers.cloudflare.com/d1/platform/limits/).

Auth account creation is limited to **100 new accounts/hour/source IP**, relevant to labs, shared networks and installation spikes. Projects upgraded to Identity Platform on Spark also have a **3,000 tier-1 daily-active-user limit**; that is not a universal limit for every basic Firebase Auth project. Check your actual configuration. [Authentication limits](https://firebase.google.com/docs/auth/limits).

### Planning for 1,000–10,000 users

Plan against simultaneous connected installations, call attempts and relayed minutes, rather than registered users alone:

- **Firebase connections:** an open mobile database connection counts even while no call is active. Several listeners in one client do not each count as a separate device connection. Activated clients can remain connected in the background while their process is alive. Spark's 100-connection ceiling is therefore not “100 calls” or “100 registered users.” See [connection limits](https://firebase.google.com/docs/database/usage/limits).
- **Worker load:** estimate registrations/reconciliation, request creation/accept/cancel/end, retries and maintenance. There is no periodic API status polling. CPU budget, external request latency and burst behavior still need measurement.
- **D1 work:** measure rows scanned/written, index/storage growth, transaction retries, participant history projection work and outbox backlog/age. Admission uses targeted records and version-checked batches; unrelated calls do not rewrite one global coordination tree.
- **Media:** direct peer-to-peer media bypasses Firebase/Worker bandwidth. TURN media does not. For illustration, two relayed 2 Mbps streams for one hour produce about 1.8 GB of egress before audio/protocol overhead. This is bitrate arithmetic, not a measured codec cost.
- **Android/push:** notification permission, OEM restrictions, cold starts and delivery latency need two-device tests regardless of account plan.

For illustration only, 10,000 installations averaging ten Worker requests/day already consume 100,000 requests before maintenance/retries. Registered users alone cannot predict daily requests or active connections. The repository's 10,000-device lookup test verifies bounded lookup work; it is not a production concurrency benchmark.

Start on free plans for a small demo, monitor actual usage and plan Blaze/Workers Paid before exceeding relevant limits. Configure billing alerts and operational budgets in customer accounts; budget alerts should not be treated as a guaranteed spending cap. Tune the Worker's query/job limits only to the chosen plan and measured workload. [Coordination limits and failure handling](docs/COORDINATION.md) explains recovery capacity and retention.

## Troubleshooting

| Symptom                                      | Check                                                                                                                                                                             |
| -------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Missing configuration / initialization fails | Matching Firebase Android client; all provider fields; exact HTTPS database and Worker URLs; distinguish local validation from service initialization                             |
| Device unavailable                           | Receiver activated against the same Worker/project; current code after reset; D1 ready/schema/binding; Worker service-account access; receiver not reserved by another setup/call |
| `IncomingRequest` in logs but no alert       | Notification permission, Incoming calls channel importance/pop-up setting, full-screen access, DND and battery restrictions; inspect `Push` suppression reason                    |
| No cold-start incoming calls                 | Existing startup token forwarded and server-registered; host FCM service initialized; matching project/sender; Worker FCM secret/IAM; delivery not guaranteed                     |
| Cancelled notification remains               | Receiver connectivity and current terminal request state; inspect event/push delivery and Worker outbox; stale pushes must revalidate before alerting                             |
| Connects but no media / ICE fails            | Both clients approved; local-network permission if applicable; emulator/NAT/firewall route; direct-only networks may require TURN                                                 |
| Unsupported VP9/AV1                          | Bundled WebRTC encoding and decoding capabilities on both peers; inspect enforced policy and actual negotiated RTP codec                                                          |
| Release APK does not update debug            | Same application ID but different certificate; use the matching signing identity. Uninstalling clears local identity/history                                                      |
| Worker 503 / coordination not ready          | Correct D1 binding, applied schema, ready marker and maintenance mode; inspect logs without exposing private payloads                                                             |
| HTTP retry after approval/end                | D1 may have committed while Firebase publication failed; retry the same scoped operation/identifier, never allocate a replacement request blindly                                 |

A Google Play services `hiddenapi … reflection: allowed` log is not evidence that SDK admission or notification presentation failed. A successful status request confirms server access, not permission to display an Android alert.

## Build, test and documentation

Run from the repository root unless stated otherwise:

```sh
./gradlew spotlessCheck verifySdkBoundaries sdkApiCheck
./gradlew :sdk-api:testDebugUnitTest :sdk:testDebugUnitTest :showcase:testDebugUnitTest
./gradlew :showcase:assembleDebug :showcase:lintDebug
./gradlew :showcase:assembleRelease :showcase:lintRelease
npm --prefix worker ci
npm --prefix worker run check
npm --prefix worker test
npm --prefix worker run format:check
npm --prefix firebase ci
npm --prefix firebase test
```

The Firebase tests use local emulators and the `demo-webrtc` test project. Worker tests use local SQLite; `npm --prefix worker run test:runtime` additionally uses local workerd/D1 and needs local sockets. Neither test command deploys production services.

Format Kotlin/Gradle with `./gradlew spotlessApply`, and TypeScript/XML with `npm --prefix worker run format`. Markdown can be formatted with `worker/node_modules/.bin/prettier --write README.md 'docs/*.md'`. Dependency/signing/local Firebase files stay outside formatting targets.

Release APK/AAB outputs are under `showcase/build/outputs/`. Whole-app R8/resource shrinking are enabled for release; SDK AARs stay unminified and ship consumer rules. Preserve signing keys and matching mapping/native symbol artifacts. See the dedicated release guide.

| Reference                                    | Contents                                                                     |
| -------------------------------------------- | ---------------------------------------------------------------------------- |
| [Documentation index](docs/README.md)        | Task-oriented guide to the documentation                                     |
| [Architecture](docs/ARCHITECTURE.md)         | Runtime ownership, module boundaries and custom-provider contracts           |
| [Coordination](docs/COORDINATION.md)         | D1 atomic admission, Firebase projections, failure semantics and maintenance |
| [Deployment](docs/DEPLOYMENT.md)             | Environment settings, fresh/local setup, API routes, TURN and operations     |
| [Codecs](docs/CODECS.md)                     | Capability discovery, policy resolution, signaling and live switching        |
| [Validation](docs/VALIDATION.md)             | Automated commands and emulator/physical-device verification matrix          |
| [Release builds](docs/RELEASE.md)            | R8, APK/AAB export, signing and crash symbols                                |
| [CI and tag releases](docs/CI.md)            | Tag-driven releases, version codes and GitHub secrets                        |
| [Publication](docs/PUBLICATION.md)           | Maven Central publishing, private credentials and local validation           |
| [Dependency inventory](docs/DEPENDENCIES.md) | Resolved dependencies and cached POM license declarations for review         |

Compose previews live alongside pure screens/components in `sdk-ui` and `showcase`. They use synthetic state rather than cloud sessions or media engines, including light/dark, denied permissions, paused video, landscape and enlarged text. The showcase Screen gallery provides an on-device entry point.

Physical-device audio/echo, headsets, lock/background behavior, FCM cold starts, restrictive-network TURN and sustained hardware codec/thermal performance remain explicit release checks. Local builds/tests do not establish those results. See [Maven Central publication](docs/PUBLICATION.md) for signed release commands and remaining distribution gates. Original project source is licensed under [Apache 2.0](LICENSE); third-party components retain their own licenses. iOS is not supported.
