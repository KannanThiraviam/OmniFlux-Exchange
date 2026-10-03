-- Older demo rows used CLIENT as an error class and CANCELLED as an error code.
-- Those values predate the current enums. Preserve the job lifecycle and
-- message, but clear the unrecognised diagnostic fields before adding the
-- invariant used by new databases.
UPDATE transfer_jobs
   SET error_class = CASE
         WHEN error_class IN ('TRANSIENT', 'DETERMINISTIC') THEN error_class
         ELSE NULL
       END,
       error_code = CASE
         WHEN error_code IN (
           'UNKNOWN_RELATION', 'UNKNOWN_COLUMN', 'DUPLICATE_COLUMN',
           'RELATION_NOT_ALLOWED', 'UNSUPPORTED_PRIMARY_KEY',
           'UNSUPPORTED_COLUMN_TYPE', 'TOO_MANY_COLUMNS', 'TOO_MANY_IN_VALUES',
           'FIELD_TOO_LARGE', 'ROW_TOO_LARGE', 'EXPORT_TOO_LARGE',
           'XLSX_ROW_LIMIT', 'CHARACTER_NOT_REPRESENTABLE',
           'IDEMPOTENCY_KEY_CONFLICT', 'QUEUE_FULL', 'JOB_NOT_FOUND',
           'PRINCIPAL_UNRESOLVED', 'UPSTREAM_CLIENT_ERROR', 'QUERY_TIMEOUT',
           'UPSTREAM_UNAVAILABLE', 'STORAGE_UNAVAILABLE', 'LEASE_LOST'
         ) THEN error_code
         ELSE NULL
       END
 WHERE error_class IS NOT NULL
    OR error_code IS NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM pg_constraint
     WHERE conname = 'transfer_jobs_error_class_check'
       AND conrelid = 'transfer_jobs'::regclass
  ) THEN
    ALTER TABLE transfer_jobs
      ADD CONSTRAINT transfer_jobs_error_class_check
      CHECK (error_class IS NULL OR error_class IN ('TRANSIENT', 'DETERMINISTIC'));
  END IF;
END $$;
