# Analytics Schemas

Canonical Java-to-Python contracts:

- `full-analysis-request.schema.json`
- `full-analysis-response.schema.json`

The schemas deliberately include adjusted OHLCV and complete benchmark price bars. Java and Python contract tests must validate the same fixtures against these files.
