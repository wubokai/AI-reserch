ALTER TABLE research_jobs
    ADD COLUMN legal_hold boolean NOT NULL DEFAULT false;

CREATE INDEX ix_research_jobs_retention_candidates
    ON research_jobs (created_at, id)
    WHERE deleted_at IS NULL
      AND NOT legal_hold
      AND status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED');

COMMENT ON COLUMN research_jobs.legal_hold IS
    'Prevents automated R3 retention archival until an authorized hold is released.';
