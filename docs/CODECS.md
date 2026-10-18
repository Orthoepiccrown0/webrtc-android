# Audio and video codecs

Codec discovery, policy resolution, enforcement and live renegotiation belong to the SDK. The showcase only persists host preferences and presents capabilities.

## Policies and public API

```kotlin
import com.epiccrown.directstream.DirectStreamConfiguration
import com.epiccrown.directstream.api.CallCodecPreferences
import com.epiccrown.directstream.api.CodecId
import com.epiccrown.directstream.api.CodecPolicy

val configuration = DirectStreamConfiguration(
    provider = provider,
    codecPreferences = CallCodecPreferences(
        video = CodecPolicy.Auto,
        audio = CodecPolicy.Auto,
    ),
)
```

Each media policy is independent:

- `Auto`: resolve a compatible family from both endpoints' encoding and decoding capabilities.
- `Prefer(CodecId.AV1)`: try AV1 first, permitting compatible fallbacks. A preference can be supplied across a device fleet even when some devices do not expose that codec.
- `Enforce(CodecId.VP9)`: require VP9. An unavailable local encoder/decoder or incompatible peer returns a typed codec failure; it never silently becomes VP8/H.264.

Selection is at codec-family level. Native SDP handles compatible profiles, packetization and levels. Enforcing H.264 does not force a particular profile or hardware implementation. RTX/FEC/other support codecs are retained where applicable and are not selectable primary codecs.

```kotlin
// Retrieve the client initialized once in Application; call these suspending APIs in a coroutine.
val client = com.epiccrown.directstream.DirectStreamClient.current()

// Update defaults only: no effect on an established or already-submitted call.
client.setNextCallCodecs(
    CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.AV1)),
)

// Explicit established-call change, without changing next-call defaults.
val callId = client.callState.value.callId
if (callId != null) {
    client.switchCodecs(
        callId,
        CallCodecPreferences(
            video = CodecPolicy.Enforce(CodecId.VP9),
            audio = CodecPolicy.Enforce(CodecId("audio/opus")),
        ),
    )
}
```

For live switches, `CallResult.Success` means the command was accepted. Observe `client.codecs` / `client.state.value.codecs` and `CallEvent.CodecChanged` for preparation, application, commit, rejection or rollback. `selection` changes only after both peers confirm native negotiation. `active` is the committed local policy; `requested` identifies the current proposal. `nextCall` remains independent. `FailureKind.CODEC` includes `codecReason`; asynchronous failures also appear in codec state.

Codec discovery is asynchronous: `capabilities == null` means not ready; `capabilitiesFailure` reports discovery failure. It initializes native factories without starting capture. No camera or microphone permission is required merely to inspect capabilities. Dispose the client when removing the feature, as described in [the integration guide](../README.md#calls-lifecycle-and-custom-ui).

## VP8, H.264, VP9 and AV1

The showcase lists all four explicitly. An unavailable codec is disabled with an explanation. WebRTC factory support is the source of truth, rather than Android's platform codec list alone. A codec may support decoding without encoding, hardware on one side and software on the other, or neither direction in a particular native build.

Explicit VP9/AV1 selection permits software codecs exposed by the bundled engine. No custom native build or added codec implementation is included. Both devices must have compatible encoding/decoding support because a call uses the same primary family in both directions. Receive-only audio remains valid when microphone permission is denied.

Auto ranks mutually hardware-available video families AV1, H.264, VP9, VP8. Otherwise it ranks VP8, H.264, VP9, AV1, then remaining mutually compatible families deterministically. Audio prefers Opus. Explicit preferences rank first; enforcement constraints on either endpoint must be satisfied. Conflicting preferences favor the existing offer coordinator's preference; conflicting enforcement fails.

Hardware availability is not a promise that an encoder will remain hardware-backed: native fallback can occur. Actual encoder/decoder implementation and profile are shown in diagnostics when RTP statistics provide them, otherwise “—”. Bitrates reset their baselines when a stream changes codec. Missing or silent audio metrics are not a codec/transport failure. This feature makes no measured CPU, battery, quality or compression-efficiency claim.

## Showcase behavior

Open **Settings → Media codecs**. Select video/audio independently and optionally enable **Enforce selected codec**. Settings persist privately in the showcase and apply only to the next call. Outgoing submission and incoming acceptance snapshot those defaults. Editing settings during a call or outgoing wait does not modify that snapshot or start renegotiation.

Before approval only local capabilities are known; peer compatibility is explicitly unknown. The showcase has no in-call codec picker. Hosts that need one can use the explicit live SDK command.

## Approved-room protocol and provider contract

Firebase and in-memory providers use the same codec protocol version 1, preserving explicit Audio/Video mode, per-direction capabilities and nullable video selection for audio-only calls. Custom `SignalingProvider` implementations must provide `publishCodecSignal` and `codecSignals`: authenticated participant-only streams that replay existing messages and deliver subsequent changes. Missing peer support produces a typed error; it does not introduce polling.

Messages use `CodecProtocol.encode/decode`, stable policy names, a versioned envelope and bounded payloads. Capability messages contain supported formats and availability, never camera data, SDP, identity-directory entries or credentials. Retain messages for the approved session and unsubscribe collectors on disposal. `SignalDescription.negotiationId` correlates SDP with the transaction and must be persisted/replayed along with type and SDP.

The existing sender role is the only offer coordinator. Initial selection is resolved after authorization and before the foreground media service/capture starts. Native SDP must confirm the selected family. Live transactions prepare, negotiate and commit; they time out after 15 seconds. Incompatible proposals leave existing codecs unchanged. A failed applied transaction performs a compensating negotiation with the previous policies, or ends the call if recovery cannot succeed. ICE restarts use the same coordinator. Hangup remains responsive while waiting for peer signaling.

Only role-owned messages under `rooms/{roomId}/signaling/codecs/{host|guest}/{messageId}` may be written. Reading any room data requires an active, unexpired approved session. Host-only offers and guest-only answers retain their existing restrictions. The SDP correlation field is an optional bounded `negotiationId` in rules, required by the SDK codec flow.

## Verification

Deploy the complete [Firebase rules](../firebase/database.rules.json) as part of [service setup](../README.md#configure-the-services). Both peers and custom providers must implement codec envelope 1 and correlated signaling; missing peer support produces a typed failure. The rules remain participant-restricted. Codec changes do not introduce periodic polling.

Unit tests cover family/profile resolution, enforcement, transaction ordering, conflicting changes, timeout/rollback, ICE restart serialization and stale messages. Rules tests cover participant isolation, pending/expired/end states and payload/correlation limits. Native emulator tests transmit VP9 and AV1 when available and validate actual SDP plus RTP, audio codec switching (SDP confirmation and RTP when audio is available), next-call isolation and paused-camera renegotiation. They cannot establish real-device hardware acceleration, thermal behavior, sustained quality, audible echo performance or OEM background delivery. Validate those separately on physical devices.

Audio-mode sessions carry `CallMode.Audio` in authorized admission and capability messages. They negotiate audio only: video selection is null, video enforcement is ignored and camera/video transceivers are never created. Both peers must agree on mode; no live Audio-to-Video upgrade is provided.
