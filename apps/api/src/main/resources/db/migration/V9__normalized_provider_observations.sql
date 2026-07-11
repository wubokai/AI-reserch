-- Normalized, append-only provider observations required by the v1 data model.
-- source_snapshots remains the immutable audit authority; these tables provide
-- queryable facts with an explicit source and research boundary.

CREATE TABLE market_price_bars (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    source_snapshot_id uuid NOT NULL REFERENCES source_snapshots (id) ON DELETE RESTRICT,
    security_id uuid REFERENCES securities (id) ON DELETE RESTRICT,
    symbol varchar(10) NOT NULL,
    interval varchar(8) NOT NULL,
    observation_date date NOT NULL,
    open numeric(38, 12) NOT NULL,
    high numeric(38, 12) NOT NULL,
    low numeric(38, 12) NOT NULL,
    close numeric(38, 12) NOT NULL,
    adjusted_close numeric(38, 12) NOT NULL,
    volume bigint NOT NULL,
    provider varchar(128) NOT NULL,
    retrieved_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_market_price_bars_observation UNIQUE (
        research_job_id, source_snapshot_id, symbol, interval, observation_date
    ),
    CONSTRAINT ck_market_price_bars_symbol CHECK (
        symbol ~ '^[A-Z][A-Z0-9.-]{0,9}$'
    ),
    CONSTRAINT ck_market_price_bars_interval CHECK (interval IN ('1d')),
    CONSTRAINT ck_market_price_bars_prices CHECK (
        open > 0 AND high > 0 AND low > 0 AND close > 0 AND adjusted_close > 0
        AND high >= open AND high >= close AND low <= open AND low <= close
        AND high >= low
    ),
    CONSTRAINT ck_market_price_bars_volume CHECK (volume >= 0),
    CONSTRAINT ck_market_price_bars_provider CHECK (btrim(provider) <> '')
);

CREATE INDEX ix_market_price_bars_symbol_date
    ON market_price_bars (symbol, observation_date DESC);
CREATE INDEX ix_market_price_bars_research
    ON market_price_bars (research_job_id, observation_date);

CREATE TABLE financial_metrics (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    source_snapshot_id uuid NOT NULL REFERENCES source_snapshots (id) ON DELETE RESTRICT,
    security_id uuid REFERENCES securities (id) ON DELETE RESTRICT,
    symbol varchar(10) NOT NULL,
    fiscal_period varchar(24) NOT NULL,
    fiscal_year integer NOT NULL,
    period_end_date date NOT NULL,
    metric_name varchar(128) NOT NULL,
    metric_value numeric(38, 12) NOT NULL,
    unit varchar(32) NOT NULL,
    provider varchar(128) NOT NULL,
    source_url text,
    published_at timestamptz,
    retrieved_at timestamptz NOT NULL,
    taxonomy varchar(64),
    concept varchar(255),
    accession_number varchar(32),
    is_derived boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_financial_metrics_observation UNIQUE (
        research_job_id, source_snapshot_id, metric_name, fiscal_period, period_end_date
    ),
    CONSTRAINT ck_financial_metrics_symbol CHECK (
        symbol ~ '^[A-Z][A-Z0-9.-]{0,9}$'
    ),
    CONSTRAINT ck_financial_metrics_period CHECK (btrim(fiscal_period) <> ''),
    CONSTRAINT ck_financial_metrics_year CHECK (fiscal_year BETWEEN 1900 AND 9999),
    CONSTRAINT ck_financial_metrics_name CHECK (btrim(metric_name) <> ''),
    CONSTRAINT ck_financial_metrics_unit CHECK (btrim(unit) <> ''),
    CONSTRAINT ck_financial_metrics_provider CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_financial_metrics_times CHECK (
        published_at IS NULL OR retrieved_at >= published_at
    )
);

CREATE INDEX ix_financial_metrics_symbol_period
    ON financial_metrics (symbol, period_end_date DESC, metric_name);
CREATE INDEX ix_financial_metrics_research
    ON financial_metrics (research_job_id, metric_name);

CREATE TABLE macro_series (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    research_job_id uuid NOT NULL REFERENCES research_jobs (id) ON DELETE RESTRICT,
    source_snapshot_id uuid NOT NULL REFERENCES source_snapshots (id) ON DELETE RESTRICT,
    series_id varchar(128) NOT NULL,
    series_name varchar(255) NOT NULL,
    observation_date date NOT NULL,
    metric_value numeric(38, 12) NOT NULL,
    unit varchar(64) NOT NULL,
    frequency varchar(64),
    realtime_start date,
    realtime_end date,
    provider varchar(128) NOT NULL,
    retrieved_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ux_macro_series_observation UNIQUE (
        research_job_id, source_snapshot_id, series_id, observation_date
    ),
    CONSTRAINT ck_macro_series_id CHECK (btrim(series_id) <> ''),
    CONSTRAINT ck_macro_series_name CHECK (btrim(series_name) <> ''),
    CONSTRAINT ck_macro_series_unit CHECK (btrim(unit) <> ''),
    CONSTRAINT ck_macro_series_provider CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_macro_series_realtime CHECK (
        realtime_start IS NULL OR realtime_end IS NULL OR realtime_end >= realtime_start
    )
);

CREATE INDEX ix_macro_series_series_date
    ON macro_series (series_id, observation_date DESC);
CREATE INDEX ix_macro_series_research
    ON macro_series (research_job_id, series_id);

CREATE FUNCTION app_private.guard_normalized_provider_observation_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM research_source_links
         WHERE research_job_id = NEW.research_job_id
           AND source_snapshot_id = NEW.source_snapshot_id
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'normalized observation source is not linked to this research job';
    END IF;
    RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION app_private.guard_normalized_provider_observation_insert() FROM PUBLIC;

CREATE TRIGGER trg_market_price_bars_guard
BEFORE INSERT ON market_price_bars
FOR EACH ROW EXECUTE FUNCTION app_private.guard_normalized_provider_observation_insert();

CREATE TRIGGER trg_financial_metrics_guard
BEFORE INSERT ON financial_metrics
FOR EACH ROW EXECUTE FUNCTION app_private.guard_normalized_provider_observation_insert();

CREATE TRIGGER trg_macro_series_guard
BEFORE INSERT ON macro_series
FOR EACH ROW EXECUTE FUNCTION app_private.guard_normalized_provider_observation_insert();

CREATE TRIGGER trg_market_price_bars_immutable
BEFORE UPDATE OR DELETE ON market_price_bars
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_financial_metrics_immutable
BEFORE UPDATE OR DELETE ON financial_metrics
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();

CREATE TRIGGER trg_macro_series_immutable
BEFORE UPDATE OR DELETE ON macro_series
FOR EACH ROW EXECUTE FUNCTION app_private.reject_immutable_research_artifact_mutation();
