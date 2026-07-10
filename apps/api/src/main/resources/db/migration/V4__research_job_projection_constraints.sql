ALTER TABLE research_jobs
    DROP CONSTRAINT ck_research_jobs_current_step;

ALTER TABLE research_jobs
    ADD CONSTRAINT ck_research_jobs_current_step CHECK (
        current_step IS NULL OR current_step IN (
            'RESOLVE_SECURITY',
            'FETCH_MARKET_DATA',
            'FETCH_FUNDAMENTALS',
            'FETCH_FILINGS',
            'FETCH_MACRO_DATA',
            'VALIDATE_DATA',
            'RUN_QUANT_ANALYSIS',
            'ANALYZE_FUNDAMENTALS',
            'BUILD_EVIDENCE',
            'GENERATE_REPORT',
            'VALIDATE_REPORT'
        )
    ),
    -- QUEUED stores the checkpoint immediately before its first runnable step. The V3
    -- state guard permits projection only to this stored step's canonical public floor.
    -- An active stage owns the progress interval up to the next stage's floor.
    ADD CONSTRAINT ck_research_jobs_lifecycle_projection CHECK (COALESCE((
        (status = 'CREATED' AND current_step IS NULL AND progress = 0)
        OR (status = 'QUEUED' AND (
            (current_step = 'RESOLVE_SECURITY' AND progress = 0)
            OR (current_step = 'FETCH_MARKET_DATA' AND progress = 14)
            OR (current_step = 'FETCH_FUNDAMENTALS' AND progress = 24)
            OR (current_step = 'FETCH_FILINGS' AND progress = 34)
            OR (current_step = 'FETCH_MACRO_DATA' AND progress = 44)
            OR (current_step = 'VALIDATE_DATA' AND progress = 54)
            OR (current_step = 'RUN_QUANT_ANALYSIS' AND progress = 64)
            OR (current_step = 'ANALYZE_FUNDAMENTALS' AND progress = 74)
            OR (current_step = 'BUILD_EVIDENCE' AND progress = 81)
            OR (current_step = 'GENERATE_REPORT' AND progress = 89)
            OR (current_step = 'VALIDATE_REPORT' AND progress = 95)
        ))
        OR (status = 'RESOLVING_SECURITY'
            AND current_step = 'RESOLVE_SECURITY' AND progress BETWEEN 5 AND 14)
        OR (status = 'FETCHING_MARKET_DATA'
            AND current_step = 'FETCH_MARKET_DATA' AND progress BETWEEN 15 AND 24)
        OR (status = 'FETCHING_FUNDAMENTALS'
            AND current_step = 'FETCH_FUNDAMENTALS' AND progress BETWEEN 25 AND 34)
        OR (status = 'FETCHING_FILINGS'
            AND current_step = 'FETCH_FILINGS' AND progress BETWEEN 35 AND 44)
        OR (status = 'FETCHING_MACRO_DATA'
            AND current_step = 'FETCH_MACRO_DATA' AND progress BETWEEN 45 AND 54)
        OR (status = 'VALIDATING_DATA'
            AND current_step = 'VALIDATE_DATA' AND progress BETWEEN 55 AND 64)
        OR (status = 'RUNNING_QUANT_ANALYSIS'
            AND current_step = 'RUN_QUANT_ANALYSIS' AND progress BETWEEN 65 AND 74)
        OR (status = 'ANALYZING_FUNDAMENTALS'
            AND current_step = 'ANALYZE_FUNDAMENTALS' AND progress BETWEEN 75 AND 81)
        OR (status = 'BUILDING_EVIDENCE'
            AND current_step = 'BUILD_EVIDENCE' AND progress BETWEEN 82 AND 89)
        OR (status = 'GENERATING_REPORT'
            AND current_step = 'GENERATE_REPORT' AND progress BETWEEN 90 AND 95)
        OR (status = 'VALIDATING_REPORT'
            AND current_step = 'VALIDATE_REPORT' AND progress BETWEEN 96 AND 99)
        OR (status IN ('COMPLETED', 'PARTIALLY_COMPLETED')
            AND current_step IS NULL AND progress = 100)
        OR (status IN ('FAILED', 'CANCELLED')
            AND current_step IS NULL AND progress < 100)
    ), false));

COMMENT ON CONSTRAINT ck_research_jobs_lifecycle_projection ON research_jobs IS
    'Keeps public lifecycle status, current durable step, and progress projection coherent.';
