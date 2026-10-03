-- =============================================================================
-- OmniFlux-Exchange — V1 schema
-- =============================================================================
-- Runs at Postgres FIRST BOOT via /docker-entrypoint-initdb.d, before PostgREST
-- connects and caches the schema. Flyway applies the byte-identical copy at
-- src/main/resources/db/migration/V1__schema.sql to an EMPTY database, and
-- BASELINES at version 1 on a database this file already seeded. Every
-- statement is IF NOT EXISTS, so a re-run is a no-op on either path.
-- =============================================================================

-- One row, locked FOR UPDATE to serialize admission decisions across pods.
-- FOR UPDATE SKIP LOCKED protects the candidate ROW, never a count(*) predicate:
-- without this gate three pods can each read count=3, each pass "< 4", and each
-- claim a different row, giving six active jobs against a limit of four.
CREATE TABLE IF NOT EXISTS admission_gate (
  id             INT PRIMARY KEY CHECK (id = 1),
  max_concurrent INT NOT NULL
);
INSERT INTO admission_gate (id, max_concurrent) VALUES (1, 4)
  ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS transfer_jobs (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  direction             TEXT NOT NULL CHECK (direction = 'EXPORT'),  -- v2 widens this
  status                TEXT NOT NULL,

  -- Authorization snapshot captured at SUBMIT. The worker runs minutes later on
  -- another pod, so a user token cannot be its credential.
  requested_by          TEXT NOT NULL,
  issuer                TEXT NOT NULL,   -- PrincipalKey = issuer + subject + tenant;
  tenant                TEXT NOT NULL,   --   NOT NULL, or uniqueness silently fails
  roles                 TEXT[],
  authz_context_version TEXT,
  client_ip             TEXT,
  idempotency_key       TEXT,

  relation_name         TEXT NOT NULL,
  columns_json          TEXT,            -- RESOLVED and ORDER-PRESERVED
  filters_json          TEXT,
  format                TEXT NOT NULL,
  csv_mode              TEXT,
  csv_dialect_version   INT,

  object_key            TEXT,            -- never a URL: presigned URLs expire
  row_count             BIGINT NOT NULL DEFAULT 0,
  byte_count            BIGINT NOT NULL DEFAULT 0,
  content_sha256        TEXT,            -- whole-object; a multipart ETag is not one

  last_key              BIGINT,          -- progress display only; retries restart at row 1
  high_water_key        BIGINT,
  attempt_count         INT NOT NULL DEFAULT 0,
  worker_id             TEXT,
  claim_token           UUID,            -- fencing token
  lease_until           TIMESTAMPTZ,
  cancel_requested      BOOLEAN NOT NULL DEFAULT FALSE,

  request_fingerprint   TEXT,
  generated_at          TIMESTAMPTZ,     -- IMMUTABLE: when the BYTES were produced
  cache_hit_of          UUID,            -- NULL == this job generated them

  presign_count         INT NOT NULL DEFAULT 0,   -- ISSUANCE, not downloads
  last_presign_at       TIMESTAMPTZ,
  last_presign_ip       TEXT,

  error_class           TEXT CHECK (error_class IS NULL OR error_class IN ('TRANSIENT', 'DETERMINISTIC')),
  error_code            TEXT,
  error_message         TEXT,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at           TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_jobs_claim   ON transfer_jobs(status, created_at);
CREATE INDEX IF NOT EXISTS ix_jobs_lease   ON transfer_jobs(status, lease_until);
CREATE INDEX IF NOT EXISTS ix_jobs_cache   ON transfer_jobs(request_fingerprint, status, generated_at);
CREATE INDEX IF NOT EXISTS ix_jobs_owner   ON transfer_jobs(issuer, tenant, requested_by, created_at DESC);

-- Scoped by the full PrincipalKey: two users must not collide on the same
-- client-generated key.
CREATE UNIQUE INDEX IF NOT EXISTS ux_jobs_idem
  ON transfer_jobs(direction, issuer, tenant, requested_by, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

-- -----------------------------------------------------------------------------
-- Demo relations. Two deliberately different shapes, so the engine is visibly
-- not tuned to one. Both use INTEGER PRIMARY KEY: that is the v1 key contract,
-- and it is what makes the (last_key, high_water] interval finite so a keyset
-- scan terminates rigorously rather than usually.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS mock_customers (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  full_name         TEXT,
  email_address     TEXT,
  country           TEXT,
  membership_status TEXT
);

CREATE TABLE IF NOT EXISTS mock_orders (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_uuid    TEXT,
  product_sku   TEXT,
  price         NUMERIC(12,2),
  purchase_date DATE
);
