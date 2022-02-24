-- This should not affect any rows, but it's here just in case.
UPDATE label SET owner_project_id = project_id WHERE owner_project_id IS NULL;

ALTER TABLE label ALTER COLUMN owner_project_id SET NOT NULL;
