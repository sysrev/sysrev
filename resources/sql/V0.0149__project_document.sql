CREATE TABLE project_document (
  pdoc_id SERIAL PRIMARY KEY,
  s3_id INTEGER NOT NULL REFERENCES s3store (s3_id) ON DELETE CASCADE,
  project_id INTEGER NOT NULL REFERENCES project (project_id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
  delete_time TIMESTAMP WITH TIME ZONE,
  UNIQUE (s3_id, project_id)
);
