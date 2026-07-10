-- Complete a fenced attempt and unlock the next non-skipped logical step in one
-- transaction. The existing queue_v1.complete_step API remains unchanged.

-- This is the canonical ready-input contract shared with ResearchCommandService.
-- A retry may temporarily store a deferred (non-runnable) hash for downstream
-- work whose predecessor output is not known yet. Only this actual-output hash
-- may make that downstream step runnable.
CREATE FUNCTION queue_v1.derive_successor_input_hash(
    p_upstream_output_hash varchar,
    p_implementation_version varchar,
    p_payload_version integer
)
RETURNS varchar
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp
AS $function$
    SELECT encode(
        digest(
            convert_to(
                p_upstream_output_hash
                    || ':' || p_implementation_version
                    || ':' || p_payload_version::text,
                'UTF8'
            ),
            'sha256'
        ),
        'hex'
    )::varchar
$function$;

REVOKE ALL ON FUNCTION queue_v1.derive_successor_input_hash(
    varchar, varchar, integer
) FROM PUBLIC;

COMMENT ON FUNCTION queue_v1.derive_successor_input_hash(
    varchar, varchar, integer
) IS
    'Canonical SHA-256 input hash for a step whose non-skipped predecessor has committed an output.';

-- V3 deliberately freezes execution input after the first claim. Manual retry
-- preserves attempt_count, so the atomic advancement path needs one narrowly
-- defined exception: it may replace a deferred hash while unlocking an idle
-- PENDING retry step. The exception is self-verifying against the closest
-- non-skipped predecessor and does not weaken attempt lease fencing.
CREATE OR REPLACE FUNCTION app_private.guard_research_step_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_research_is_retrying boolean;
    v_manual_retry boolean;
    v_dependency_advancement boolean;
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

    SELECT EXISTS (
        SELECT 1
          FROM public.research_jobs AS r
          JOIN LATERAL (
              SELECT predecessor.status,
                     predecessor.successful_output_hash
                FROM public.research_steps AS predecessor
               WHERE predecessor.research_job_id = OLD.research_job_id
                 AND predecessor.sequence_no < OLD.sequence_no
                 AND predecessor.status <> 'SKIPPED'
               ORDER BY predecessor.sequence_no DESC
               LIMIT 1
          ) AS predecessor ON true
         WHERE r.id = OLD.research_job_id
           AND r.deleted_at IS NULL
           AND NOT r.cancellation_requested
           AND r.status NOT IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
           AND OLD.status = 'PENDING'
           AND NEW.status = 'PENDING'
           AND OLD.available_at IS NULL
           AND NEW.available_at IS NOT NULL
           AND OLD.successful_output_hash IS NULL
           AND NEW.successful_output_hash IS NULL
           AND NEW.attempt_count = OLD.attempt_count
           AND NEW.max_attempts = OLD.max_attempts
           AND NEW.implementation_version = OLD.implementation_version
           AND NEW.payload_version = OLD.payload_version
           AND NEW.payload_json = OLD.payload_json
           AND NOT EXISTS (
               SELECT 1
                 FROM public.step_attempts AS running_attempt
                WHERE running_attempt.research_step_id = OLD.id
                  AND running_attempt.status = 'RUNNING'
           )
           AND predecessor.status = 'SUCCEEDED'
           AND predecessor.successful_output_hash IS NOT NULL
           AND NEW.input_hash = queue_v1.derive_successor_input_hash(
               predecessor.successful_output_hash,
               NEW.implementation_version,
               NEW.payload_version
           )
    ) INTO v_dependency_advancement;

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

    IF NOT v_manual_retry AND NOT v_dependency_advancement
       AND OLD.attempt_count > 0 AND (
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

CREATE FUNCTION queue_v1.complete_step_and_advance(
    p_attempt_id uuid,
    p_lease_token uuid,
    p_output_hash varchar,
    p_output_manifest_json jsonb DEFAULT '{}'::jsonb
)
RETURNS TABLE (
    result_code varchar,
    research_job_id uuid,
    research_step_id uuid,
    committed_output_hash varchar,
    next_research_step_id uuid,
    next_step_type varchar,
    next_input_hash varchar
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, queue_v1, pg_temp
AS $function$
DECLARE
    v_now timestamptz := statement_timestamp();
    v_completion record;
    v_current_step research_steps%ROWTYPE;
    v_next_step research_steps%ROWTYPE;
    v_next_input_hash varchar(64);
    v_advanced boolean := false;
BEGIN
    SELECT completed.result_code,
           completed.research_job_id,
           completed.research_step_id,
           completed.committed_output_hash
      INTO v_completion
      FROM queue_v1.complete_step(
          p_attempt_id,
          p_lease_token,
          p_output_hash,
          p_output_manifest_json
      ) AS completed;

    IF NOT FOUND THEN
        RETURN QUERY SELECT
            'STALE_LEASE'::varchar,
            NULL::uuid,
            NULL::uuid,
            NULL::varchar,
            NULL::uuid,
            NULL::varchar,
            NULL::varchar;
        RETURN;
    END IF;

    IF v_completion.result_code NOT IN ('SUCCEEDED', 'ALREADY_SUCCEEDED') THEN
        RETURN QUERY SELECT
            v_completion.result_code::varchar,
            v_completion.research_job_id::uuid,
            v_completion.research_step_id::uuid,
            v_completion.committed_output_hash::varchar,
            NULL::uuid,
            NULL::varchar,
            NULL::varchar;
        RETURN;
    END IF;

    SELECT s.* INTO v_current_step
      FROM research_steps AS s
     WHERE s.id = v_completion.research_step_id
       AND s.research_job_id = v_completion.research_job_id;

    SELECT s.* INTO v_next_step
      FROM research_steps AS s
     WHERE s.research_job_id = v_current_step.research_job_id
       AND s.sequence_no > v_current_step.sequence_no
       AND s.status <> 'SKIPPED'
     ORDER BY s.sequence_no
     LIMIT 1
     FOR UPDATE;

    IF NOT FOUND THEN
        RETURN QUERY SELECT
            CASE
                WHEN v_completion.result_code = 'SUCCEEDED'
                    THEN 'SUCCEEDED_NO_SUCCESSOR'
                ELSE 'ALREADY_SUCCEEDED_NO_SUCCESSOR'
            END::varchar,
            v_completion.research_job_id::uuid,
            v_completion.research_step_id::uuid,
            v_completion.committed_output_hash::varchar,
            NULL::uuid,
            NULL::varchar,
            NULL::varchar;
        RETURN;
    END IF;

    v_next_input_hash := queue_v1.derive_successor_input_hash(
        p_output_hash,
        v_next_step.implementation_version,
        v_next_step.payload_version
    );

    IF v_next_step.status = 'PENDING'
       AND v_next_step.available_at IS NULL
       AND v_next_step.attempt_count < v_next_step.max_attempts THEN
        UPDATE research_steps
           SET input_hash = v_next_input_hash,
               available_at = v_now,
               row_version = row_version + 1
         WHERE id = v_next_step.id
           AND status = 'PENDING'
           AND available_at IS NULL
           AND attempt_count < max_attempts;
        v_advanced := FOUND;

        IF v_advanced THEN
            INSERT INTO outbox_events (
                aggregate_type,
                aggregate_id,
                event_type,
                payload_json,
                occurred_at
            ) VALUES (
                'RESEARCH_STEP',
                v_next_step.id,
                'STEP_READY',
                jsonb_build_object(
                    'researchJobId', v_next_step.research_job_id,
                    'researchStepId', v_next_step.id,
                    'stepType', v_next_step.step_type,
                    'upstreamResearchStepId', v_current_step.id,
                    'upstreamOutputHash', p_output_hash,
                    'inputHash', v_next_input_hash
                ),
                v_now
            );
        END IF;
    END IF;

    IF NOT v_advanced THEN
        SELECT s.* INTO v_next_step
          FROM research_steps AS s
         WHERE s.id = v_next_step.id;

        IF v_next_step.input_hash <> v_next_input_hash
           OR (v_next_step.status = 'PENDING' AND v_next_step.available_at IS NULL) THEN
            RETURN QUERY SELECT
                'ADVANCEMENT_CONFLICT'::varchar,
                v_completion.research_job_id::uuid,
                v_completion.research_step_id::uuid,
                v_completion.committed_output_hash::varchar,
                v_next_step.id,
                v_next_step.step_type::varchar,
                v_next_step.input_hash::varchar;
            RETURN;
        END IF;
    END IF;

    RETURN QUERY SELECT
        CASE
            WHEN v_advanced AND v_completion.result_code = 'SUCCEEDED'
                THEN 'SUCCEEDED_AND_ADVANCED'
            WHEN v_advanced
                THEN 'ALREADY_SUCCEEDED_AND_ADVANCED'
            ELSE 'ALREADY_ADVANCED'
        END::varchar,
        v_completion.research_job_id::uuid,
        v_completion.research_step_id::uuid,
        v_completion.committed_output_hash::varchar,
        v_next_step.id,
        v_next_step.step_type::varchar,
        v_next_input_hash::varchar;
END;
$function$;

REVOKE ALL ON FUNCTION queue_v1.complete_step_and_advance(
    uuid, uuid, varchar, jsonb
) FROM PUBLIC;

COMMENT ON FUNCTION queue_v1.complete_step_and_advance(
    uuid, uuid, varchar, jsonb
) IS
    'Fenced, idempotent completion plus deterministic unlock of the next non-skipped step. The last step is completed without publishing the Research terminal state.';
