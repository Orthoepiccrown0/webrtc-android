# Validation guide

Validate the SDK, configured showcase and customer service deployment separately. Automated correctness checks are not proof of physical-device behavior or production throughput. Use the first-release v1 Worker/push/codec protocol consistently.

## Automated repository checks

With the pinned Android toolchain and npm dependencies installed:

```sh
./gradlew spotlessCheck verifySdkBoundaries sdkApiCheck
./gradlew :sdk-api:testDebugUnitTest :sdk:testDebugUnitTest :showcase:testDebugUnitTest
./gradlew :showcase:assembleDebug :showcase:lintDebug
./gradlew :showcase:assembleRelease :showcase:bundleRelease :showcase:lintRelease
./gradlew publishSdkLocally sdkDependencyInventory
python3 scripts/dependency_licenses.py
./gradlew -p integration-consumer assembleDebug assembleRelease --refresh-dependencies
./gradlew verifySdkPublication
npm --prefix worker run check
npm --prefix worker test
npm --prefix worker run format:check
npm --prefix worker run test:runtime
npm --prefix firebase test
```

Showcase builds consume Maven Central by default. For SDK source changes, add
`-PshowcaseUseLocalSdk=true` to showcase build/test commands. To validate local
publications with the standalone consumer, add `-PuseLocalSdkRepository=true`
to its Gradle command; omit it to test the public Central artifacts.

Worker tests cover SQLite-backed admission/HTTP behavior, optimistic conflicts, atomic rollback, idempotency, participant privacy, publication failures, stale delivery, query budgets and retention. The 10,000-device directory check verifies point-lookup work does not grow with all installations; it is not a deployed concurrency benchmark. `test:runtime` needs local sockets and exercises real local workerd/D1 batch semantics. A dry run (`cd worker` then `npx wrangler deploy --dry-run`) checks bundling without deployment.

Firebase rules tests run only against local emulators using `demo-webrtc`. JVM/provider tests cover call modes, registration/name validation, stale actions, cancellation/approval races, cleanup and codec resolution/transactions. Boundary/API checks protect supported declarations and native-type isolation. The separate Maven-only consumer checks optimized integration without repository project dependencies.

Formatting covers Kotlin/Gradle, TypeScript and XML; check Markdown separately with `worker/node_modules/.bin/prettier --check README.md 'docs/*.md'`. Dependency inventory generation is a review aid, not license clearance.

## Emulator and UI checks

Build instrumentation with the isolated runner so these tests do not activate the live provider:

```sh
./gradlew :showcase:assembleDebug :showcase:assembleDebugAndroidTest \
  -PdirectStreamTestRunner=com.epiccrown.directstream.showcase.integration.SdkTestRunner
# After installing the APKs, deny camera permission for the typed-failure contract case.
adb -s YOUR_EMULATOR_SERIAL shell pm revoke com.epiccrown.webrtctest android.permission.CAMERA
adb -s YOUR_EMULATOR_SERIAL shell am instrument -w \
  -e class com.epiccrown.directstream.showcase.SdkClientTest,com.epiccrown.directstream.showcase.integration.SdkIntegrationTest \
  com.epiccrown.webrtctest.test/com.epiccrown.directstream.showcase.integration.SdkTestRunner
```

The showcase owns its debug-only SDK inspection/preview helpers so the suite can
run against published release AARs. Those helpers access SDK internals for testing;
production integration must use the public facade.

The isolated SDK suite also covers names supplied after login, live profile-name
updates without ending media, stored-name restart recovery, logout clearing,
offline revocation, and a name update racing logout. The host still owns its
business-user/device directory association.

Install the APK/test APK on the selected emulator before invoking instrumentation. The typed camera-permission case is skipped if permission is already granted; revoke it as shown to exercise the case. Inspect the individual test classes under `showcase/src/androidTest` for media, codec, crop and preview cases. Configure a local Firebase Auth emulator on port 9099 for host-Auth coexistence cases.

Verify light/dark themes, narrow/landscape/large-text layouts, TalkBack, 48 dp targets, system-bar contrast, named/unnamed/debug identifier visibility, empty/populated history and gallery states. Retain preview pixel/crop/mirroring regressions and ensure diagnostics do not obscure controls. Test QR/manual/link entry in the showcase; scanning must not place a call automatically.

Verify Back cancels waiting, declines incoming and returns Home during an established call while audio remains active/video pauses. Test rotation, process restoration, repeated taps, stale intents, notification return and dialog overlays. Saved UI must never restart capture after process death.

For incoming-alert tests, exercise granted and denied notification access, alerting/silent channels, allowed/unavailable full-screen access, locked/background/cold-start delivery, duplicates, expiry and caller cancellation. Restore test permission/channel settings afterward. Emulator alert tests do not establish vendor-device wake behavior.

## Configured two-device and physical-device matrix

| Area                | Required checks                                                                                                                                             |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Registration        | Persistence, regeneration/revocation, inactive restart, offline cleanup retry and fresh activation                                                          |
| Host identity       | Existing default Firebase Auth user remains unchanged; authenticated ownership mapping and account change cleanup                                           |
| Admission           | Explicit acceptance, matching verification, expiry, busy participants, approval/cancel races and unauthorized reads                                         |
| Push                | Startup token, refresh, matching project/sender, denied notifications, delayed/stale/terminal messages and cold start                                       |
| Audio/video         | Audio-only without camera, two-way media, receive-only microphone fallback, mute and camera visibility                                                      |
| Android integration | Unlock before capture, background/lock audio, video pause/resume, focus loss, system calls, headset switch/disconnect and notification actions              |
| Codecs              | Auto/Prefer/Enforce; VP9/AV1 software/hardware where available; actual SDP/RTP codec; concurrent switches, timeout/rollback and ICE recovery                |
| Diagnostics         | Missing/stale metrics, counter resets, silence/mute and bounded private-data-safe logs                                                                      |
| Network/relay       | Same and unrelated networks, emulator/phone differences, transient server disconnect/resume and restrictive-network TURN with authorized credential refresh |
| Release             | Optimized APK/native JNI startup, serialization, incoming wake, cleanup, signing and matching mapping/symbol retention                                      |

Test on two physical devices for audible audio/echo, headset/Telecom interactions, OEM lock policies, sustained hardware codecs, thermal behavior and actual FCM delivery. Direct-only success on one network does not prove restrictive-network connectivity.

## Cloud operations and load

Apply [fresh setup](../README.md#configure-the-services) only when provisioning services. Existing service configuration is not verified by local tests. Check Worker/D1 readiness, real credentials/IAM, participant Firebase rules, FCM status and server-authorized TURN before exercising production-like calls.

Measure concurrent Firebase connections, database bytes downloaded/stored, Worker CPU/errors/latency, D1 rows/query counts/conflicts/storage and outbox backlog/oldest age under the intended customer workload. Include reconnect storms, shared-IP registration bursts and recovery after service interruption. Do not derive capacity from registered-user counts or the directory unit test alone. See [coordination](COORDINATION.md) and [service plans](../README.md#service-plans-costs-and-capacity).

Any live smoke test must keep cleanup credentials privately before creating temporary identities, remove its own registrations/Auth users afterward and avoid host accounts. Test commands must not deploy, delete live data or activate paid services implicitly.
