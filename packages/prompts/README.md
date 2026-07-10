# Prompt registry

Prompt assets will be added in Phase 6. Each prompt must have:

- an immutable version identifier;
- its compatible input/output Schema versions;
- a purpose, owner, evaluation set, and change log;
- explicit instructions that external content is untrusted data;
- a Mock implementation/fixture for tests.

Do not store API keys, user data, provider payloads, or production responses in this directory.
