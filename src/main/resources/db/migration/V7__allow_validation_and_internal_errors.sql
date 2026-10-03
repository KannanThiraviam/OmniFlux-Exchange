-- Persist the complete application ErrorCode enum as a database invariant.
-- V7 is deliberately forward-only: V1 and V4 are retained for existing users.
ALTER TABLE transfer_jobs
  DROP CONSTRAINT IF EXISTS transfer_jobs_error_code_check;

ALTER TABLE transfer_jobs
  ADD CONSTRAINT transfer_jobs_error_code_check
  CHECK (error_code IS NULL OR error_code IN (
    'UNKNOWN_RELATION', 'UNKNOWN_COLUMN', 'DUPLICATE_COLUMN',
    'RELATION_NOT_ALLOWED', 'UNSUPPORTED_PRIMARY_KEY',
    'UNSUPPORTED_COLUMN_TYPE', 'TOO_MANY_COLUMNS', 'TOO_MANY_IN_VALUES',
    'FIELD_TOO_LARGE', 'ROW_TOO_LARGE', 'EXPORT_TOO_LARGE',
    'XLSX_ROW_LIMIT', 'CHARACTER_NOT_REPRESENTABLE',
    'IDEMPOTENCY_KEY_CONFLICT', 'QUEUE_FULL', 'CANCELLED',
    'JOB_NOT_FOUND', 'PRINCIPAL_UNRESOLVED', 'VALIDATION_ERROR',
    'INTERNAL_ERROR', 'UPSTREAM_CLIENT_ERROR', 'QUERY_TIMEOUT',
    'UPSTREAM_UNAVAILABLE', 'STORAGE_UNAVAILABLE', 'LEASE_LOST'
  ));
