CREATE SCHEMA queue_v1;
REVOKE ALL ON SCHEMA queue_v1 FROM PUBLIC;

CREATE FUNCTION app_private.research_status_rank(p_status varchar)
RETURNS smallint
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $function$
    SELECT CASE p_status
        WHEN 'CREATED' THEN 1
        WHEN 'QUEUED' THEN 2
        WHEN 'RESOLVING_SECURITY' THEN 3
        WHEN 'FETCHING_MARKET_DATA' THEN 4
        WHEN 'FETCHING_FUNDAMENTALS' THEN 5
        WHEN 'FETCHING_FILINGS' THEN 6
        WHEN 'FETCHING_MACRO_DATA' THEN 7
        WHEN 'VALIDATING_DATA' THEN 8
        WHEN 'RUNNING_QUANT_ANALYSIS' THEN 9
        WHEN 'ANALYZING_FUNDAMENTALS' THEN 10
        WHEN 'BUILDING_EVIDENCE' THEN 11
        WHEN 'GENERATING_REPORT' THEN 12
        WHEN 'VALIDATING_REPORT' THEN 13
        ELSE NULL
    END::smallint
$function$;

CREATE FUNCTION app_private.guard_research_job_update()
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

    -- A manual retry records its first runnable step as a QUEUED checkpoint. Resume may
    -- project only that exact stored step to its canonical public status and progress floor.
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

            IF NEW.status IN ('COMPLETED', 'PARTIALLY_COMPLETED')
               AND OLD.status <> 'VALIDATING_REPORT' THEN
                RAISE EXCEPTION USING ERRCODE = '23514',
                    MESSAGE = 'publish terminal states require VALIDATING_REPORT';
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

CREATE FUNCTION app_private.guard_research_step_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_research_is_retrying boolean;
    v_manual_retry boolean;
BEGIN
    IF NEW.research_job_id <> OLD.research_job_id
       OR NEW.step_type <> OLD.step_type
       OR NEW.sequence_no <> OLD.sequence_no
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'immutable research step fields cannot be changed';
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM public.research_jobs AS r
         WHERE r.id = OLD.research_job_id
           AND r.status = 'QUEUED'
           AND NOT r.cancellation_requested
           AND r.deleted_at IS NULL
    ) INTO v_research_is_retrying;

    v_manual_retry := v_research_is_retrying
        AND OLD.status <> 'RUNNING'
        AND NEW.status = 'PENDING'
        AND NEW.max_attempts > OLD.max_attempts
        AND (
            OLD.status IN ('PENDING', 'FAILED', 'CANCELLED', 'SKIPPED', 'SUCCEEDED')
        );

    IF OLD.status = 'SUCCEEDED' AND NOT v_manual_retry AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'a succeeded step is immutable and must be reused';
    END IF;

    IF v_manual_retry AND OLD.status = 'SUCCEEDED'
       AND NEW.input_hash = OLD.input_hash
       AND NEW.implementation_version = OLD.implementation_version THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'a succeeded step with unchanged execution input must be reused';
    END IF;

    IF OLD.status IN ('FAILED', 'CANCELLED', 'SKIPPED')
       AND NEW.status <> OLD.status
       AND NOT v_manual_retry THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'terminal step can only be reopened by explicit manual retry';
    END IF;

    IF NOT v_manual_retry AND OLD.attempt_count > 0 AND (
        NEW.input_hash <> OLD.input_hash
        OR NEW.implementation_version <> OLD.implementation_version
        OR NEW.payload_version <> OLD.payload_version
        OR NEW.payload_json <> OLD.payload_json
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'claimed step execution inputs are immutable';
    END IF;

    IF NEW.attempt_count < OLD.attempt_count
       OR NEW.max_attempts < OLD.max_attempts THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'step attempt counters cannot decrease';
    END IF;

    IF OLD.status = 'PENDING' AND NEW.status = 'RUNNING' THEN
        IF NEW.attempt_count <> OLD.attempt_count + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'claim must increment attempt_count exactly once';
        END IF;
    ELSIF NEW.attempt_count <> OLD.attempt_count THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'attempt_count may change only during claim';
    END IF;

    IF NEW.status <> OLD.status AND NOT v_manual_retry THEN
        IF OLD.status = 'PENDING' AND NEW.status NOT IN ('RUNNING', 'SKIPPED', 'CANCELLED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'illegal PENDING step transition';
        ELSIF OLD.status = 'RUNNING'
              AND NEW.status NOT IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'illegal RUNNING step transition';
        END IF;
    END IF;

    IF OLD.successful_output_hash IS NOT NULL
       AND NEW.successful_output_hash IS DISTINCT FROM OLD.successful_output_hash THEN
        IF NOT v_manual_retry OR NEW.successful_output_hash IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'successful output may only be cleared for changed-input manual retry';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

CREATE FUNCTION app_private.guard_step_attempt_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF NEW.research_step_id <> OLD.research_step_id
       OR NEW.attempt_number <> OLD.attempt_number
       OR NEW.input_hash <> OLD.input_hash
       OR NEW.worker_id <> OLD.worker_id
       OR NEW.lease_token <> OLD.lease_token
       OR NEW.started_at <> OLD.started_at
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'immutable attempt identity fields cannot be changed';
    END IF;

    IF OLD.status <> 'RUNNING' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'terminal attempt is immutable';
    END IF;

    IF OLD.status = 'RUNNING'
       AND NEW.status NOT IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'LEASE_EXPIRED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'illegal attempt transition';
    END IF;

    IF NEW.status = 'RUNNING' THEN
        IF NEW.heartbeat_at < OLD.heartbeat_at
           OR NEW.lease_expires_at < OLD.lease_expires_at
           OR NEW.lease_expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'heartbeat must use a live, non-decreasing database-time lease';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_research_jobs_state_guard
BEFORE UPDATE ON research_jobs
FOR EACH ROW
EXECUTE FUNCTION app_private.guard_research_job_update();

CREATE TRIGGER trg_research_steps_state_guard
BEFORE UPDATE ON research_steps
FOR EACH ROW
EXECUTE FUNCTION app_private.guard_research_step_update();

CREATE TRIGGER trg_step_attempts_state_guard
BEFORE UPDATE ON step_attempts
FOR EACH ROW
EXECUTE FUNCTION app_private.guard_step_attempt_update();

CREATE FUNCTION queue_v1.claim_step(
    p_worker_id varchar,
    p_supported_step_types varchar[],
    p_lease_seconds integer DEFAULT 60
)
RETURNS TABLE (
    result_code varchar,
    research_job_id uuid,
    research_step_id uuid,
    attempt_id uuid,
    attempt_number integer,
    lease_token uuid,
    lease_expires_at timestamptz,
    step_type varchar,
    input_hash varchar,
    implementation_version varchar,
    payload_version integer,
    payload_json jsonb
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_candidate record;
    v_step research_steps%ROWTYPE;
    v_attempt_id uuid := gen_random_uuid();
    v_token uuid := gen_random_uuid();
    v_lease_expires timestamptz;
    v_claim_found boolean := false;
BEGIN
    IF p_worker_id IS NULL OR length(btrim(p_worker_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'worker_id must be 1 to 128 characters';
    END IF;
    IF p_supported_step_types IS NULL OR cardinality(p_supported_step_types) = 0
       OR EXISTS (
           SELECT 1 FROM unnest(p_supported_step_types) AS supported(value)
           WHERE value IS NULL
              OR value NOT IN (
               'RESOLVE_SECURITY', 'FETCH_MARKET_DATA', 'FETCH_FUNDAMENTALS',
               'FETCH_FILINGS', 'FETCH_MACRO_DATA', 'VALIDATE_DATA',
               'RUN_QUANT_ANALYSIS', 'ANALYZE_FUNDAMENTALS', 'BUILD_EVIDENCE',
               'GENERATE_REPORT', 'VALIDATE_REPORT'
           )
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'supported_step_types is invalid';
    END IF;
    IF p_lease_seconds NOT BETWEEN 5 AND 3600 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'lease_seconds must be between 5 and 3600';
    END IF;

    FOR v_candidate IN
        SELECT r.id AS research_job_id, s.id AS research_step_id
          FROM research_jobs AS r
          JOIN research_steps AS s ON s.research_job_id = r.id
         WHERE s.status = 'PENDING'
           AND s.available_at IS NOT NULL
           AND s.available_at <= v_now
           AND s.attempt_count < s.max_attempts
           AND s.step_type = ANY (p_supported_step_types)
           AND r.deleted_at IS NULL
           AND NOT r.cancellation_requested
           AND r.status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
         ORDER BY s.priority DESC, s.available_at, s.id
         LIMIT 32
    LOOP
        PERFORM 1
          FROM research_jobs AS r
         WHERE r.id = v_candidate.research_job_id
           AND r.deleted_at IS NULL
           AND NOT r.cancellation_requested
           AND r.status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
         FOR UPDATE SKIP LOCKED;

        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        SELECT s.* INTO v_step
          FROM research_steps AS s
         WHERE s.id = v_candidate.research_step_id
           AND s.research_job_id = v_candidate.research_job_id
           AND s.status = 'PENDING'
           AND s.available_at IS NOT NULL
           AND s.available_at <= v_now
           AND s.attempt_count < s.max_attempts
           AND s.step_type = ANY (p_supported_step_types)
         FOR UPDATE SKIP LOCKED;

        IF FOUND THEN
            v_claim_found := true;
            EXIT;
        END IF;
    END LOOP;

    IF NOT v_claim_found THEN
        RETURN;
    END IF;

    v_lease_expires := v_now + make_interval(secs => p_lease_seconds);

    UPDATE research_steps AS s
       SET status = 'RUNNING',
           attempt_count = s.attempt_count + 1,
           available_at = NULL,
           row_version = s.row_version + 1
     WHERE s.id = v_step.id
     RETURNING s.* INTO v_step;

    INSERT INTO step_attempts (
        id, research_step_id, attempt_number, status, retryable,
        input_hash, worker_id, lease_token, lease_expires_at,
        heartbeat_at, started_at, created_at
    ) VALUES (
        v_attempt_id, v_step.id, v_step.attempt_count, 'RUNNING', false,
        v_step.input_hash, p_worker_id, v_token, v_lease_expires,
        v_now, v_now, v_now
    );

    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload_json, occurred_at)
    VALUES (
        'RESEARCH_STEP', v_step.id, 'STEP_STARTED',
        jsonb_build_object(
            'researchJobId', v_step.research_job_id,
            'researchStepId', v_step.id,
            'attemptId', v_attempt_id,
            'attemptNumber', v_step.attempt_count,
            'stepType', v_step.step_type
        ),
        v_now
    );

    RETURN QUERY SELECT
        'CLAIMED'::varchar,
        v_step.research_job_id,
        v_step.id,
        v_attempt_id,
        v_step.attempt_count,
        v_token,
        v_lease_expires,
        v_step.step_type::varchar,
        v_step.input_hash::varchar,
        v_step.implementation_version::varchar,
        v_step.payload_version,
        v_step.payload_json;
END;
$function$;

CREATE FUNCTION queue_v1.heartbeat(
    p_attempt_id uuid,
    p_lease_token uuid,
    p_lease_seconds integer DEFAULT 60
)
RETURNS TABLE (
    result_code varchar,
    cancellation_requested boolean,
    lease_expires_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_cancellation_requested boolean;
    v_lease_expires timestamptz;
BEGIN
    IF p_attempt_id IS NULL OR p_lease_token IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'attempt_id and lease_token are required';
    END IF;
    IF p_lease_seconds NOT BETWEEN 5 AND 3600 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'lease_seconds must be between 5 and 3600';
    END IF;

    UPDATE step_attempts AS a
       SET heartbeat_at = v_now,
           lease_expires_at = greatest(
               a.lease_expires_at,
               v_now + make_interval(secs => p_lease_seconds)
           )
      FROM research_steps AS s,
           research_jobs AS r
     WHERE a.id = p_attempt_id
       AND a.research_step_id = s.id
       AND s.research_job_id = r.id
       AND a.status = 'RUNNING'
       AND a.lease_token = p_lease_token
       AND a.lease_expires_at > v_now
       AND s.status = 'RUNNING'
     RETURNING r.cancellation_requested, a.lease_expires_at
          INTO v_cancellation_requested, v_lease_expires;

    IF NOT FOUND THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, false, NULL::timestamptz;
        RETURN;
    END IF;

    RETURN QUERY SELECT 'HEARTBEAT_ACCEPTED'::varchar, v_cancellation_requested, v_lease_expires;
END;
$function$;

CREATE FUNCTION queue_v1.checkpoint_step(
    p_attempt_id uuid,
    p_lease_token uuid,
    p_checkpoint_json jsonb
)
RETURNS TABLE (
    result_code varchar,
    cancellation_requested boolean
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_cancellation_requested boolean;
BEGIN
    IF p_checkpoint_json IS NULL OR jsonb_typeof(p_checkpoint_json) <> 'object' THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'checkpoint_json must be an object';
    END IF;

    UPDATE step_attempts AS a
       SET checkpoint_json = p_checkpoint_json
      FROM research_steps AS s,
           research_jobs AS r
     WHERE a.id = p_attempt_id
       AND a.research_step_id = s.id
       AND s.research_job_id = r.id
       AND a.status = 'RUNNING'
       AND a.lease_token = p_lease_token
       AND a.lease_expires_at > v_now
       AND s.status = 'RUNNING'
     RETURNING r.cancellation_requested INTO v_cancellation_requested;

    IF NOT FOUND THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, false;
        RETURN;
    END IF;

    RETURN QUERY SELECT 'CHECKPOINT_ACCEPTED'::varchar, v_cancellation_requested;
END;
$function$;

CREATE FUNCTION queue_v1.complete_step(
    p_attempt_id uuid,
    p_lease_token uuid,
    p_output_hash varchar,
    p_output_manifest_json jsonb DEFAULT '{}'::jsonb
)
RETURNS TABLE (
    result_code varchar,
    research_job_id uuid,
    research_step_id uuid,
    committed_output_hash varchar
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_attempt step_attempts%ROWTYPE;
    v_step research_steps%ROWTYPE;
    v_job research_jobs%ROWTYPE;
    v_step_id uuid;
    v_job_id uuid;
BEGIN
    IF p_attempt_id IS NULL OR p_lease_token IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'attempt_id and lease_token are required';
    END IF;
    IF p_output_hash IS NULL OR p_output_hash !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'output_hash must be lowercase SHA-256';
    END IF;
    IF p_output_manifest_json IS NULL OR jsonb_typeof(p_output_manifest_json) <> 'object' THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'output_manifest_json must be an object';
    END IF;

    SELECT a.research_step_id, s.research_job_id
      INTO v_step_id, v_job_id
      FROM step_attempts AS a
      JOIN research_steps AS s ON s.id = a.research_step_id
     WHERE a.id = p_attempt_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, NULL::uuid, NULL::uuid, NULL::varchar;
        RETURN;
    END IF;

    SELECT locked_job.* INTO v_job
      FROM research_jobs AS locked_job
     WHERE locked_job.id = v_job_id
     FOR SHARE;

    SELECT locked_step.* INTO v_step
      FROM research_steps AS locked_step
     WHERE locked_step.id = v_step_id
       AND locked_step.research_job_id = v_job.id
     FOR UPDATE;

    SELECT locked_attempt.* INTO v_attempt
      FROM step_attempts AS locked_attempt
     WHERE locked_attempt.id = p_attempt_id
       AND locked_attempt.research_step_id = v_step.id
     FOR UPDATE;

    IF NOT FOUND OR v_attempt.lease_token IS DISTINCT FROM p_lease_token THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, v_job.id, v_step.id, NULL::varchar;
        RETURN;
    END IF;

    IF v_attempt.status = 'SUCCEEDED' THEN
        IF v_attempt.output_hash::text = p_output_hash
           AND v_step.status = 'SUCCEEDED'
           AND v_step.successful_output_hash::text = p_output_hash THEN
            RETURN QUERY SELECT
                'ALREADY_SUCCEEDED'::varchar, v_job.id, v_step.id, p_output_hash::varchar;
        ELSE
            RETURN QUERY SELECT
                'IDEMPOTENCY_CONFLICT'::varchar, v_job.id, v_step.id,
                v_attempt.output_hash::varchar;
        END IF;
        RETURN;
    END IF;

    IF v_attempt.status <> 'RUNNING'
       OR v_attempt.lease_expires_at <= v_now
       OR v_step.status <> 'RUNNING' THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, v_job.id, v_step.id, NULL::varchar;
        RETURN;
    END IF;

    IF v_job.cancellation_requested THEN
        RETURN QUERY SELECT
            'CANCELLATION_REQUESTED'::varchar, v_job.id, v_step.id, NULL::varchar;
        RETURN;
    END IF;

    IF v_job.deleted_at IS NOT NULL
       OR v_job.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED') THEN
        RETURN QUERY SELECT 'RESEARCH_TERMINAL'::varchar, v_job.id, v_step.id, NULL::varchar;
        RETURN;
    END IF;

    UPDATE step_attempts
       SET status = 'SUCCEEDED',
           retryable = false,
           output_hash = p_output_hash,
           output_manifest_json = p_output_manifest_json,
           completed_at = v_now,
           duration_ms = greatest(0, floor(extract(epoch FROM (v_now - started_at)) * 1000)::bigint)
     WHERE id = v_attempt.id;

    UPDATE research_steps
       SET status = 'SUCCEEDED',
           successful_output_hash = p_output_hash,
           available_at = NULL,
           row_version = row_version + 1
     WHERE id = v_step.id;

    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload_json, occurred_at)
    VALUES (
        'RESEARCH_STEP', v_step.id, 'STEP_SUCCEEDED',
        jsonb_build_object(
            'researchJobId', v_job.id,
            'researchStepId', v_step.id,
            'attemptId', v_attempt.id,
            'stepType', v_step.step_type,
            'outputHash', p_output_hash
        ),
        v_now
    );

    RETURN QUERY SELECT 'SUCCEEDED'::varchar, v_job.id, v_step.id, p_output_hash::varchar;
END;
$function$;

CREATE FUNCTION queue_v1.fail_step(
    p_attempt_id uuid,
    p_lease_token uuid,
    p_retryable boolean,
    p_error_code varchar,
    p_error_message_safe varchar DEFAULT NULL,
    p_base_delay_seconds integer DEFAULT 5,
    p_max_delay_seconds integer DEFAULT 300
)
RETURNS TABLE (
    result_code varchar,
    research_job_id uuid,
    research_step_id uuid,
    step_status varchar,
    available_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_attempt step_attempts%ROWTYPE;
    v_step research_steps%ROWTYPE;
    v_job research_jobs%ROWTYPE;
    v_can_retry boolean;
    v_delay_seconds integer;
    v_jitter_seconds integer;
    v_available_at timestamptz;
    v_step_status varchar;
    v_event_type varchar;
    v_step_id uuid;
    v_job_id uuid;
BEGIN
    IF p_attempt_id IS NULL OR p_lease_token IS NULL OR p_retryable IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'attempt_id, lease_token, and retryable are required';
    END IF;
    IF p_error_code IS NULL OR p_error_code !~ '^[A-Z][A-Z0-9_.-]{0,127}$' THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'error_code is invalid';
    END IF;
    IF p_error_message_safe IS NOT NULL AND length(p_error_message_safe) > 2000 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'error_message_safe exceeds 2000 characters';
    END IF;
    IF p_base_delay_seconds NOT BETWEEN 1 AND 3600
       OR p_max_delay_seconds NOT BETWEEN p_base_delay_seconds AND 86400 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'retry delay bounds are invalid';
    END IF;

    SELECT a.research_step_id, s.research_job_id
      INTO v_step_id, v_job_id
      FROM step_attempts AS a
      JOIN research_steps AS s ON s.id = a.research_step_id
     WHERE a.id = p_attempt_id;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, NULL::uuid, NULL::uuid, NULL::varchar, NULL::timestamptz;
        RETURN;
    END IF;

    SELECT locked_job.* INTO v_job
      FROM research_jobs AS locked_job
     WHERE locked_job.id = v_job_id
     FOR SHARE;
    SELECT locked_step.* INTO v_step
      FROM research_steps AS locked_step
     WHERE locked_step.id = v_step_id
       AND locked_step.research_job_id = v_job.id
     FOR UPDATE;
    SELECT locked_attempt.* INTO v_attempt
      FROM step_attempts AS locked_attempt
     WHERE locked_attempt.id = p_attempt_id
       AND locked_attempt.research_step_id = v_step.id
     FOR UPDATE;
    IF NOT FOUND OR v_attempt.lease_token IS DISTINCT FROM p_lease_token THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, v_job.id, v_step.id, NULL::varchar, NULL::timestamptz;
        RETURN;
    END IF;

    IF v_attempt.status = 'FAILED' THEN
        RETURN QUERY SELECT 'ALREADY_FAILED'::varchar, v_job.id, v_step.id,
            v_step.status::varchar, v_step.available_at;
        RETURN;
    END IF;

    IF v_attempt.status <> 'RUNNING'
       OR v_attempt.lease_expires_at <= v_now
       OR v_step.status <> 'RUNNING' THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, v_job.id, v_step.id, v_step.status::varchar, v_step.available_at;
        RETURN;
    END IF;

    IF v_job.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED') THEN
        RETURN QUERY SELECT 'RESEARCH_TERMINAL'::varchar, v_job.id, v_step.id, v_step.status::varchar, NULL::timestamptz;
        RETURN;
    END IF;

    IF v_job.cancellation_requested THEN
        UPDATE step_attempts
           SET status = 'CANCELLED', retryable = false,
               completed_at = v_now,
               duration_ms = greatest(0, floor(extract(epoch FROM (v_now - started_at)) * 1000)::bigint)
         WHERE id = v_attempt.id;

        UPDATE research_steps
           SET status = 'CANCELLED', available_at = NULL,
               row_version = row_version + 1
         WHERE id = v_step.id;

        v_step_status := 'CANCELLED';
        v_event_type := 'STEP_CANCELLED';
        v_available_at := NULL;
    ELSE
        v_can_retry := p_retryable AND v_step.attempt_count < v_step.max_attempts;

        UPDATE step_attempts
           SET status = 'FAILED', retryable = p_retryable,
               error_code = p_error_code,
               error_message_safe = NULLIF(btrim(p_error_message_safe), ''),
               completed_at = v_now,
               duration_ms = greatest(0, floor(extract(epoch FROM (v_now - started_at)) * 1000)::bigint)
         WHERE id = v_attempt.id;

        IF v_can_retry THEN
            v_delay_seconds := least(
                p_max_delay_seconds::numeric,
                p_base_delay_seconds::numeric * power(2::numeric, least(v_step.attempt_count - 1, 30))
            )::integer;
            v_jitter_seconds := floor(random() * greatest(1, least(p_base_delay_seconds, v_delay_seconds / 4 + 1)))::integer;
            v_available_at := v_now + make_interval(secs => v_delay_seconds + v_jitter_seconds);
            v_step_status := 'PENDING';
            v_event_type := 'STEP_RETRY_SCHEDULED';
        ELSE
            v_available_at := NULL;
            v_step_status := 'FAILED';
            v_event_type := 'STEP_FAILED';
        END IF;

        UPDATE research_steps
           SET status = v_step_status,
               available_at = v_available_at,
               row_version = row_version + 1
         WHERE id = v_step.id;
    END IF;

    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload_json, occurred_at)
    VALUES (
        'RESEARCH_STEP', v_step.id, v_event_type,
        jsonb_strip_nulls(jsonb_build_object(
            'researchJobId', v_job.id,
            'researchStepId', v_step.id,
            'attemptId', v_attempt.id,
            'stepType', v_step.step_type,
            'errorCode', CASE WHEN v_event_type = 'STEP_CANCELLED' THEN NULL ELSE p_error_code END,
            'retryable', CASE WHEN v_event_type = 'STEP_CANCELLED' THEN false ELSE p_retryable END,
            'availableAt', v_available_at
        )),
        v_now
    );

    RETURN QUERY SELECT
        CASE WHEN v_step_status = 'PENDING' THEN 'RETRY_SCHEDULED' ELSE v_step_status END::varchar,
        v_job.id, v_step.id, v_step_status::varchar, v_available_at;
END;
$function$;

CREATE FUNCTION queue_v1.request_cancel(
    p_research_job_id uuid,
    p_actor_user_id uuid,
    p_request_id varchar DEFAULT NULL,
    p_reason varchar DEFAULT NULL
)
RETURNS TABLE (
    result_code varchar,
    research_status varchar,
    cancellation_requested boolean,
    cancelled_pending_steps integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_job research_jobs%ROWTYPE;
    v_cancelled_steps integer := 0;
    v_first_request boolean;
BEGIN
    IF p_research_job_id IS NULL OR p_actor_user_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'research_job_id and actor_user_id are required';
    END IF;
    IF p_request_id IS NOT NULL AND length(btrim(p_request_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'request_id is invalid';
    END IF;
    IF p_reason IS NOT NULL AND length(btrim(p_reason)) > 500 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'reason exceeds 500 characters';
    END IF;

    SELECT r.*
      INTO v_job
      FROM research_jobs AS r
      JOIN users AS u ON u.id = p_actor_user_id AND u.status = 'ACTIVE'
     WHERE r.id = p_research_job_id
       AND r.deleted_at IS NULL
       AND (r.user_id = p_actor_user_id OR u.role = 'ADMIN')
     FOR UPDATE OF r;

    IF NOT FOUND THEN
        RETURN QUERY SELECT 'NOT_FOUND'::varchar, NULL::varchar, false, 0;
        RETURN;
    END IF;

    IF v_job.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED') THEN
        RETURN QUERY SELECT 'TERMINAL_NOOP'::varchar, v_job.status::varchar,
            v_job.cancellation_requested, 0;
        RETURN;
    END IF;

    v_first_request := NOT v_job.cancellation_requested;

    IF v_first_request THEN
        UPDATE research_jobs
           SET cancellation_requested = true,
               cancellation_requested_at = v_now,
               row_version = row_version + 1,
               updated_by = p_actor_user_id
         WHERE id = v_job.id
         RETURNING * INTO v_job;
    END IF;

    WITH cancelled AS (
        UPDATE research_steps
           SET status = 'CANCELLED',
               available_at = NULL,
               row_version = row_version + 1,
               updated_by = p_actor_user_id
         WHERE research_job_id = v_job.id
           AND status = 'PENDING'
         RETURNING id, step_type
    ), emitted AS (
        INSERT INTO outbox_events (
            aggregate_type, aggregate_id, event_type, payload_json, request_id, occurred_at
        )
        SELECT 'RESEARCH_STEP', c.id, 'STEP_CANCELLED',
               jsonb_build_object(
                   'researchJobId', v_job.id,
                   'researchStepId', c.id,
                   'stepType', c.step_type,
                   'reason', 'RESEARCH_CANCELLATION_REQUESTED'
               ),
               p_request_id,
               v_now
          FROM cancelled AS c
        RETURNING 1
    )
    SELECT count(*)::integer INTO v_cancelled_steps FROM emitted;

    IF v_first_request THEN
        INSERT INTO audit_events (
            research_job_id, actor_type, actor_user_id, action,
            request_id, metadata_json, occurred_at
        ) VALUES (
            v_job.id, 'USER', p_actor_user_id, 'CANCEL_REQUESTED',
            p_request_id,
            jsonb_strip_nulls(jsonb_build_object(
                'reason', COALESCE(
                    NULLIF(
                        regexp_replace(
                            btrim(p_reason),
                            '[[:space:][:cntrl:]]+',
                            ' ',
                            'g'
                        ),
                        ''
                    ),
                    'USER_REQUESTED'
                ),
                'cancelledPendingSteps', v_cancelled_steps
            )),
            v_now
        );

        INSERT INTO outbox_events (
            aggregate_type, aggregate_id, event_type, payload_json, request_id, occurred_at
        ) VALUES (
            'RESEARCH', v_job.id, 'RESEARCH_CANCELLATION_REQUESTED',
            jsonb_build_object(
                'researchJobId', v_job.id,
                'cancelledPendingSteps', v_cancelled_steps
            ),
            p_request_id,
            v_now
        );
    END IF;

    RETURN QUERY SELECT
        CASE WHEN v_first_request THEN 'CANCELLATION_REQUESTED' ELSE 'ALREADY_REQUESTED' END::varchar,
        v_job.status::varchar,
        true,
        v_cancelled_steps;
END;
$function$;

CREATE FUNCTION queue_v1.cancel_step(
    p_attempt_id uuid,
    p_lease_token uuid,
    p_checkpoint_json jsonb DEFAULT NULL
)
RETURNS TABLE (
    result_code varchar,
    research_job_id uuid,
    research_step_id uuid
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_attempt step_attempts%ROWTYPE;
    v_step research_steps%ROWTYPE;
    v_job research_jobs%ROWTYPE;
    v_step_id uuid;
    v_job_id uuid;
BEGIN
    IF p_attempt_id IS NULL OR p_lease_token IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'attempt_id and lease_token are required';
    END IF;

    IF p_checkpoint_json IS NOT NULL AND jsonb_typeof(p_checkpoint_json) <> 'object' THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'checkpoint_json must be an object';
    END IF;

    SELECT a.research_step_id, s.research_job_id
      INTO v_step_id, v_job_id
      FROM step_attempts AS a
      JOIN research_steps AS s ON s.id = a.research_step_id
     WHERE a.id = p_attempt_id;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, NULL::uuid, NULL::uuid;
        RETURN;
    END IF;

    SELECT locked_job.* INTO v_job
      FROM research_jobs AS locked_job
     WHERE locked_job.id = v_job_id
     FOR SHARE;
    SELECT locked_step.* INTO v_step
      FROM research_steps AS locked_step
     WHERE locked_step.id = v_step_id
       AND locked_step.research_job_id = v_job.id
     FOR UPDATE;
    SELECT locked_attempt.* INTO v_attempt
      FROM step_attempts AS locked_attempt
     WHERE locked_attempt.id = p_attempt_id
       AND locked_attempt.research_step_id = v_step.id
     FOR UPDATE;
    IF NOT FOUND OR v_attempt.lease_token IS DISTINCT FROM p_lease_token THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, v_job.id, v_step.id;
        RETURN;
    END IF;

    IF v_attempt.status = 'CANCELLED' AND v_step.status = 'CANCELLED' THEN
        RETURN QUERY SELECT 'ALREADY_CANCELLED'::varchar, v_job.id, v_step.id;
        RETURN;
    END IF;

    IF v_attempt.status <> 'RUNNING'
       OR v_attempt.lease_expires_at <= v_now
       OR v_step.status <> 'RUNNING' THEN
        RETURN QUERY SELECT 'STALE_LEASE'::varchar, v_job.id, v_step.id;
        RETURN;
    END IF;

    IF NOT v_job.cancellation_requested THEN
        RETURN QUERY SELECT 'CANCELLATION_NOT_REQUESTED'::varchar, v_job.id, v_step.id;
        RETURN;
    END IF;

    UPDATE step_attempts
       SET status = 'CANCELLED',
           retryable = false,
           checkpoint_json = COALESCE(p_checkpoint_json, checkpoint_json),
           completed_at = v_now,
           duration_ms = greatest(0, floor(extract(epoch FROM (v_now - started_at)) * 1000)::bigint)
     WHERE id = v_attempt.id;

    UPDATE research_steps
       SET status = 'CANCELLED', available_at = NULL,
           row_version = row_version + 1
     WHERE id = v_step.id;

    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload_json, occurred_at)
    VALUES (
        'RESEARCH_STEP', v_step.id, 'STEP_CANCELLED',
        jsonb_build_object(
            'researchJobId', v_job.id,
            'researchStepId', v_step.id,
            'attemptId', v_attempt.id,
            'stepType', v_step.step_type,
            'reason', 'WORKER_CONFIRMED_CANCELLATION'
        ),
        v_now
    );

    RETURN QUERY SELECT 'CANCELLED'::varchar, v_job.id, v_step.id;
END;
$function$;

CREATE FUNCTION queue_v1.reap_expired(
    p_batch_size integer DEFAULT 100,
    p_base_delay_seconds integer DEFAULT 5,
    p_max_delay_seconds integer DEFAULT 300
)
RETURNS TABLE (
    attempt_id uuid,
    research_job_id uuid,
    research_step_id uuid,
    step_status varchar,
    available_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_candidate record;
    v_attempt step_attempts%ROWTYPE;
    v_step research_steps%ROWTYPE;
    v_job research_jobs%ROWTYPE;
    v_can_retry boolean;
    v_delay_seconds integer;
    v_jitter_seconds integer;
    v_available_at timestamptz;
    v_step_status varchar;
    v_event_type varchar;
BEGIN
    IF p_batch_size NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'batch_size must be between 1 and 1000';
    END IF;
    IF p_base_delay_seconds NOT BETWEEN 1 AND 3600
       OR p_max_delay_seconds NOT BETWEEN p_base_delay_seconds AND 86400 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'retry delay bounds are invalid';
    END IF;

    FOR v_candidate IN
        SELECT a.id AS attempt_id,
               a.research_step_id,
               s.research_job_id,
               a.lease_token
          FROM step_attempts AS a
          JOIN research_steps AS s ON s.id = a.research_step_id
         WHERE a.status = 'RUNNING'
           AND a.lease_expires_at <= v_now
         ORDER BY a.lease_expires_at, a.id
         LIMIT p_batch_size
    LOOP
        SELECT locked_job.* INTO v_job
          FROM research_jobs AS locked_job
         WHERE locked_job.id = v_candidate.research_job_id
         FOR SHARE;

        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        SELECT locked_step.* INTO v_step
          FROM research_steps AS locked_step
         WHERE locked_step.id = v_candidate.research_step_id
           AND locked_step.research_job_id = v_job.id
         FOR UPDATE SKIP LOCKED;

        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        SELECT locked_attempt.* INTO v_attempt
          FROM step_attempts AS locked_attempt
         WHERE locked_attempt.id = v_candidate.attempt_id
           AND locked_attempt.research_step_id = v_step.id
         FOR UPDATE SKIP LOCKED;

        IF NOT FOUND
           OR v_attempt.status <> 'RUNNING'
           OR v_attempt.lease_token IS DISTINCT FROM v_candidate.lease_token
           OR v_attempt.lease_expires_at > v_now
           OR v_step.status <> 'RUNNING' THEN
            CONTINUE;
        END IF;

        v_can_retry := NOT v_job.cancellation_requested
            AND v_job.status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
            AND v_step.attempt_count < v_step.max_attempts;

        IF v_job.cancellation_requested OR v_job.status = 'CANCELLED' THEN
            v_step_status := 'CANCELLED';
            v_available_at := NULL;
            v_event_type := 'STEP_CANCELLED';

            UPDATE step_attempts
               SET status = 'CANCELLED',
                   retryable = false,
                   error_code = NULL,
                   error_message_safe = NULL,
                   completed_at = v_now,
                   duration_ms = greatest(0, floor(extract(epoch FROM (v_now - started_at)) * 1000)::bigint)
             WHERE id = v_attempt.id;
        ELSIF v_can_retry THEN
            v_delay_seconds := least(
                p_max_delay_seconds::numeric,
                p_base_delay_seconds::numeric * power(2::numeric, least(v_step.attempt_count - 1, 30))
            )::integer;
            v_jitter_seconds := floor(random() * greatest(1, least(p_base_delay_seconds, v_delay_seconds / 4 + 1)))::integer;
            v_available_at := v_now + make_interval(secs => v_delay_seconds + v_jitter_seconds);
            v_step_status := 'PENDING';
            v_event_type := 'STEP_LEASE_EXPIRED_RETRY_SCHEDULED';

            UPDATE step_attempts
               SET status = 'LEASE_EXPIRED',
                   retryable = true,
                   error_code = 'LEASE_EXPIRED',
                   error_message_safe = 'Worker lease expired before a terminal result was committed.',
                   completed_at = v_now,
                   duration_ms = greatest(0, floor(extract(epoch FROM (v_now - started_at)) * 1000)::bigint)
             WHERE id = v_attempt.id;
        ELSE
            v_step_status := 'FAILED';
            v_available_at := NULL;
            v_event_type := 'STEP_LEASE_EXPIRED';

            UPDATE step_attempts
               SET status = 'LEASE_EXPIRED',
                   retryable = false,
                   error_code = 'LEASE_EXPIRED',
                   error_message_safe = 'Worker lease expired before a terminal result was committed.',
                   completed_at = v_now,
                   duration_ms = greatest(0, floor(extract(epoch FROM (v_now - started_at)) * 1000)::bigint)
             WHERE id = v_attempt.id;
        END IF;

        UPDATE research_steps
           SET status = v_step_status,
               available_at = v_available_at,
               row_version = row_version + 1
         WHERE id = v_step.id;

        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload_json, occurred_at)
        VALUES (
            'RESEARCH_STEP', v_step.id, v_event_type,
            jsonb_strip_nulls(jsonb_build_object(
                'researchJobId', v_job.id,
                'researchStepId', v_step.id,
                'attemptId', v_attempt.id,
                'stepType', v_step.step_type,
                'availableAt', v_available_at
            )),
            v_now
        );

        RETURN QUERY SELECT v_attempt.id, v_job.id, v_step.id,
            v_step_status::varchar, v_available_at;
    END LOOP;
END;
$function$;

REVOKE ALL ON FUNCTION queue_v1.claim_step(varchar, varchar[], integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION queue_v1.heartbeat(uuid, uuid, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION queue_v1.checkpoint_step(uuid, uuid, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION queue_v1.complete_step(uuid, uuid, varchar, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION queue_v1.fail_step(uuid, uuid, boolean, varchar, varchar, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION queue_v1.request_cancel(uuid, uuid, varchar, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION queue_v1.cancel_step(uuid, uuid, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION queue_v1.reap_expired(integer, integer, integer) FROM PUBLIC;

COMMENT ON SCHEMA queue_v1 IS
    'Versioned, least-privilege durable queue API. Grant only USAGE and required function EXECUTE privileges to worker roles.';
