-- Phase 3 immutable research artifacts and Mock-first lineage.
-- PostgreSQL remains the authority for every source, calculation, Evidence item,
-- Claim, report version, export and run-level publication manifest.

CREATE TABLE source_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(128) NOT NULL,
    source_type varchar(40) NOT NULL,
    external_source_id varchar(255),
    source_url text,
    request_fingerprint varchar(64),
    published_at timestamptz,
    retrieved_at timestamptz NOT NULL,
    effective_date date,
    raw_data_hash varchar(64) NOT NULL,
    normalized_data_hash varchar(64) NOT NULL,
    payload_json jsonb NOT NULL,
    is_primary_source boolean NOT NULL,
    freshness_status varchar(16) NOT NULL,
    is_demo_data boolean NOT NULL,
    schema_version varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_source_snapshots_content UNIQUE (
        provider, raw_data_hash, schema_version
    ),
    CONSTRAINT ux_source_snapshots_id_demo UNIQUE (id, is_demo_data),
    CONSTRAINT ck_source_snapshots_provider CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_source_snapshots_source_type CHECK (source_type IN (
        'EXCHANGE', 'MARKET_DATA_PROVIDER', 'COMPANY_FILING', 'SEC_FILING',
        'GOVERNMENT_DATA', 'COMPANY_WEBSITE', 'NEWS',
        'INTERNAL_CALCULATION', 'MOCK', 'OTHER'
    )),
    CONSTRAINT ck_source_snapshots_external_id CHECK (
        external_source_id IS NULL OR btrim(external_source_id) <> ''
    ),
    CONSTRAINT ck_source_snapshots_request_fingerprint CHECK (
        request_fingerprint IS NULL
        OR request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_source_snapshots_raw_hash CHECK (
        raw_data_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_source_snapshots_normalized_hash CHECK (
        normalized_data_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_source_snapshots_payload CHECK (
        jsonb_typeof(payload_json) = 'object'
    ),
    CONSTRAINT ck_source_snapshots_freshness CHECK (
        freshness_status IN ('FRESH', 'STALE', 'VERY_STALE', 'UNKNOWN')
    ),
    CONSTRAINT ck_source_snapshots_schema_version CHECK (
        btrim(schema_version) <> ''
    ),
    CONSTRAINT ck_source_snapshots_times CHECK (
        published_at IS NULL OR retrieved_at >= published_at
    )
);

CREATE TABLE research_source_links (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    source_snapshot_id uuid NOT NULL REFERENCES source_snapshots (id) ON DELETE RESTRICT,
    step_attempt_id uuid REFERENCES step_attempts (id) ON DELETE RESTRICT,
    purpose varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_research_source_links UNIQUE (
        research_job_id, source_snapshot_id, purpose
    ),
    CONSTRAINT ck_research_source_links_purpose CHECK (
        purpose IN (
            'SECURITY_PROFILE', 'MARKET_DATA', 'BENCHMARK_DATA', 'FUNDAMENTALS',
            'FILING', 'MACRO', 'REPORT_CONTEXT', 'OTHER'
        )
    )
);

CREATE TABLE quant_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    public_id varchar(72) NOT NULL,
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    metric_name varchar(128) NOT NULL,
    metric_value numeric(38, 12),
    unit varchar(32) NOT NULL,
    result_status varchar(24) NOT NULL,
    result_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    calculation_version varchar(64) NOT NULL,
    input_hash varchar(64) NOT NULL,
    input_data_start date,
    input_data_end date,
    sample_size integer NOT NULL,
    warnings_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_demo_data boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_quant_results_public_id UNIQUE (public_id),
    CONSTRAINT ux_quant_results_identity UNIQUE (
        research_job_id, metric_name, calculation_version, input_hash
    ),
    CONSTRAINT ux_quant_results_id_job UNIQUE (id, research_job_id),
    CONSTRAINT ck_quant_results_public_id CHECK (
        public_id ~ '^calc_[A-Za-z0-9_-]{1,64}$'
    ),
    CONSTRAINT ck_quant_results_metric_name CHECK (btrim(metric_name) <> ''),
    CONSTRAINT ck_quant_results_unit CHECK (btrim(unit) <> ''),
    CONSTRAINT ck_quant_results_status CHECK (result_status IN (
        'AVAILABLE', 'NOT_AVAILABLE', 'NOT_APPLICABLE', 'INVALID_INPUT'
    )),
    CONSTRAINT ck_quant_results_value CHECK (
        (result_status = 'AVAILABLE' AND metric_value IS NOT NULL)
        OR (result_status <> 'AVAILABLE' AND metric_value IS NULL)
    ),
    CONSTRAINT ck_quant_results_result_json CHECK (
        jsonb_typeof(result_json) = 'object'
    ),
    CONSTRAINT ck_quant_results_version CHECK (
        btrim(calculation_version) <> ''
    ),
    CONSTRAINT ck_quant_results_input_hash CHECK (
        input_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_quant_results_period CHECK (
        input_data_start IS NULL
        OR input_data_end IS NULL
        OR input_data_end >= input_data_start
    ),
    CONSTRAINT ck_quant_results_sample_size CHECK (sample_size >= 0),
    CONSTRAINT ck_quant_results_warnings CHECK (
        jsonb_typeof(warnings_json) = 'array'
    )
);

CREATE TABLE evidence_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    public_id varchar(72) NOT NULL,
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    source_snapshot_id uuid REFERENCES source_snapshots (id) ON DELETE RESTRICT,
    quant_result_id uuid,
    evidence_type varchar(32) NOT NULL,
    title varchar(300) NOT NULL,
    summary text NOT NULL,
    value_json jsonb NOT NULL,
    unit varchar(32),
    quality_score numeric(5, 4) NOT NULL,
    is_demo_data boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_evidence_items_public_id UNIQUE (public_id),
    CONSTRAINT ux_evidence_items_id_job UNIQUE (id, research_job_id),
    CONSTRAINT fk_evidence_items_quant_job FOREIGN KEY (
        quant_result_id, research_job_id
    ) REFERENCES quant_results (id, research_job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_evidence_items_public_id CHECK (
        public_id ~ '^ev_[A-Za-z0-9_-]{1,64}$'
    ),
    CONSTRAINT ck_evidence_items_source CHECK (
        source_snapshot_id IS NOT NULL OR quant_result_id IS NOT NULL
    ),
    CONSTRAINT ck_evidence_items_type CHECK (evidence_type IN (
        'MARKET_PRICE', 'FINANCIAL_METRIC', 'SEC_FILING',
        'MACRO_OBSERVATION', 'QUANT_RESULT', 'COMPANY_PROFILE',
        'NEWS_ARTICLE', 'OTHER'
    )),
    CONSTRAINT ck_evidence_items_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_evidence_items_summary CHECK (btrim(summary) <> ''),
    CONSTRAINT ck_evidence_items_value CHECK (
        jsonb_typeof(value_json) IN ('object', 'array', 'string', 'number', 'boolean')
    ),
    CONSTRAINT ck_evidence_items_unit CHECK (
        unit IS NULL OR btrim(unit) <> ''
    ),
    CONSTRAINT ck_evidence_items_quality CHECK (
        quality_score BETWEEN 0 AND 1
    )
);

CREATE TABLE llm_calls (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    step_attempt_id uuid REFERENCES step_attempts (id) ON DELETE RESTRICT,
    provider varchar(64) NOT NULL,
    model_name varchar(128) NOT NULL,
    prompt_version varchar(64) NOT NULL,
    schema_version varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response_hash varchar(64),
    input_tokens integer NOT NULL DEFAULT 0,
    output_tokens integer NOT NULL DEFAULT 0,
    cached_tokens integer NOT NULL DEFAULT 0,
    estimated_cost_usd numeric(18, 8),
    latency_ms bigint NOT NULL,
    status varchar(24) NOT NULL,
    error_code varchar(128),
    is_mock boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_llm_calls_id_job UNIQUE (id, research_job_id),
    CONSTRAINT ck_llm_calls_provider CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_llm_calls_model CHECK (btrim(model_name) <> ''),
    CONSTRAINT ck_llm_calls_prompt_version CHECK (btrim(prompt_version) <> ''),
    CONSTRAINT ck_llm_calls_schema_version CHECK (btrim(schema_version) <> ''),
    CONSTRAINT ck_llm_calls_request_hash CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_llm_calls_response_hash CHECK (
        response_hash IS NULL OR response_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_llm_calls_tokens CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND cached_tokens >= 0
    ),
    CONSTRAINT ck_llm_calls_cost CHECK (
        estimated_cost_usd IS NULL OR estimated_cost_usd >= 0
    ),
    CONSTRAINT ck_llm_calls_latency CHECK (latency_ms >= 0),
    CONSTRAINT ck_llm_calls_status CHECK (status IN (
        'SUCCEEDED', 'FAILED', 'CACHE_HIT', 'REFUSED', 'INCOMPLETE'
    )),
    CONSTRAINT ck_llm_calls_error CHECK (
        (status IN ('FAILED', 'REFUSED', 'INCOMPLETE')
            AND error_code IS NOT NULL AND btrim(error_code) <> '')
        OR (status IN ('SUCCEEDED', 'CACHE_HIT') AND error_code IS NULL)
    )
);

CREATE UNIQUE INDEX ux_llm_calls_attempt_request
    ON llm_calls (step_attempt_id, request_hash)
    WHERE step_attempt_id IS NOT NULL;

CREATE TABLE report_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    version integer NOT NULL,
    report_schema_version varchar(64) NOT NULL,
    report_json jsonb NOT NULL,
    report_markdown text NOT NULL,
    validation_status varchar(32) NOT NULL,
    content_hash varchar(64) NOT NULL,
    data_mode varchar(16) NOT NULL,
    data_as_of_date date NOT NULL,
    generation_llm_call_id uuid,
    generated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_report_versions_job_version UNIQUE (research_job_id, version),
    CONSTRAINT ux_report_versions_job_content UNIQUE (research_job_id, content_hash),
    CONSTRAINT ux_report_versions_id_job UNIQUE (id, research_job_id),
    CONSTRAINT fk_report_versions_llm_job FOREIGN KEY (
        generation_llm_call_id, research_job_id
    ) REFERENCES llm_calls (id, research_job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_report_versions_version CHECK (version > 0),
    CONSTRAINT ck_report_versions_schema_version CHECK (
        report_schema_version = 'research_report_v1'
    ),
    CONSTRAINT ck_report_versions_json CHECK (
        jsonb_typeof(report_json) = 'object'
        AND report_json ->> 'schemaVersion' = 'research_report_v1'
        AND report_json ->> 'dataMode' = data_mode
    ),
    CONSTRAINT ck_report_versions_markdown CHECK (btrim(report_markdown) <> ''),
    CONSTRAINT ck_report_versions_validation CHECK (validation_status IN (
        'PENDING', 'PASSED', 'PASSED_WITH_WARNINGS', 'FAILED'
    )),
    CONSTRAINT ck_report_versions_content_hash CHECK (
        content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_report_versions_data_mode CHECK (
        data_mode IN ('REAL', 'MOCK', 'MIXED_TEST')
    ),
    CONSTRAINT ck_report_versions_generated_time CHECK (
        generated_at <= created_at
    )
);

CREATE TABLE claims (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    public_id varchar(72) NOT NULL,
    report_version_id uuid NOT NULL,
    research_job_id uuid NOT NULL,
    claim_key varchar(128) NOT NULL,
    claim_type varchar(24) NOT NULL,
    statement text NOT NULL,
    materiality varchar(16) NOT NULL,
    confidence numeric(5, 4) NOT NULL,
    calculation_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    numeric_references_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    limitations_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    validation_status varchar(32) NOT NULL,
    validation_notes_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_claims_public_id UNIQUE (public_id),
    CONSTRAINT ux_claims_report_key UNIQUE (report_version_id, claim_key),
    CONSTRAINT ux_claims_id_job UNIQUE (id, research_job_id),
    CONSTRAINT fk_claims_report_job FOREIGN KEY (
        report_version_id, research_job_id
    ) REFERENCES report_versions (id, research_job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_claims_public_id CHECK (
        public_id ~ '^cl_[A-Za-z0-9_-]{1,64}$'
    ),
    CONSTRAINT ck_claims_key CHECK (btrim(claim_key) <> ''),
    CONSTRAINT ck_claims_type CHECK (
        claim_type IN ('FACT', 'CALCULATION', 'INFERENCE', 'OPINION')
    ),
    CONSTRAINT ck_claims_statement CHECK (btrim(statement) <> ''),
    CONSTRAINT ck_claims_materiality CHECK (
        materiality IN ('MATERIAL', 'SUPPORTING')
    ),
    CONSTRAINT ck_claims_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_claims_calculation_ids CHECK (
        jsonb_typeof(calculation_ids_json) = 'array'
    ),
    CONSTRAINT ck_claims_numeric_references CHECK (
        jsonb_typeof(numeric_references_json) = 'array'
    ),
    CONSTRAINT ck_claims_limitations CHECK (
        jsonb_typeof(limitations_json) = 'array'
    ),
    CONSTRAINT ck_claims_validation CHECK (validation_status IN (
        'PENDING', 'PASSED', 'PASSED_WITH_WARNINGS', 'FAILED'
    )),
    CONSTRAINT ck_claims_validation_notes CHECK (
        jsonb_typeof(validation_notes_json) = 'array'
    )
);

CREATE TABLE claim_evidence_links (
    claim_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    research_job_id uuid NOT NULL,
    support_role varchar(16) NOT NULL,
    relevance_score numeric(5, 4) NOT NULL,
    citation_locator varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (claim_id, evidence_id),
    CONSTRAINT fk_claim_evidence_claim_job FOREIGN KEY (
        claim_id, research_job_id
    ) REFERENCES claims (id, research_job_id) ON DELETE RESTRICT,
    CONSTRAINT fk_claim_evidence_evidence_job FOREIGN KEY (
        evidence_id, research_job_id
    ) REFERENCES evidence_items (id, research_job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_claim_evidence_role CHECK (
        support_role IN ('PRIMARY', 'SUPPORTING', 'CONTRADICTING', 'CONTEXT')
    ),
    CONSTRAINT ck_claim_evidence_relevance CHECK (
        relevance_score BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_claim_evidence_locator CHECK (btrim(citation_locator) <> '')
);

CREATE TABLE report_exports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_version_id uuid NOT NULL,
    research_job_id uuid NOT NULL,
    format varchar(16) NOT NULL,
    template_version varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    content_bytes bytea,
    storage_uri text,
    content_hash varchar(64),
    size_bytes bigint,
    error_code varchar(128),
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_report_exports_report_job FOREIGN KEY (
        report_version_id, research_job_id
    ) REFERENCES report_versions (id, research_job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_report_exports_format CHECK (
        format IN ('PDF', 'MARKDOWN', 'HTML')
    ),
    CONSTRAINT ck_report_exports_template_version CHECK (
        btrim(template_version) <> ''
    ),
    CONSTRAINT ck_report_exports_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_report_exports_hash CHECK (
        content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_report_exports_size CHECK (
        size_bytes IS NULL OR size_bytes >= 0
    ),
    CONSTRAINT ck_report_exports_result CHECK (
        (status IN ('PENDING', 'RUNNING')
            AND content_bytes IS NULL AND storage_uri IS NULL
            AND content_hash IS NULL AND size_bytes IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED'
            AND (content_bytes IS NOT NULL OR storage_uri IS NOT NULL)
            AND content_hash IS NOT NULL AND size_bytes IS NOT NULL
            AND error_code IS NULL)
        OR (status = 'FAILED'
            AND content_bytes IS NULL AND storage_uri IS NULL
            AND content_hash IS NULL AND size_bytes IS NULL
            AND error_code IS NOT NULL AND btrim(error_code) <> '')
    ),
    CONSTRAINT ck_report_exports_expiry CHECK (
        expires_at IS NULL OR expires_at > created_at
    )
);

CREATE UNIQUE INDEX ux_report_exports_success
    ON report_exports (report_version_id, format, template_version, content_hash)
    WHERE status = 'SUCCEEDED';

CREATE TABLE research_run_manifests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    execution_cycle integer NOT NULL,
    report_version_id uuid,
    manifest_json jsonb NOT NULL,
    content_hash varchar(64) NOT NULL,
    completion_policy_version varchar(64) NOT NULL,
    data_mode varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_research_run_manifests_cycle UNIQUE (
        research_job_id, execution_cycle
    ),
    CONSTRAINT ux_research_run_manifests_content UNIQUE (
        research_job_id, content_hash
    ),
    CONSTRAINT fk_research_run_manifest_report_job FOREIGN KEY (
        report_version_id, research_job_id
    ) REFERENCES report_versions (id, research_job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_research_run_manifests_cycle CHECK (execution_cycle > 0),
    CONSTRAINT ck_research_run_manifests_json CHECK (
        jsonb_typeof(manifest_json) = 'object'
    ),
    CONSTRAINT ck_research_run_manifests_hash CHECK (
        content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_research_run_manifests_policy CHECK (
        btrim(completion_policy_version) <> ''
    ),
    CONSTRAINT ck_research_run_manifests_data_mode CHECK (
        data_mode IN ('REAL', 'MOCK', 'MIXED_TEST')
    ),
    CONSTRAINT ck_research_run_manifests_status CHECK (
        status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT ck_research_run_manifests_report CHECK (
        (status = 'PUBLISHED' AND report_version_id IS NOT NULL)
        OR status <> 'PUBLISHED'
    )
);

ALTER TABLE research_jobs
    ADD COLUMN latest_report_version_id uuid,
    ADD CONSTRAINT fk_research_jobs_latest_report FOREIGN KEY (
        latest_report_version_id, id
    ) REFERENCES report_versions (id, research_job_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_research_jobs_success_report CHECK (
        (status IN ('COMPLETED', 'PARTIALLY_COMPLETED')
            AND latest_report_version_id IS NOT NULL)
        OR (status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED'))
    );

-- Phase 2 intentionally failed closed before report publication existed. Keep every
-- existing guard, but make the documented VALIDATING_REPORT -> successful terminal
-- branch reachable now that latest_report_version_id is constrained to a validated
-- same-Research report.
CREATE OR REPLACE FUNCTION app_private.guard_research_job_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_manual_retry boolean := OLD.status IN ('FAILED', 'PARTIALLY_COMPLETED')
        AND NEW.status = 'QUEUED';
    v_queued_checkpoint_projection boolean;
    v_old_rank smallint;
    v_new_rank smallint;
BEGIN
    IF NEW.user_id <> OLD.user_id
       OR NEW.query <> OLD.query
       OR NEW.locale <> OLD.locale
       OR NEW.request_json <> OLD.request_json
       OR NEW.data_mode <> OLD.data_mode
       OR NEW.symbol_input IS DISTINCT FROM OLD.symbol_input
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'immutable research job fields cannot be changed';
    END IF;

    IF OLD.security_id IS NOT NULL
       AND NEW.security_id IS DISTINCT FROM OLD.security_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'a resolved security cannot be replaced';
    END IF;

    IF OLD.deleted_at IS NOT NULL AND (
        NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
        OR NEW.deleted_by IS DISTINCT FROM OLD.deleted_by
        OR NEW.delete_reason IS DISTINCT FROM OLD.delete_reason
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'soft deletion is irreversible';
    END IF;

    v_queued_checkpoint_projection := OLD.status = 'QUEUED'
        AND OLD.current_step = NEW.current_step
        AND (
            (OLD.current_step = 'RESOLVE_SECURITY'
                AND OLD.progress = 0
                AND NEW.status = 'RESOLVING_SECURITY'
                AND NEW.progress = 5)
            OR (OLD.current_step = 'FETCH_MARKET_DATA'
                AND OLD.progress = 14
                AND NEW.status = 'FETCHING_MARKET_DATA'
                AND NEW.progress = 15)
            OR (OLD.current_step = 'FETCH_FUNDAMENTALS'
                AND OLD.progress = 24
                AND NEW.status = 'FETCHING_FUNDAMENTALS'
                AND NEW.progress = 25)
            OR (OLD.current_step = 'FETCH_FILINGS'
                AND OLD.progress = 34
                AND NEW.status = 'FETCHING_FILINGS'
                AND NEW.progress = 35)
            OR (OLD.current_step = 'FETCH_MACRO_DATA'
                AND OLD.progress = 44
                AND NEW.status = 'FETCHING_MACRO_DATA'
                AND NEW.progress = 45)
            OR (OLD.current_step = 'VALIDATE_DATA'
                AND OLD.progress = 54
                AND NEW.status = 'VALIDATING_DATA'
                AND NEW.progress = 55)
            OR (OLD.current_step = 'RUN_QUANT_ANALYSIS'
                AND OLD.progress = 64
                AND NEW.status = 'RUNNING_QUANT_ANALYSIS'
                AND NEW.progress = 65)
            OR (OLD.current_step = 'ANALYZE_FUNDAMENTALS'
                AND OLD.progress = 74
                AND NEW.status = 'ANALYZING_FUNDAMENTALS'
                AND NEW.progress = 75)
            OR (OLD.current_step = 'BUILD_EVIDENCE'
                AND OLD.progress = 81
                AND NEW.status = 'BUILDING_EVIDENCE'
                AND NEW.progress = 82)
            OR (OLD.current_step = 'GENERATE_REPORT'
                AND OLD.progress = 89
                AND NEW.status = 'GENERATING_REPORT'
                AND NEW.progress = 90)
            OR (OLD.current_step = 'VALIDATE_REPORT'
                AND OLD.progress = 95
                AND NEW.status = 'VALIDATING_REPORT'
                AND NEW.progress = 96)
        );

    IF v_manual_retry THEN
        IF NEW.completed_at IS NOT NULL
           OR NEW.started_at IS NOT NULL
           OR NEW.cancellation_requested
           OR NEW.cancellation_requested_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'manual retry must clear completion, start, and cancellation fields';
        END IF;
    ELSE
        IF OLD.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
           AND NEW.status <> OLD.status THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'terminal research status is immutable outside explicit manual retry';
        END IF;

        IF OLD.cancellation_requested AND NOT NEW.cancellation_requested THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'cancellation request is irreversible';
        END IF;

        IF NEW.progress < OLD.progress THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'research progress cannot decrease';
        END IF;

        IF NEW.status <> OLD.status
           AND OLD.status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED') THEN
            v_old_rank := app_private.research_status_rank(OLD.status);
            v_new_rank := app_private.research_status_rank(NEW.status);

            IF NEW.status IN ('COMPLETED', 'PARTIALLY_COMPLETED') THEN
                IF OLD.status <> 'VALIDATING_REPORT' THEN
                    RAISE EXCEPTION USING ERRCODE = '23514',
                        MESSAGE = 'publish terminal states require VALIDATING_REPORT';
                END IF;
            ELSIF NEW.status IN ('FAILED', 'CANCELLED') THEN
                NULL;
            ELSIF v_queued_checkpoint_projection THEN
                NULL;
            ELSIF OLD.status = 'QUEUED' THEN
                RAISE EXCEPTION USING ERRCODE = '23514',
                    MESSAGE = 'queued research must project its stored checkpoint';
            ELSIF v_old_rank IS NULL OR v_new_rank IS NULL OR v_new_rank <> v_old_rank + 1 THEN
                RAISE EXCEPTION USING ERRCODE = '23514',
                    MESSAGE = 'ordinary research status transition must advance exactly one stage';
            END IF;
        END IF;
    END IF;

    IF NEW.status = 'CANCELLED' AND NOT NEW.cancellation_requested THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'CANCELLED requires a committed cancellation request';
    END IF;

    IF NEW.cancellation_requested
       AND NEW.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'a cancellation-first race may only converge to CANCELLED';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE INDEX ix_source_snapshots_lookup
    ON source_snapshots (provider, source_type, effective_date DESC, retrieved_at DESC);

CREATE INDEX ix_research_source_links_job
    ON research_source_links (research_job_id, purpose, created_at DESC);

CREATE INDEX ix_quant_results_job_metric
    ON quant_results (research_job_id, metric_name, created_at DESC);

CREATE INDEX ix_evidence_items_job_type
    ON evidence_items (research_job_id, evidence_type, created_at DESC);

CREATE INDEX ix_report_versions_job_latest
    ON report_versions (research_job_id, version DESC);

CREATE INDEX ix_claim_evidence_links_evidence
    ON claim_evidence_links (evidence_id, claim_id);

CREATE INDEX ix_report_exports_report
    ON report_exports (report_version_id, format, created_at DESC);

CREATE FUNCTION app_private.reject_immutable_research_artifact_mutation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = format('%s is append-only and cannot be updated or deleted', TG_TABLE_NAME);
END;
$function$;

REVOKE ALL ON FUNCTION app_private.reject_immutable_research_artifact_mutation() FROM PUBLIC;

CREATE FUNCTION app_private.guard_research_source_link_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_data_mode varchar;
    v_snapshot_is_demo boolean;
    v_attempt_job_id uuid;
BEGIN
    SELECT r.data_mode, s.is_demo_data
      INTO v_data_mode, v_snapshot_is_demo
      FROM research_jobs AS r
      JOIN source_snapshots AS s ON s.id = NEW.source_snapshot_id
     WHERE r.id = NEW.research_job_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503',
            MESSAGE = 'research job or source snapshot does not exist';
    END IF;

    IF (v_data_mode = 'MOCK' AND NOT v_snapshot_is_demo)
       OR (v_data_mode = 'REAL' AND v_snapshot_is_demo) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'source snapshot data mode does not match research job';
    END IF;

    IF NEW.step_attempt_id IS NOT NULL THEN
        SELECT s.research_job_id INTO v_attempt_job_id
          FROM step_attempts AS a
          JOIN research_steps AS s ON s.id = a.research_step_id
         WHERE a.id = NEW.step_attempt_id;
        IF v_attempt_job_id IS DISTINCT FROM NEW.research_job_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'source link attempt belongs to another research job';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.guard_quant_result_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_data_mode varchar;
BEGIN
    SELECT data_mode INTO v_data_mode
      FROM research_jobs
     WHERE id = NEW.research_job_id;

    IF (v_data_mode = 'MOCK' AND NOT NEW.is_demo_data)
       OR (v_data_mode = 'REAL' AND NEW.is_demo_data) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'quant result data mode does not match research job';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.guard_evidence_item_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_data_mode varchar;
    v_source_demo boolean;
    v_quant_demo boolean;
BEGIN
    SELECT data_mode INTO v_data_mode
      FROM research_jobs
     WHERE id = NEW.research_job_id;

    IF NEW.source_snapshot_id IS NOT NULL THEN
        SELECT is_demo_data INTO v_source_demo
          FROM source_snapshots
         WHERE id = NEW.source_snapshot_id;
        IF NOT EXISTS (
            SELECT 1
              FROM research_source_links
             WHERE research_job_id = NEW.research_job_id
               AND source_snapshot_id = NEW.source_snapshot_id
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'evidence source snapshot is not linked to this research job';
        END IF;
    END IF;

    IF NEW.quant_result_id IS NOT NULL THEN
        SELECT is_demo_data INTO v_quant_demo
          FROM quant_results
         WHERE id = NEW.quant_result_id
           AND research_job_id = NEW.research_job_id;
    END IF;

    IF (v_source_demo IS NOT NULL AND v_source_demo <> NEW.is_demo_data)
       OR (v_quant_demo IS NOT NULL AND v_quant_demo <> NEW.is_demo_data)
       OR (v_data_mode = 'MOCK' AND NOT NEW.is_demo_data)
       OR (v_data_mode = 'REAL' AND NEW.is_demo_data) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'evidence data lineage does not match its research job';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.guard_llm_call_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_attempt_job_id uuid;
BEGIN
    IF NEW.step_attempt_id IS NOT NULL THEN
        SELECT s.research_job_id INTO v_attempt_job_id
          FROM step_attempts AS a
          JOIN research_steps AS s ON s.id = a.research_step_id
         WHERE a.id = NEW.step_attempt_id;
        IF v_attempt_job_id IS DISTINCT FROM NEW.research_job_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'LLM call attempt belongs to another research job';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.guard_report_version_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_data_mode varchar;
BEGIN
    SELECT data_mode INTO v_data_mode
      FROM research_jobs
     WHERE id = NEW.research_job_id;

    IF v_data_mode IS DISTINCT FROM NEW.data_mode THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'report data mode does not match research job';
    END IF;

    IF v_data_mode = 'MIXED_TEST'
       AND NEW.validation_status IN ('PASSED', 'PASSED_WITH_WARNINGS') THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'MIXED_TEST reports cannot be published';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.guard_report_export_write()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_data_mode varchar;
    v_validation_status varchar;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.report_version_id <> OLD.report_version_id
        OR NEW.research_job_id <> OLD.research_job_id
        OR NEW.format <> OLD.format
        OR NEW.template_version <> OLD.template_version
        OR NEW.created_at <> OLD.created_at
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'report export identity fields are immutable';
    END IF;

    SELECT data_mode, validation_status
      INTO v_data_mode, v_validation_status
      FROM report_versions
     WHERE id = NEW.report_version_id
       AND research_job_id = NEW.research_job_id;

    IF v_data_mode = 'MIXED_TEST' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'MIXED_TEST reports cannot be exported';
    END IF;
    IF v_validation_status NOT IN ('PASSED', 'PASSED_WITH_WARNINGS') THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'only validated reports can be exported';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.guard_research_latest_report()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_report record;
    v_previous_version integer;
BEGIN
    IF NEW.latest_report_version_id IS DISTINCT FROM OLD.latest_report_version_id
       AND NEW.latest_report_version_id IS NOT NULL THEN
        IF OLD.status <> 'VALIDATING_REPORT'
           OR NEW.status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'latest report may change only during atomic report finalization';
        END IF;

        SELECT validation_status, data_mode
          INTO v_report
          FROM report_versions
         WHERE id = NEW.latest_report_version_id
           AND research_job_id = NEW.id;

        IF NOT FOUND
           OR v_report.validation_status NOT IN ('PASSED', 'PASSED_WITH_WARNINGS') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'latest report must be a validated version for this research job';
        END IF;
        IF v_report.data_mode = 'MIXED_TEST' OR v_report.data_mode <> NEW.data_mode THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'latest report cannot publish mixed or mismatched data';
        END IF;

        IF OLD.latest_report_version_id IS NOT NULL THEN
            SELECT version INTO v_previous_version
              FROM report_versions
             WHERE id = OLD.latest_report_version_id
               AND research_job_id = OLD.id;
            IF (SELECT version FROM report_versions WHERE id = NEW.latest_report_version_id)
               <= v_previous_version THEN
                RAISE EXCEPTION USING ERRCODE = '23514',
                    MESSAGE = 'a replacement latest report must have a greater version';
            END IF;
        END IF;
    ELSIF NEW.latest_report_version_id IS DISTINCT FROM OLD.latest_report_version_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'latest report reference cannot be cleared';
    END IF;

    IF NEW.status IN ('COMPLETED', 'PARTIALLY_COMPLETED')
       AND NEW.latest_report_version_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'successful research terminal state requires a validated report';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.guard_research_run_manifest_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_data_mode varchar;
    v_report_validation varchar;
    v_report_mode varchar;
BEGIN
    SELECT data_mode INTO v_data_mode
      FROM research_jobs
     WHERE id = NEW.research_job_id;
    IF v_data_mode IS DISTINCT FROM NEW.data_mode THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'run manifest data mode does not match research job';
    END IF;
    IF NEW.status = 'PUBLISHED' AND v_data_mode = 'MIXED_TEST' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'MIXED_TEST run manifests cannot be published';
    END IF;
    IF NEW.status = 'PUBLISHED' THEN
        SELECT validation_status, data_mode
          INTO v_report_validation, v_report_mode
          FROM report_versions
         WHERE id = NEW.report_version_id
           AND research_job_id = NEW.research_job_id;
        IF v_report_validation NOT IN ('PASSED', 'PASSED_WITH_WARNINGS')
           OR v_report_mode IS DISTINCT FROM NEW.data_mode THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'published run manifest requires a validated matching report';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.require_material_claim_evidence()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF NEW.materiality = 'MATERIAL'
       AND NEW.validation_status IN ('PASSED', 'PASSED_WITH_WARNINGS')
       AND NOT EXISTS (
           SELECT 1
             FROM claim_evidence_links
            WHERE claim_id = NEW.id
              AND research_job_id = NEW.research_job_id
              AND support_role IN ('PRIMARY', 'SUPPORTING')
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'MATERIAL Claim requires PRIMARY or SUPPORTING Evidence';
    END IF;
    RETURN NULL;
END;
$function$;

CREATE FUNCTION app_private.require_validated_report_claims()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF NEW.validation_status IN ('PASSED', 'PASSED_WITH_WARNINGS') THEN
        IF NOT EXISTS (
            SELECT 1 FROM claims WHERE report_version_id = NEW.id
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'validated report requires at least one Claim';
        END IF;

        IF EXISTS (
            SELECT 1
              FROM claims AS c
              JOIN claim_evidence_links AS l ON l.claim_id = c.id
              JOIN evidence_items AS e ON e.id = l.evidence_id
             WHERE c.report_version_id = NEW.id
               AND e.is_demo_data <> (NEW.data_mode = 'MOCK')
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'report Evidence data mode does not match the report';
        END IF;
    END IF;
    RETURN NULL;
END;
$function$;

CREATE TRIGGER trg_research_source_links_guard
BEFORE INSERT ON research_source_links
FOR EACH ROW EXECUTE FUNCTION app_private.guard_research_source_link_insert();

CREATE TRIGGER trg_quant_results_guard
BEFORE INSERT ON quant_results
FOR EACH ROW EXECUTE FUNCTION app_private.guard_quant_result_insert();

CREATE TRIGGER trg_evidence_items_guard
BEFORE INSERT ON evidence_items
FOR EACH ROW EXECUTE FUNCTION app_private.guard_evidence_item_insert();

CREATE TRIGGER trg_llm_calls_guard
BEFORE INSERT ON llm_calls
FOR EACH ROW EXECUTE FUNCTION app_private.guard_llm_call_insert();

CREATE TRIGGER trg_report_versions_guard
BEFORE INSERT ON report_versions
FOR EACH ROW EXECUTE FUNCTION app_private.guard_report_version_insert();

CREATE TRIGGER trg_report_exports_guard
BEFORE INSERT OR UPDATE ON report_exports
FOR EACH ROW EXECUTE FUNCTION app_private.guard_report_export_write();

CREATE TRIGGER trg_research_jobs_report_guard
BEFORE UPDATE ON research_jobs
FOR EACH ROW EXECUTE FUNCTION app_private.guard_research_latest_report();

CREATE TRIGGER trg_research_run_manifests_guard
BEFORE INSERT ON research_run_manifests
FOR EACH ROW EXECUTE FUNCTION app_private.guard_research_run_manifest_insert();

CREATE CONSTRAINT TRIGGER trg_claims_material_evidence
AFTER INSERT ON claims
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION app_private.require_material_claim_evidence();

CREATE CONSTRAINT TRIGGER trg_report_versions_claims
AFTER INSERT ON report_versions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION app_private.require_validated_report_claims();

CREATE TRIGGER trg_source_snapshots_immutable
BEFORE UPDATE OR DELETE ON source_snapshots
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_research_source_links_immutable
BEFORE UPDATE OR DELETE ON research_source_links
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_quant_results_immutable
BEFORE UPDATE OR DELETE ON quant_results
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_evidence_items_immutable
BEFORE UPDATE OR DELETE ON evidence_items
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_llm_calls_immutable
BEFORE UPDATE OR DELETE ON llm_calls
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_report_versions_immutable
BEFORE UPDATE OR DELETE ON report_versions
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_claims_immutable
BEFORE UPDATE OR DELETE ON claims
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_claim_evidence_links_immutable
BEFORE UPDATE OR DELETE ON claim_evidence_links
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_research_run_manifests_immutable
BEFORE UPDATE OR DELETE ON research_run_manifests
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

REVOKE ALL ON FUNCTION app_private.guard_research_source_link_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.guard_quant_result_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.guard_evidence_item_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.guard_llm_call_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.guard_report_version_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.guard_report_export_write() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.guard_research_latest_report() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.guard_research_run_manifest_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.require_material_claim_evidence() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.require_validated_report_claims() FROM PUBLIC;

-- Deterministic UUIDs make Mock fixtures and golden tests stable across databases.
INSERT INTO securities (
    id, symbol, company_name, exchange, security_type, currency,
    active, is_demo_data
) VALUES
    ('00000000-0000-4000-8000-000000000001', 'MU',
        'Micron Technology, Inc.', 'NASDAQ', 'COMMON_STOCK', 'USD', true, true),
    ('00000000-0000-4000-8000-000000000002', 'NVDA',
        'NVIDIA Corporation', 'NASDAQ', 'COMMON_STOCK', 'USD', true, true),
    ('00000000-0000-4000-8000-000000000003', 'RKLB',
        'Rocket Lab USA, Inc.', 'NASDAQ', 'COMMON_STOCK', 'USD', true, true),
    ('00000000-0000-4000-8000-000000000004', 'SPY',
        'SPDR S&P 500 ETF Trust', 'NYSEARCA', 'ETF', 'USD', true, true),
    ('00000000-0000-4000-8000-000000000005', 'QQQ',
        'Invesco QQQ Trust', 'NASDAQ', 'ETF', 'USD', true, true)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE source_snapshots IS
    'Immutable provider/source payload roots. Mock lineage is a correctness field.';
COMMENT ON TABLE report_versions IS
    'Immutable validated report JSON; research_report_v1 is the canonical Phase 3 schema.';
COMMENT ON TABLE research_run_manifests IS
    'Immutable execution-cycle manifest used by Java finalization and audit.';
