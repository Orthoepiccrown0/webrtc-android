# SDK architecture and provider contracts

DirectStream SDK 1.0.0 has five publishable modules. SDK code uses `com.epiccrown.directstream`; the showcase retains its application ID. No artifact depends on an unpublished implementation project.

| Module       | Owns                                                                                                    | Dependencies                                 |
| ------------ | ------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| sdk-api      | Models, focused state, identity/admission/signaling/push/relay contracts, renderer abstraction          | Coroutines, serialization, Android contracts |
| sdk          | Process client, session state machine, WebRTC, Core-Telecom, incoming notifications, foreground service | sdk-api; private native/platform libraries   |
| sdk-ui       | Compose host, permissions and lifecycle integration, default and individual screens                     | sdk; Compose                                 |
| sdk-firebase | Dedicated SDK Firebase app/Auth, RTDB realtime transport, Worker API                                    | sdk-api; private Firebase/OkHttp             |
| sdk-starter  | Default integration dependency                                                                          | sdk-ui + sdk-firebase                        |

`sdk` and `sdk-ui` also work without Firebase. `InMemoryCallProvider` supports local demonstrations and contract tests. Optional presentation is a UI choice; incoming calls are part of the runtime.

## Runtime ownership

One Application-owned `DirectStreamClient` lives above Activity navigation. `DirectStreamClient.initialize(context, configuration)` creates/reuses it; other components retrieve the same instance with `current()` or inject it. `currentOrNull()` permits an initialization check. Initialization is side-effect limited. Activation registers the installation; logout deactivation persists inactivity and revokes the registration with bounded remote cleanup. A pending cleanup cannot be presented as successful server revocation. Reconciliation retries persisted abandoned rooms/requests; process restoration never starts media.

Initialize with a null name before login and use `activate(participantName)` when the host authorizes calling. `updateParticipantName(name)` stages an inactive name or updates an active registration through the existing provider registration contract, without reinitializing media. Selected names persist across process restarts; null clears the label. Requests snapshot names at creation, so pending and established calls retain their original labels. `deactivate()` clears the local name and disables calling immediately, then revokes remote registration; offline cleanup must complete before reactivation. Cleanup retries preserve a subsequent login's staged name. Host business-user/device mappings are maintained separately by the host server.

`internal/session/StreamController` serializes admission, mode, session ownership, codec transactions and ICE recovery. `internal/media/WebRtcClient` privately owns media factories, tracks, capture and statistics. `AndroidCallPlatform` is the sole production audio-endpoint/system-call owner, using Core-Telecom; legacy AudioManager routing remains only for standalone engine tests. Foreground notification actions, platform callbacks and Compose controls use the same session.

Approval and codec negotiation precede media startup. Audio mode has no video capture/transceiver or video enforcement. Video capture additionally requires permission, visible call UI and unlocked/interactive device. Established background audio continues while outgoing video pauses. Native objects never cross the supported renderer interface. Diagnostics sample locally every two seconds with bounded summaries.

`DirectStreamHost` wraps host navigation rather than replacing it. It handles permission launchers, stale notification validation, unlock gating and request back behavior. Dialogs preserve call visibility. Hosts may replace its call slot or use individual screens/custom state rendering. Saved UI values never authorize media.

## Provider contracts

- **Identity:** supplies a persistent installation identity. The default uses a named Firebase app, preserving default host Auth. Ownership proofs are short-lived values for host server verification, never log fields.
- **Admission:** authorizes every operation; privately allocates/retires device codes; owns five-minute requests, idempotency, approval and both-participant reservation. Named registration must support metadata refresh for an existing installation without rotating its code or ending an active room; null clears the label. Providers that do not support names return an explicit unsupported failure. Revocation must be idempotent, remove the active registration/name association, and must not allocate a replacement code. Mode is immutable per request. Incoming/request/session flows emit initial and subsequent authorized snapshots, including terminal changes. Cancellation racing approval must end the newly approved room. Callback codes are participant-only.
- **Signaling:** exchanges SDP/ICE only in approved participant rooms. Codec capability envelopes and negotiation transactions use correlated IDs. The Firebase and in-memory adapters use one first-release protocol: v1 API, push envelope and codec signaling. There is no compatibility negotiation. Reject stale/foreign messages; same family names do not prove profile compatibility. A sole offer coordinator serializes codec switches and ICE restarts.
- **Push:** host forwards messages/tokens. A push is a hint to revalidate, never permission to accept or capture. Typed readiness distinguishes queued tokens from server registration. Default FCM routing requires matching host/SDK project and sender. The Firebase adapter recognizes `type=directstream.incoming` with `protocolVersion=1`. Hosts forward an existing token at startup as well as token changes.
- **Relay:** obtains expiring ICE credentials using approved-session authorization, renews before expiry, and fails without exposing master credentials. No paid relay activates automatically. Already issued credentials can remain valid until expiry after hangup.

Provider exceptions map to typed failure categories. Implementations must be cancellable, avoid unbounded network calls, and apply server authorization independently of client rules. Transient failures reconnect through event streams and one-shot reconciliation, not periodic status polling. Private credentials, UID values, codes, SDP and candidate addresses do not belong in logs or diagnostics.

## Default service authority

The Cloudflare Worker uses private indexed D1 records for device codes, participant reservations, requests and sessions. A version-checked transactional batch commits changes and durable delivery jobs together. Firebase retains participant event views and session authorization projections used by the existing rules; per-path ETag/revision checks prevent stale delivery. Session authorization must publish before approval succeeds. Reads and writes never download/rewrite the global control tree. Native UID/IP limits remain approximate/per-location; target admission limits remain transactional. See [coordination design](COORDINATION.md) and [deployment and operations](DEPLOYMENT.md).

## Hosts and finding the examples

- `showcase`: installed application ID `com.epiccrown.webrtctest`, Hilt wiring, Navigation 3, QR/manual/link entry, local history and participant names, settings/guide and screen gallery. It consumes the Maven Central starter by default; `-PshowcaseUseLocalSdk=true` switches to source modules for SDK development. Those tools stay outside the SDK. Open **Screen gallery** on Home to inspect screens without a second device; open **Settings → View guide** for integration examples.
- `integration-consumer`: separate Gradle build, Maven dependencies only, fake provider, optimized release. It resolves from Central by default, with an optional `-PuseLocalSdkRepository=true` local-publication repository. It does not include repository projects.

See [quick start](../README.md#quick-start), [the integration guide](../README.md#integrate-into-your-application), [deployment instructions](DEPLOYMENT.md), [codecs](CODECS.md), and [publication checks](PUBLICATION.md). No remote deployment is performed by SDK builds.

The Gradle root is **DirectStream SDK**. `showcase` is the only runnable repository module. Its source namespace is `com.epiccrown.directstream.showcase`; its installed application ID remains `com.epiccrown.webrtctest` to preserve Firebase registration and local app data.

The existing Cloudflare deployment name (`webrtc-pairing`) is intentionally retained in `worker/wrangler.toml`, so renaming the SDK does not create a different Worker or change the configured endpoint. Local Worker tooling is named `directstream-sdk-worker`.
