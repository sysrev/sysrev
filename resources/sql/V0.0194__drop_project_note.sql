ALTER TABLE article_note DROP CONSTRAINT IF EXISTS article_note_project_note_id_article_id_user_id_key;
ALTER TABLE article_note DROP CONSTRAINT IF EXISTS article_note_unique_key;
ALTER TABLE article_note ADD CONSTRAINT article_note_unique_key UNIQUE (article_id, user_id);
ALTER TABLE article_note DROP COLUMN IF EXISTS project_note_id;
DROP TABLE IF EXISTS project_note;
