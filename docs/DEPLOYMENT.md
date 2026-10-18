# Provider deployment and operations

The default provider uses a customer-owned Cloudflare Worker/D1 database and Firebase Auth, Realtime Database and FCM. All API routes, push and codec envelopes use the first-release protocol version 1. Android builds never deploy services or upgrade plans.

Follow [README → Configure the services](../README.md#configure-the-services) for fresh provisioning, Firebase rules, D1 schema/ready initialization and the first Worker deployment. Existing configured services can be reused. The supported setup initializes an empty D1 database with `worker/migrations/0001_coordination.sql`; there is no Firebase-export import or legacy cutover command.

## Configuration reference

Edit `worker/wrangler.toml` for the intended account/environment. Its checked-in `webrtc-pairing` name is the existing demonstration deployment; changing it creates/selects a different Worker and endpoint.

| Setting                              | Meaning/default                                                                   |
| ------------------------------------ | --------------------------------------------------------------------------------- |
| `FIREBASE_PROJECT_ID`                | Project owning SDK Auth, database and host FCM sender                             |
| `FIREBASE_DATABASE_URL`              | Exact HTTPS Realtime Database URL, including region                               |
| `COORDINATION_DB` binding            | Private D1 database with the current schema and `service_metadata.ready=1`        |
| `GOOGLE_SERVICE_ACCOUNT_JSON` secret | Authorized Google server credentials for database REST operations and FCM HTTP v1 |
| `MAINTENANCE_MODE`                   | `false`; `true` rejects normal operations and stops scheduled work                |
| `TURN_ENABLED`                       | `false`; explicit opt-in to the configured credential service                     |
| `TURN_KEY_ID`                        | Cloudflare TURN key identifier, empty until configured                            |
| `TURN_API_TOKEN` secret              | Server-side TURN API credential, needed only when TURN is enabled                 |
| `D1_QUERY_BUDGET`                    | `50`; per-invocation SQL statement budget, including transactional batches        |
| `MAINTENANCE_JOB_LIMIT`              | `2`; maximum recovery delivery jobs considered per scheduled run                  |
| `MAINTENANCE_MUTATION_LIMIT`         | `1`; bounded expiry/pruning mutation work per run                                 |
| Minute cron                          | Retries durable jobs and performs bounded expiry/retention cleanup                |
| `UID_LIMITER` / `IP_LIMITER`         | Native limits: 20/40 operations per minute respectively                           |

Native rate limits are approximate and per Cloudflare location, not global quotas. Target request limits are enforced within admission transactions (five per minute). Consider shared NATs when tuning IP limits. See [Worker rate-limit semantics](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/).

Never place service-account JSON, TURN master tokens or private signing keys in Android code/resources. Public Firebase client identifiers and endpoint URLs belong to host configuration. Maintain separate project/database/Worker configuration per customer/environment; do not treat one demonstration account as a multi-customer production service.

## Local development

From `worker/`, after `npm ci`:

```sh
npx wrangler d1 migrations apply directstream-coordination --local
npx wrangler d1 execute directstream-coordination --local \
  --command "INSERT INTO service_metadata (key, value) VALUES ('ready', '1');"
npm run dev
```

The ready insertion is only for an empty database after schema creation; omit it if already initialized. For any intentional local access to Firebase/FCM, supply credentials through a Git-ignored `.dev.vars` file and use an isolated development project. Wrangler's local D1 binding does not make remote Firebase endpoints local. The production Android provider requires HTTPS URLs; default localhost HTTP is not a valid production configuration.

Validate without deployment:

```sh
npm run check
npm test
npm run test:runtime
npm run format:check
npx wrangler deploy --dry-run
```

`test:runtime` needs local sockets and exercises workerd/D1 semantics. The dry run bundles the Worker; it does not initialize a remote database or prove remote permissions, quota or FCM delivery.

## Authenticated API

All operations are JSON POSTs with `Authorization: Bearer <SDK Firebase ID token>`. Android normally calls these through the provider. Server authorization is required independently of Firebase client rules. Responses must expose only participant-authorized fields; no public code directory exists.

| Route                   | Body/behavior                                                                                  |
| ----------------------- | ---------------------------------------------------------------------------------------------- |
| `/v1/device/register`   | `{ "participantName": "Optional name" }`; null clears the name, omission preserves it          |
| `/v1/device/reset`      | Optional participant name; retire old code and cancel pending setup; approved call continues   |
| `/v1/device/revoke`     | `{}`; idempotent registration/name/push revocation                                             |
| `/v1/device/settings`   | Legacy `{ "enabled": false }` or `true`; compatibility no-op returning enabled status          |
| `/v1/device/push-token` | `{ "token": "FCM_TOKEN" }`                                                                     |
| `/v1/requests/create`   | `{ "code": "ABC234", "requestId": "FRESH_UUID", "mode": "Video" }`; mode is `Audio` or `Video` |
| `/v1/requests/incoming` | `{}`                                                                                           |
| `/v1/requests/status`   | `{ "requestId": "REQUEST_UUID" }`                                                              |
| `/v1/requests/approve`  | Same scoped request body; retry returns the same approved room                                 |
| `/v1/requests/reject`   | Same scoped request body                                                                       |
| `/v1/requests/cancel`   | Same scoped request body; handles approval races                                               |
| `/v1/sessions/status`   | `{ "roomId": "APPROVED_ROOM_UUID" }`                                                           |
| `/v1/sessions/end`      | Same scoped room body; releases both participants                                              |
| `/v1/turn-credentials`  | Same scoped room body; enabled relay and approved active participant required                  |

The Android client uses registration both for `activate(participantName)` and active `updateParticipantName(...)` calls. A metadata update preserves the installation code and active room; names already snapshotted into requests/sessions remain unchanged. `deactivate()` removes the active registration/name association. This does not remove the host application's own authenticated user/device mapping or retained call history. These operations use the existing v1 routes and require no separate backend endpoint for profile edits.

Use fresh UUIDs for new requests and reuse the identifier when retrying that operation. A changed mode cannot mutate an existing request. Status endpoints perform one-shot validation/reconciliation, not periodic polling. Firebase participant listeners deliver normal state changes; FCM is a hint for background/cold-start handling.

The push envelope is `type=directstream.incoming`, `protocolVersion=1` with an opaque request identifier. Pending hints have a TTL bounded by request expiry; terminal hints allow suspended receivers to validate and dismiss alerts. Names and authorization come from validated server state, never payload trust.

## TURN

The direct-only default does not guarantee connectivity through restrictive NATs/firewalls. To enable the built-in Cloudflare TURN credential adapter:

1. Create a TURN key in the customer's Cloudflare account and review current [Realtime pricing](https://developers.cloudflare.com/realtime/sfu/platform/pricing/).
2. Set `TURN_KEY_ID` and `TURN_ENABLED=true` in the intended Worker's configuration.
3. Store the private credential with `npx wrangler secret put TURN_API_TOKEN`.
4. Deploy explicitly, verify SDK relay readiness and test an approved call with host `forceRelay=true`.
5. Return `forceRelay=false` for ordinary ICE routing unless relay-only behavior is intentional.

The Worker issues expiring session-authorized credentials, not the master token. Refresh before expiry. Previously issued credentials may remain valid until their expiry after hangup. A custom host can instead supply `DirectStreamConfiguration.relayProvider` implementing the same authorization/refresh contract. No service is enabled merely by depending on the SDK.

## Updates, monitoring and recovery

Before changing a live Worker, record `npx wrangler deployments list` and `npx wrangler versions list`, retain its source/configuration and back up D1 using customer-approved tooling. Backups contain private registration and push data; restrict access. Keep the configured Firebase project, Worker URL, Android application ID and signing identity when updating the existing showcase.

For an ordinary compatible code update with initialized D1 and existing secrets:

```sh
npx wrangler deploy --keep-vars
```

If an environment intentionally needs different variables, update them explicitly; `--keep-vars` preserves remotely configured values rather than applying every changed source variable. Schema changes require their own reviewed schema application. Confirm maintenance is disabled when resuming normal operation. Avoid deploying during active calls when changes affect authorization semantics.

Monitor Worker CPU/errors/latency, D1 query and row counts, transaction conflicts, storage growth, outbox count/oldest age, Firebase concurrent connections/downloads and FCM failures. Limits and costs are in [README → Service plans](../README.md#service-plans-costs-and-capacity). Free-plan recovery settings prioritize bounded execution; they do not promise bulk recovery capacity.

D1 commits and Firebase publication are separate. A failed HTTP response may follow a committed D1 operation; retry the same operation/identifier. Session authorization publication must succeed before approval/end is acknowledged. See [coordination guarantees](COORDINATION.md).

`npx wrangler rollback SAVED_VERSION_ID` restores Worker code, not database contents or external authorization state. Only roll back to code compatible with the current schema/protocol. Never restore stale coordination data over active calls as an automatic rollback. Validate registration, approval, cancellation while locked, both call modes, regeneration, end and revoke after a deployment.
