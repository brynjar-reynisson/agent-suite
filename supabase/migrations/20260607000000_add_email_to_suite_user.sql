ALTER TABLE suite_user ADD COLUMN IF NOT EXISTS email TEXT;
ALTER TABLE suite_user ADD CONSTRAINT suite_user_uuid_unique UNIQUE (uuid);
