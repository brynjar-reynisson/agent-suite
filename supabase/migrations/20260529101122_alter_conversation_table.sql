ALTER TABLE conversation
    ADD COLUMN model          TEXT,
    ADD COLUMN root_directory TEXT,
    DROP COLUMN last_updated;

ALTER TABLE conversation
    RENAME COLUMN start TO create_time;
