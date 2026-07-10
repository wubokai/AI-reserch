-- Complete a fenced attempt and unlock the next non-skipped logical step in one
-- transaction. The existing queue_v1.complete_step API remains unchanged.

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

    v_next_input_hash := encode(
        digest(
            convert_to(
                p_output_hash
                    || ':' || v_next_step.implementation_version
                    || ':' || v_next_step.payload_version::text,
                'UTF8'
            ),
            'sha256'
        ),
        'hex'
    );

    IF v_next_step.status = 'PENDING'
       AND v_next_step.attempt_count = 0
       AND v_next_step.available_at IS NULL THEN
        UPDATE research_steps
           SET input_hash = v_next_input_hash,
               available_at = v_now,
               row_version = row_version + 1
         WHERE id = v_next_step.id
           AND status = 'PENDING'
           AND attempt_count = 0
           AND available_at IS NULL;
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

        IF v_next_step.input_hash <> v_next_input_hash THEN
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
