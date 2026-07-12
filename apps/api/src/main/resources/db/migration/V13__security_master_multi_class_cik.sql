-- One SEC registrant can issue multiple listed share classes or securities.
-- CIK remains indexed for resolution, but it is not a security-level unique key.
DROP INDEX ux_securities_cik;

CREATE INDEX ix_securities_cik
    ON securities (cik)
    WHERE cik IS NOT NULL;
