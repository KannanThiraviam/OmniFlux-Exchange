-- Persist the final attempt timing so the operator view works after a worker
-- finishes on another pod or the original pod is restarted.
ALTER TABLE transfer_jobs
  ADD COLUMN IF NOT EXISTS timing_json TEXT;
