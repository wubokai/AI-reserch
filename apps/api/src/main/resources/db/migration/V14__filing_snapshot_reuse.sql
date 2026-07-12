-- Reuse immutable SEC filings across refreshed source snapshots without duplicating chunks.

CREATE TABLE filing_source_snapshot_links (
    filing_id uuid NOT NULL REFERENCES filings (id) ON DELETE RESTRICT,
    source_snapshot_id uuid NOT NULL REFERENCES source_snapshots (id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (filing_id, source_snapshot_id)
);

CREATE INDEX ix_filing_source_snapshot_links_source
    ON filing_source_snapshot_links (source_snapshot_id, filing_id);

INSERT INTO filing_source_snapshot_links (filing_id, source_snapshot_id)
SELECT id, source_snapshot_id
  FROM filings
ON CONFLICT DO NOTHING;

CREATE FUNCTION app_private.guard_filing_source_snapshot_link_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_filing_demo boolean;
    v_snapshot_demo boolean;
BEGIN
    SELECT is_demo_data INTO v_filing_demo
      FROM filings
     WHERE id = NEW.filing_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503',
            MESSAGE = 'filing does not exist';
    END IF;

    SELECT is_demo_data INTO v_snapshot_demo
      FROM source_snapshots
     WHERE id = NEW.source_snapshot_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503',
            MESSAGE = 'filing source snapshot does not exist';
    END IF;

    IF v_filing_demo <> v_snapshot_demo THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'filing data mode does not match linked source snapshot';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_filing_source_snapshot_links_guard
BEFORE INSERT ON filing_source_snapshot_links
FOR EACH ROW EXECUTE FUNCTION app_private.guard_filing_source_snapshot_link_insert();

CREATE TRIGGER trg_filing_source_snapshot_links_immutable
BEFORE UPDATE OR DELETE ON filing_source_snapshot_links
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

REVOKE ALL ON FUNCTION app_private.guard_filing_source_snapshot_link_insert() FROM PUBLIC;
