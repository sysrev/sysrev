ALTER TABLE label ADD COLUMN owner_project_id integer, ADD COLUMN global_label_id uuid;
UPDATE label SET owner_project_id = project_id, global_label_id = label_id;
ALTER TABLE label ADD CONSTRAINT unique_label_project  UNIQUE (project_id, global_label_id);
