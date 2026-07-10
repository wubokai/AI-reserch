ALTER TABLE llm_calls
    ADD COLUMN provider_request_id varchar(160),
    ADD COLUMN pricing_version varchar(64);

ALTER TABLE llm_calls
    ADD CONSTRAINT ck_llm_calls_provider_request_id CHECK (
        provider_request_id IS NULL OR btrim(provider_request_id) <> ''
    ),
    ADD CONSTRAINT ck_llm_calls_pricing_version CHECK (
        pricing_version IS NULL OR btrim(pricing_version) <> ''
    );

CREATE TABLE llm_budget_reservations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    step_attempt_id uuid NOT NULL REFERENCES step_attempts (id) ON DELETE RESTRICT,
    request_hash varchar(64) NOT NULL,
    reserved_cost_usd numeric(18, 8) NOT NULL,
    actual_cost_usd numeric(18, 8),
    status varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    settled_at timestamptz,
    CONSTRAINT ux_llm_budget_attempt_request UNIQUE (step_attempt_id, request_hash),
    CONSTRAINT ck_llm_budget_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_llm_budget_reserved_cost CHECK (reserved_cost_usd >= 0),
    CONSTRAINT ck_llm_budget_actual_cost CHECK (
        actual_cost_usd IS NULL OR actual_cost_usd >= 0
    ),
    CONSTRAINT ck_llm_budget_status CHECK (
        status IN ('RESERVED', 'SETTLED', 'RELEASED')
    ),
    CONSTRAINT ck_llm_budget_settlement CHECK (
        (status = 'RESERVED' AND settled_at IS NULL AND actual_cost_usd IS NULL)
        OR (status = 'SETTLED' AND settled_at IS NOT NULL AND actual_cost_usd IS NOT NULL)
        OR (status = 'RELEASED' AND settled_at IS NOT NULL)
    ),
    CONSTRAINT ck_llm_budget_time CHECK (
        expires_at > created_at AND updated_at >= created_at
    )
);

CREATE INDEX ix_llm_budget_active
    ON llm_budget_reservations (research_job_id, expires_at)
    WHERE status = 'RESERVED';

CREATE FUNCTION app_private.guard_llm_budget_write()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_attempt_job_id uuid;
BEGIN
    SELECT s.research_job_id INTO v_attempt_job_id
      FROM step_attempts AS a
      JOIN research_steps AS s ON s.id = a.research_step_id
     WHERE a.id = NEW.step_attempt_id;

    IF v_attempt_job_id IS DISTINCT FROM NEW.research_job_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'LLM budget attempt belongs to another research job';
    END IF;

    IF TG_OP = 'UPDATE' AND (
        NEW.id <> OLD.id
        OR NEW.research_job_id <> OLD.research_job_id
        OR NEW.step_attempt_id <> OLD.step_attempt_id
        OR NEW.request_hash <> OLD.request_hash
        OR NEW.reserved_cost_usd <> OLD.reserved_cost_usd
        OR NEW.created_at <> OLD.created_at
        OR OLD.status <> 'RESERVED'
        OR NEW.status NOT IN ('SETTLED', 'RELEASED')
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'LLM budget reservation identity or transition is immutable';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_llm_budget_guard
BEFORE INSERT OR UPDATE ON llm_budget_reservations
FOR EACH ROW EXECUTE FUNCTION app_private.guard_llm_budget_write();

CREATE TRIGGER trg_llm_budget_no_delete
BEFORE DELETE ON llm_budget_reservations
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_artifact();
