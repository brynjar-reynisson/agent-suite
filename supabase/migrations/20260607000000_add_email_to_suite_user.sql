ALTER TABLE suite_user ADD COLUMN IF NOT EXISTS email TEXT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'suite_user_uuid_unique'
      AND conrelid = 'suite_user'::regclass
  ) THEN
    ALTER TABLE suite_user ADD CONSTRAINT suite_user_uuid_unique UNIQUE (uuid);
  END IF;
END;
$$;
