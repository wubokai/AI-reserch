CREATE TABLE research_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    security_id uuid REFERENCES securities (id) ON DELETE RESTRICT,
    symbol_input varchar(10),
    query text NOT NULL,
    locale varchar(5) NOT NULL,
    request_json jsonb NOT NULL,
    status varchar(40) NOT NULL DEFAULT 'CREATED',
    progress smallint NOT NULL DEFAULT 0,
    current_step varchar(40),
    data_mode varchar(16) NOT NULL,
    cancellation_requested boolean NOT NULL DEFAULT false,
    cancellation_requested_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    deleted_at timestamptz,
    deleted_by uuid REFERENCES users (id) ON DELETE RESTRICT,
    delete_reason varchar(500),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    created_by uuid REFERENCES users (id) ON DELETE RESTRICT,
    updated_by uuid REFERENCES users (id) ON DELETE RESTRICT,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_research_jobs_symbol CHECK (
        symbol_input IS NULL
        OR (symbol_input = upper(symbol_input)
            AND symbol_input ~ '^[A-Z][A-Z0-9.-]{0,9}$')
    ),
    CONSTRAINT ck_research_jobs_query CHECK (
        length(btrim(query)) BETWEEN 10 AND 4000
    ),
    CONSTRAINT ck_research_jobs_locale CHECK (locale IN ('zh-CN', 'en-US')),
    CONSTRAINT ck_research_jobs_request_json CHECK (
        jsonb_typeof(request_json) = 'object'
    ),
    CONSTRAINT ck_research_jobs_status CHECK (status IN (
        'CREATED',
        'QUEUED',
        'RESOLVING_SECURITY',
        'FETCHING_MARKET_DATA',
        'FETCHING_FUNDAMENTALS',
        'FETCHING_FILINGS',
        'FETCHING_MACRO_DATA',
        'VALIDATING_DATA',
        'RUNNING_QUANT_ANALYSIS',
        'ANALYZING_FUNDAMENTALS',
        'BUILDING_EVIDENCE',
        'GENERATING_REPORT',
        'VALIDATING_REPORT',
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'FAILED',
        'CANCELLED'
    )),
    CONSTRAINT ck_research_jobs_progress CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT ck_research_jobs_current_step CHECK (
        current_step IS NULL OR btrim(current_step) <> ''
    ),
    CONSTRAINT ck_research_jobs_data_mode CHECK (
        data_mode IN ('REAL', 'MOCK', 'MIXED_TEST')
    ),
    CONSTRAINT ck_research_jobs_cancellation_time CHECK (
        (cancellation_requested AND cancellation_requested_at IS NOT NULL)
        OR (NOT cancellation_requested AND cancellation_requested_at IS NULL)
    ),
    CONSTRAINT ck_research_jobs_terminal_time CHECK (
        (status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
            AND completed_at IS NOT NULL)
        OR (status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
            AND completed_at IS NULL)
    ),
    CONSTRAINT ck_research_jobs_started_time CHECK (
        started_at IS NULL OR started_at >= created_at
    ),
    CONSTRAINT ck_research_jobs_completed_time CHECK (
        completed_at IS NULL OR completed_at >= COALESCE(started_at, created_at)
    ),
    CONSTRAINT ck_research_jobs_soft_delete CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL AND delete_reason IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL
            AND length(btrim(delete_reason)) BETWEEN 1 AND 500)
    ),
    CONSTRAINT ck_research_jobs_updated_time CHECK (updated_at >= created_at),
    CONSTRAINT ck_research_jobs_row_version CHECK (row_version >= 0)
);

CREATE TABLE research_steps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    step_type varchar(40) NOT NULL,
    sequence_no smallint NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    input_hash varchar(64) NOT NULL,
    successful_output_hash varchar(64),
    payload_version integer NOT NULL DEFAULT 1,
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    implementation_version varchar(128) NOT NULL,
    priority integer NOT NULL DEFAULT 0,
    available_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3,
    skip_reason varchar(500),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    created_by uuid REFERENCES users (id) ON DELETE RESTRICT,
    updated_by uuid REFERENCES users (id) ON DELETE RESTRICT,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_research_steps_job_type UNIQUE (research_job_id, step_type),
    CONSTRAINT ux_research_steps_job_sequence UNIQUE (research_job_id, sequence_no),
    CONSTRAINT ck_research_steps_type CHECK (step_type IN (
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
    )),
    CONSTRAINT ck_research_steps_sequence CHECK (sequence_no BETWEEN 1 AND 32767),
    CONSTRAINT ck_research_steps_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')
    ),
    CONSTRAINT ck_research_steps_input_hash CHECK (
        input_hash::text ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_research_steps_output_hash CHECK (
        successful_output_hash IS NULL
        OR successful_output_hash::text ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_research_steps_success_output CHECK (
        (status = 'SUCCEEDED' AND successful_output_hash IS NOT NULL)
        OR (status <> 'SUCCEEDED' AND successful_output_hash IS NULL)
    ),
    CONSTRAINT ck_research_steps_payload_version CHECK (payload_version > 0),
    CONSTRAINT ck_research_steps_payload_json CHECK (
        jsonb_typeof(payload_json) = 'object'
    ),
    CONSTRAINT ck_research_steps_implementation_version CHECK (
        btrim(implementation_version) <> ''
    ),
    CONSTRAINT ck_research_steps_attempt_budget CHECK (
        attempt_count >= 0
        AND max_attempts > 0
        AND attempt_count <= max_attempts
    ),
    CONSTRAINT ck_research_steps_runnable_time CHECK (
        status = 'PENDING' OR available_at IS NULL
    ),
    CONSTRAINT ck_research_steps_skip_reason CHECK (
        (status = 'SKIPPED' AND length(btrim(skip_reason)) BETWEEN 1 AND 500)
        OR (status <> 'SKIPPED' AND skip_reason IS NULL)
    ),
    CONSTRAINT ck_research_steps_updated_time CHECK (updated_at >= created_at),
    CONSTRAINT ck_research_steps_row_version CHECK (row_version >= 0)
);

CREATE TABLE step_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_step_id uuid NOT NULL REFERENCES research_steps (id) ON DELETE RESTRICT,
    attempt_number integer NOT NULL,
    status varchar(24) NOT NULL,
    retryable boolean NOT NULL DEFAULT false,
    input_hash varchar(64) NOT NULL,
    output_hash varchar(64),
    output_manifest_json jsonb,
    checkpoint_json jsonb,
    worker_id varchar(128) NOT NULL,
    lease_token uuid NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    heartbeat_at timestamptz NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    duration_ms bigint,
    error_code varchar(128),
    error_message_safe varchar(2000),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_step_attempts_number UNIQUE (research_step_id, attempt_number),
    CONSTRAINT ck_step_attempts_number CHECK (attempt_number > 0),
    CONSTRAINT ck_step_attempts_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'LEASE_EXPIRED')
    ),
    CONSTRAINT ck_step_attempts_input_hash CHECK (
        input_hash::text ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_step_attempts_output_hash CHECK (
        output_hash IS NULL OR output_hash::text ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_step_attempts_output_manifest CHECK (
        output_manifest_json IS NULL OR jsonb_typeof(output_manifest_json) = 'object'
    ),
    CONSTRAINT ck_step_attempts_checkpoint CHECK (
        checkpoint_json IS NULL OR jsonb_typeof(checkpoint_json) = 'object'
    ),
    CONSTRAINT ck_step_attempts_worker CHECK (btrim(worker_id) <> ''),
    CONSTRAINT ck_step_attempts_running_lease CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status <> 'RUNNING' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_step_attempts_terminal_output CHECK (
        (status = 'SUCCEEDED' AND output_hash IS NOT NULL)
        OR (status <> 'SUCCEEDED' AND output_hash IS NULL)
    ),
    CONSTRAINT ck_step_attempts_retryable CHECK (
        NOT retryable OR status IN ('FAILED', 'LEASE_EXPIRED')
    ),
    CONSTRAINT ck_step_attempts_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_step_attempts_error CHECK (
        (status IN ('FAILED', 'LEASE_EXPIRED')
            AND error_code IS NOT NULL AND btrim(error_code) <> '')
        OR (status NOT IN ('FAILED', 'LEASE_EXPIRED') AND error_code IS NULL)
    ),
    CONSTRAINT ck_step_attempts_started CHECK (started_at >= created_at),
    CONSTRAINT ck_step_attempts_completed CHECK (
        completed_at IS NULL OR completed_at >= started_at
    )
);

CREATE UNIQUE INDEX ux_step_attempt_one_running
    ON step_attempts (research_step_id)
    WHERE status = 'RUNNING';

CREATE UNIQUE INDEX ux_step_attempt_token
    ON step_attempts (research_step_id, lease_token);

CREATE TABLE idempotency_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    http_method varchar(16) NOT NULL,
    request_path varchar(500) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PROCESSING',
    response_status smallint,
    response_body jsonb,
    resource_id uuid,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_idempotency_records_scope UNIQUE (
        user_id, http_method, request_path, idempotency_key
    ),
    CONSTRAINT ck_idempotency_records_method CHECK (
        http_method = upper(http_method)
        AND http_method IN ('POST', 'PUT', 'PATCH', 'DELETE')
    ),
    CONSTRAINT ck_idempotency_records_path CHECK (
        request_path LIKE '/api/v1/%' AND length(btrim(request_path)) <= 500
    ),
    CONSTRAINT ck_idempotency_records_key CHECK (
        length(btrim(idempotency_key)) BETWEEN 1 AND 128
        AND idempotency_key ~ '^[!-~]{1,128}$'
    ),
    CONSTRAINT ck_idempotency_records_hash CHECK (
        request_hash::text ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_idempotency_records_status CHECK (
        status IN ('PROCESSING', 'COMPLETED')
    ),
    CONSTRAINT ck_idempotency_records_response CHECK (
        (status = 'PROCESSING' AND response_status IS NULL AND response_body IS NULL)
        OR (status = 'COMPLETED' AND response_status BETWEEN 200 AND 599
            AND response_body IS NOT NULL)
    ),
    CONSTRAINT ck_idempotency_records_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_idempotency_records_updated CHECK (updated_at >= created_at),
    CONSTRAINT ck_idempotency_records_row_version CHECK (row_version >= 0)
);

CREATE TABLE audit_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    research_job_id uuid REFERENCES research_jobs (id) ON DELETE RESTRICT,
    actor_type varchar(24) NOT NULL,
    actor_user_id uuid REFERENCES users (id) ON DELETE RESTRICT,
    action varchar(128) NOT NULL,
    request_id varchar(128),
    source_ip_hash char(64),
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ck_audit_events_actor_type CHECK (
        actor_type IN ('USER', 'SYSTEM', 'WORKER')
    ),
    CONSTRAINT ck_audit_events_actor_user CHECK (
        (actor_type = 'USER' AND actor_user_id IS NOT NULL)
        OR actor_type <> 'USER'
    ),
    CONSTRAINT ck_audit_events_action CHECK (btrim(action) <> ''),
    CONSTRAINT ck_audit_events_request_id CHECK (
        request_id IS NULL OR btrim(request_id) <> ''
    ),
    CONSTRAINT ck_audit_events_source_ip_hash CHECK (
        source_ip_hash IS NULL OR source_ip_hash::text ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_audit_events_metadata CHECK (
        jsonb_typeof(metadata_json) = 'object'
    )
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    event_version integer NOT NULL DEFAULT 1,
    payload_json jsonb NOT NULL,
    request_id varchar(128),
    occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0,
    last_error_code varchar(128),
    CONSTRAINT ck_outbox_events_aggregate_type CHECK (btrim(aggregate_type) <> ''),
    CONSTRAINT ck_outbox_events_event_type CHECK (btrim(event_type) <> ''),
    CONSTRAINT ck_outbox_events_version CHECK (event_version > 0),
    CONSTRAINT ck_outbox_events_payload CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_outbox_events_request_id CHECK (
        request_id IS NULL OR btrim(request_id) <> ''
    ),
    CONSTRAINT ck_outbox_events_publish_attempts CHECK (publish_attempts >= 0),
    CONSTRAINT ck_outbox_events_published_time CHECK (
        published_at IS NULL OR published_at >= occurred_at
    )
);

CREATE INDEX ix_research_jobs_user_created
    ON research_jobs (user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_research_jobs_user_symbol_status
    ON research_jobs (user_id, symbol_input, status, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_research_jobs_active_status
    ON research_jobs (status, updated_at, id)
    WHERE deleted_at IS NULL
      AND status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED');

CREATE INDEX ix_research_steps_job_sequence
    ON research_steps (research_job_id, sequence_no);

CREATE INDEX ix_research_steps_claim
    ON research_steps (priority DESC, available_at, id)
    INCLUDE (step_type, research_job_id)
    WHERE status = 'PENDING' AND available_at IS NOT NULL;

CREATE INDEX ix_step_attempt_reaper
    ON step_attempts (lease_expires_at, id)
    INCLUDE (research_step_id, lease_token)
    WHERE status = 'RUNNING';

CREATE INDEX ix_step_attempts_step_created
    ON step_attempts (research_step_id, attempt_number DESC);

CREATE INDEX ix_idempotency_records_expiry
    ON idempotency_records (expires_at, id)
    WHERE status = 'COMPLETED';

CREATE INDEX ix_idempotency_records_processing
    ON idempotency_records (created_at, id)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_audit_events_research
    ON audit_events (research_job_id, occurred_at DESC, id DESC)
    WHERE research_job_id IS NOT NULL;

CREATE INDEX ix_audit_events_actor
    ON audit_events (actor_user_id, occurred_at DESC, id DESC)
    WHERE actor_user_id IS NOT NULL;

CREATE INDEX ix_outbox_events_unpublished
    ON outbox_events (occurred_at, id)
    WHERE published_at IS NULL;

CREATE TRIGGER trg_research_jobs_row_version
BEFORE UPDATE ON research_jobs
FOR EACH ROW
EXECUTE FUNCTION app_private.enforce_row_version_and_timestamp();

CREATE TRIGGER trg_research_steps_row_version
BEFORE UPDATE ON research_steps
FOR EACH ROW
EXECUTE FUNCTION app_private.enforce_row_version_and_timestamp();

CREATE TRIGGER trg_idempotency_records_row_version
BEFORE UPDATE ON idempotency_records
FOR EACH ROW
EXECUTE FUNCTION app_private.enforce_row_version_and_timestamp();
