ALTER TABLE conversation
    ADD COLUMN external_id TEXT UNIQUE NOT NULL;
