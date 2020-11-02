ALTER TABLE web_user ADD COLUMN registered_from TEXT NOT NULL DEFAULT 'sysrev';
ALTER TABLE web_user ADD COLUMN google_user_id TEXT;
ALTER TABLE web_user ADD COLUMN date_google_login TIMESTAMP WITH TIME ZONE;
