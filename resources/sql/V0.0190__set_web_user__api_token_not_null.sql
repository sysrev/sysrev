UPDATE web_user SET api_token = md5(random()::text) WHERE api_token IS NULL;
ALTER TABLE web_user ALTER COLUMN api_token SET NOT NULL;
