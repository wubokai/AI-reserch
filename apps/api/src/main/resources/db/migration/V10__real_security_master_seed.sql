-- Minimal v1 real security master. These identifiers are public company/ETF
-- identities only; no market observations are embedded in the migration.
DROP INDEX ux_securities_symbol_exchange;

CREATE UNIQUE INDEX ux_securities_symbol_exchange_mode
    ON securities (upper(symbol), upper(exchange), is_demo_data);

INSERT INTO securities (
    symbol, company_name, exchange, security_type, currency, cik,
    active, is_demo_data
) VALUES
    ('MU', 'Micron Technology, Inc.', 'NASDAQ', 'COMMON_STOCK', 'USD', '723125', true, false),
    ('NVDA', 'NVIDIA Corporation', 'NASDAQ', 'COMMON_STOCK', 'USD', '1045810', true, false),
    ('RKLB', 'Rocket Lab Corporation', 'NASDAQ', 'COMMON_STOCK', 'USD', '1819994', true, false),
    ('SPY', 'SPDR S&P 500 ETF Trust', 'NYSEARCA', 'ETF', 'USD', '884394', true, false),
    ('QQQ', 'Invesco QQQ Trust, Series 1', 'NASDAQ', 'ETF', 'USD', '1067839', true, false)
ON CONFLICT DO NOTHING;
