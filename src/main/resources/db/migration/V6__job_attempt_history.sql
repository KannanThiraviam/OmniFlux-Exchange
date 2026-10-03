-- Keep every claimed attempt after the parent job is requeued or retried.
-- This is the durable evidence used by the operator view and orphan cleanup.
CREATE TABLE IF NOT EXISTS transfer_job_attempts (
  id               BIGSERIAL PRIMARY KEY,
  job_id           UUID NOT NULL REFERENCES transfer_jobs(id) ON DELETE CASCADE,
  attempt_no       INT NOT NULL CHECK (attempt_no > 0),
  claim_token      UUID NOT NULL,
  worker_id        TEXT NOT NULL,
  status           TEXT NOT NULL CHECK (status IN
                    ('IN_PROGRESS', 'COMPLETED', 'REQUEUED', 'FAILED', 'CANCELLED', 'LEASE_LOST')),
  started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at      TIMESTAMPTZ,
  object_key       TEXT,
  row_count        BIGINT NOT NULL DEFAULT 0,
  byte_count       BIGINT NOT NULL DEFAULT 0,
  content_sha256   TEXT,
  timing_json      TEXT,
  error_code       TEXT,
  error_message    TEXT,
  UNIQUE (job_id, attempt_no)
);

CREATE INDEX IF NOT EXISTS ix_job_attempts_job ON transfer_job_attempts(job_id, attempt_no);
