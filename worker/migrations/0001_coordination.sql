-- Private authoritative records. JSON preserves the existing v1 domain/wire models.
CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  data TEXT,
  version INTEGER NOT NULL,
  updated INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE codes (
  id TEXT PRIMARY KEY,
  data TEXT NOT NULL,
  version INTEGER NOT NULL,
  updated INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE requests (
  id TEXT PRIMARY KEY,
  data TEXT,
  version INTEGER NOT NULL,
  updated INTEGER NOT NULL DEFAULT 0,
  sender TEXT GENERATED ALWAYS AS (json_extract(data, '$.senderUid')) VIRTUAL,
  receiver TEXT GENERATED ALWAYS AS (json_extract(data, '$.receiverUid')) VIRTUAL,
  status TEXT GENERATED ALWAYS AS (json_extract(data, '$.status')) VIRTUAL,
  expires INTEGER GENERATED ALWAYS AS (json_extract(data, '$.expiresAt')) VIRTUAL
);
CREATE INDEX requests_sender ON requests(sender, expires);
CREATE INDEX requests_receiver ON requests(receiver, expires);
CREATE INDEX requests_expiry ON requests(status, expires);
CREATE INDEX requests_retention ON requests(expires);
CREATE INDEX requests_tombstones ON requests(updated) WHERE data IS NULL;
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  data TEXT,
  version INTEGER NOT NULL,
  updated INTEGER NOT NULL DEFAULT 0,
  host TEXT GENERATED ALWAYS AS (json_extract(data, '$.hostUid')) VIRTUAL,
  guest TEXT GENERATED ALWAYS AS (json_extract(data, '$.guestUid')) VIRTUAL,
  status TEXT GENERATED ALWAYS AS (json_extract(data, '$.status')) VIRTUAL,
  expires INTEGER GENERATED ALWAYS AS (json_extract(data, '$.expiresAt')) VIRTUAL
);
CREATE INDEX sessions_host ON sessions(host, expires);
CREATE INDEX sessions_guest ON sessions(guest, expires);
CREATE INDEX sessions_expiry ON sessions(status, expires);
CREATE INDEX sessions_retention ON sessions(expires);
CREATE INDEX sessions_tombstones ON sessions(updated) WHERE data IS NULL;
-- Constraint failures abort the whole D1 batch, including reservations and delivery jobs.
CREATE TABLE mutation_guards (
  id TEXT PRIMARY KEY,
  valid INTEGER NOT NULL CHECK (valid = 1)
);
CREATE TABLE participant_versions (
  id TEXT PRIMARY KEY,
  version INTEGER NOT NULL
);
CREATE TABLE outbox (
  kind TEXT NOT NULL CHECK (kind IN ('participant', 'session', 'push')),
  id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  available INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (kind, id)
);
CREATE INDEX outbox_due ON outbox(kind, available);
CREATE TABLE service_metadata (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
