# Targeted coordination for DirectStream SDK

The default provider uses the v1 Worker API. It uses a customer-owned Cloudflare D1 database for private coordination. Firebase Authentication, participant event listeners, SDP/ICE, rules, FCM and the configured Worker URL remain in place. Media still travels over WebRTC. No service plan is upgraded by a build or script.

## Authority and transactions

D1 stores indexed device, code, request and session records, with monotonically increasing per-record versions. Code IDs are unique; retired codes remain reserved. SDK request identifiers are fresh UUIDs; integrations must never recycle them after retention cleanup. Both participants hold a pending reservation, and approval converts those reservations to one active session. Unrelated calls never share a coordination document or optimistic version.

The Worker loads only the records referenced by the operation and participant slots. A transactional D1 batch validates the versions read, writes changed records and records durable event jobs. A version conflict or code collision rolls back the entire batch and retries with a fresh snapshot. An unexpired, ordinary status read changes nothing. Lazy expiry only touches reservations encountered by that operation; maintenance uses expiry indexes.

The existing pure admission model remains the reference implementation; it operates on this small scoped snapshot rather than every installation. Do not add directory-wide scans or unguarded indexed predicates to an admission operation. Reservations must always change a participant record so competing operations conflict on that record.

Display names belong to the private device registration. Updating registration metadata preserves code ownership and reservations; requests snapshot participant names at creation. Revocation deletes the active device registration and retires its code, while terminal request/session snapshots follow the normal retention policy. The host's authenticated user/device directory is outside this coordination store's display-name contract.

## Realtime projections and delivery

D1 is authoritative. Firebase `/control/events/{uid}` contains only that participant's request/session views and incoming inbox. `/control/sessions/{roomId}` is the server-written authorization projection used by the existing signaling rules. Each projection carries a private-to-participants `_revision` field; existing Android parsers ignore this extra field. Firebase conditional writes apply only newer revisions. They no longer read or rewrite `/control`.

Admission and its outbox jobs commit together. Delivery reads current authoritative records, so an old job cannot recreate an approved session that has ended. Session authorization/metadata publication must succeed before approval or end is acknowledged. Approval retries use the same room. Other event/push failures retain their jobs with bounded backoff. FCM is an at-least-once hint, not authorization; cancelled/terminal requests also send a hint so a receiver whose process was suspended can revalidate and dismiss its alert.

D1 and Firebase do not share a transaction. If Firebase is unavailable, a D1 operation can have committed even though the HTTP response fails. Retry the same request identifier. Revocation in Firebase is pending until publication succeeds; existing authorization also has its expiry deadline. Never claim simultaneous cross-service revocation or issue relay credentials without checking authoritative session state.

Participant projection generation reads indexed records for that user within a 24-hour history window, not every user's records. It checks the participant revision before/after loading to avoid publishing a mixed snapshot. There is still per-user history work and one D1 primary write path; this is not unlimited horizontal write scaling.

## Maintenance and limits

A minute cron retries a bounded number of outbox jobs, expires indexed reservations, and prunes terminal request/session payloads after the existing retention period (one day after their expiry). Version tombstones protect delayed deliveries for seven days; retired device codes are permanent. Ended room media/signaling payloads are deleted after authorization is revoked. Pending requests and active calls are never removed as retention cleanup.

Normal request processing attempts delivery immediately. The conservative Free-plan maintenance batch is recovery capacity, not a promised bulk-delivery SLA. Monitor outbox count/oldest age, delivery failures, transaction conflicts, D1 queries/rows/latency and Worker CPU. The Worker stops before its configured `D1_QUERY_BUDGET` (default 50) is exceeded; a transaction batch is reserved as a whole. Remaining jobs stay durable. Tune `MAINTENANCE_JOB_LIMIT` (default 2), `MAINTENANCE_MUTATION_LIMIT` (default 1) and the query budget for the customer's actual plan and workload before a large launch. Changing these settings never upgrades a service plan. SDK clients continue using events and one-shot reconciliation; no API polling is added.

Local tests cover real SQLite transactions, contention, rollback, retries, privacy and a 10,000-device directory lookup. These are correctness/work-scaling tests, not a production throughput benchmark. Load-test the deployed customer services before asserting concurrent-call capacity.
