-- Older compose volumes can have the table without its singleton seed row.
-- The row is a correctness invariant for the cross-pod admission transaction,
-- so repair it idempotently during every database upgrade.
INSERT INTO admission_gate (id, max_concurrent)
VALUES (1, 4)
ON CONFLICT (id) DO NOTHING;
