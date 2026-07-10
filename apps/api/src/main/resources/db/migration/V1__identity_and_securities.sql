CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- SECURITY DEFINER queue functions resolve application tables from public.
-- Keep that schema non-writable for untrusted roles so objects cannot be shadowed.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA IF NOT EXISTS app_private;
REVOKE ALL ON SCHEMA app_private FROM PUBLIC;

CREATE FUNCTION app_private.enforce_row_version_and_timestamp()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = '40001',
            MESSAGE = format('%s row_version must increase by exactly one', TG_TABLE_NAME);
    END IF;

    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION app_private.enforce_row_version_and_timestamp() FROM PUBLIC;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext,
    auth_provider varchar(64),
    auth_subject varchar(255),
    password_hash text,
    role varchar(32) NOT NULL DEFAULT 'USER',
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    created_by uuid,
    updated_by uuid,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_identity_present CHECK (
        email IS NOT NULL
        OR (auth_provider IS NOT NULL AND auth_subject IS NOT NULL)
    ),
    CONSTRAINT ck_users_provider_identity_pair CHECK (
        (auth_provider IS NULL AND auth_subject IS NULL)
        OR (auth_provider IS NOT NULL AND auth_subject IS NOT NULL)
    ),
    CONSTRAINT ck_users_email_not_blank CHECK (
        email IS NULL OR btrim(email::text) <> ''
    ),
    CONSTRAINT ck_users_auth_provider_not_blank CHECK (
        auth_provider IS NULL OR btrim(auth_provider) <> ''
    ),
    CONSTRAINT ck_users_auth_subject_not_blank CHECK (
        auth_subject IS NULL OR btrim(auth_subject) <> ''
    ),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ANALYST', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_users_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_users_row_version CHECK (row_version >= 0),
    CONSTRAINT fk_users_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by)
        REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_users_email
    ON users (email)
    WHERE email IS NOT NULL;

CREATE UNIQUE INDEX ux_users_provider_subject
    ON users (auth_provider, auth_subject)
    WHERE auth_provider IS NOT NULL AND auth_subject IS NOT NULL;

CREATE INDEX ix_users_status
    ON users (status, created_at DESC, id DESC);

CREATE TRIGGER trg_users_row_version
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE FUNCTION app_private.enforce_row_version_and_timestamp();

CREATE TABLE securities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol varchar(32) NOT NULL,
    company_name varchar(255) NOT NULL,
    exchange varchar(64) NOT NULL,
    security_type varchar(32) NOT NULL,
    currency char(3) NOT NULL,
    cik varchar(10),
    active boolean NOT NULL DEFAULT true,
    is_demo_data boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    created_by uuid REFERENCES users (id) ON DELETE RESTRICT,
    updated_by uuid REFERENCES users (id) ON DELETE RESTRICT,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_securities_symbol CHECK (
        symbol = upper(symbol)
        AND symbol ~ '^[A-Z0-9][A-Z0-9.-]{0,31}$'
    ),
    CONSTRAINT ck_securities_company_name CHECK (btrim(company_name) <> ''),
    CONSTRAINT ck_securities_exchange CHECK (btrim(exchange) <> ''),
    CONSTRAINT ck_securities_type CHECK (security_type IN ('COMMON_STOCK', 'ETF')),
    CONSTRAINT ck_securities_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_securities_cik CHECK (cik IS NULL OR cik ~ '^[0-9]{1,10}$'),
    CONSTRAINT ck_securities_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_securities_row_version CHECK (row_version >= 0)
);

CREATE UNIQUE INDEX ux_securities_symbol_exchange
    ON securities (upper(symbol), upper(exchange));

CREATE UNIQUE INDEX ux_securities_cik
    ON securities (cik)
    WHERE cik IS NOT NULL;

CREATE INDEX ix_securities_search
    ON securities (active, upper(symbol), upper(exchange));

CREATE TRIGGER trg_securities_row_version
BEFORE UPDATE ON securities
FOR EACH ROW
EXECUTE FUNCTION app_private.enforce_row_version_and_timestamp();
