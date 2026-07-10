-- Phase 5 evidence lineage and SEC-search foundation.
-- External filing text is immutable data. It is never executable input.

ALTER TABLE source_snapshots
    ADD COLUMN storage_uri text,
    ADD COLUMN metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_source_snapshots_storage_uri CHECK (
        storage_uri IS NULL OR btrim(storage_uri) <> ''
    ),
    ADD CONSTRAINT ck_source_snapshots_metadata CHECK (
        jsonb_typeof(metadata_json) = 'object'
    );

ALTER TABLE claims
    ADD COLUMN date_references_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_claims_date_references CHECK (
        jsonb_typeof(date_references_json) = 'array'
    );

CREATE TABLE filings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_snapshot_id uuid NOT NULL REFERENCES source_snapshots (id) ON DELETE RESTRICT,
    external_document_id varchar(255) NOT NULL,
    accession_number varchar(20),
    form_type varchar(20) NOT NULL,
    filing_date date NOT NULL,
    report_period date,
    title varchar(500) NOT NULL,
    raw_text_uri text,
    raw_text_hash varchar(64) NOT NULL,
    cleaned_text_hash varchar(64) NOT NULL,
    parser_version varchar(64) NOT NULL,
    is_demo_data boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_filings_source_document UNIQUE (
        source_snapshot_id, external_document_id
    ),
    CONSTRAINT ux_filings_accession UNIQUE (accession_number),
    CONSTRAINT ux_filings_id_source UNIQUE (id, source_snapshot_id),
    CONSTRAINT ck_filings_external_id CHECK (btrim(external_document_id) <> ''),
    CONSTRAINT ck_filings_accession CHECK (
        accession_number IS NULL
        OR accession_number ~ '^[0-9]{10}-[0-9]{2}-[0-9]{6}$'
    ),
    CONSTRAINT ck_filings_form_type CHECK (btrim(form_type) <> ''),
    CONSTRAINT ck_filings_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_filings_raw_uri CHECK (
        raw_text_uri IS NULL OR btrim(raw_text_uri) <> ''
    ),
    CONSTRAINT ck_filings_raw_hash CHECK (raw_text_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_filings_cleaned_hash CHECK (cleaned_text_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_filings_parser_version CHECK (btrim(parser_version) <> '')
);

CREATE TABLE filing_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    filing_id uuid NOT NULL REFERENCES filings (id) ON DELETE RESTRICT,
    section_name varchar(160) NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    content_hash varchar(64) NOT NULL,
    character_start integer NOT NULL,
    character_end integer NOT NULL,
    token_estimate integer NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('english', content)
    ) STORED,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_filing_chunks_position UNIQUE (
        filing_id, section_name, chunk_index
    ),
    CONSTRAINT ck_filing_chunks_section CHECK (btrim(section_name) <> ''),
    CONSTRAINT ck_filing_chunks_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_filing_chunks_content CHECK (btrim(content) <> ''),
    CONSTRAINT ck_filing_chunks_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_filing_chunks_offsets CHECK (
        character_start >= 0 AND character_end > character_start
    ),
    CONSTRAINT ck_filing_chunks_tokens CHECK (token_estimate > 0)
);

CREATE INDEX ix_filings_source_date
    ON filings (source_snapshot_id, filing_date DESC, form_type);

CREATE INDEX ix_filing_chunks_search
    ON filing_chunks USING gin (search_vector);

CREATE INDEX ix_filing_chunks_filing_section
    ON filing_chunks (filing_id, section_name, chunk_index);

CREATE FUNCTION app_private.guard_filing_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_snapshot_demo boolean;
BEGIN
    SELECT is_demo_data INTO v_snapshot_demo
      FROM source_snapshots
     WHERE id = NEW.source_snapshot_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503',
            MESSAGE = 'filing source snapshot does not exist';
    END IF;
    IF v_snapshot_demo <> NEW.is_demo_data THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'filing data mode does not match its source snapshot';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_filings_guard
BEFORE INSERT ON filings
FOR EACH ROW EXECUTE FUNCTION app_private.guard_filing_insert();

CREATE TRIGGER trg_filings_immutable
BEFORE UPDATE OR DELETE ON filings
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_filing_chunks_immutable
BEFORE UPDATE OR DELETE ON filing_chunks
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

REVOKE ALL ON FUNCTION app_private.guard_filing_insert() FROM PUBLIC;
