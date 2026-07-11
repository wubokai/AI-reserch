-- One immutable provider payload may be normalized into different requested
-- research windows. Preserve the raw hash while making the normalized hash part
-- of snapshot identity so a 1y slice can never alias a 5y payload.
ALTER TABLE source_snapshots
    DROP CONSTRAINT ux_source_snapshots_content;

ALTER TABLE source_snapshots
    ADD CONSTRAINT ux_source_snapshots_content UNIQUE (
        provider, raw_data_hash, normalized_data_hash, schema_version
    );
