-- Following this to avoid taking down the entire service
-- https://gist.github.com/jcoleman/1e6ad1bf8de454c166da94b67537758b#not-null-constraints

ALTER TABLE label ADD CONSTRAINT label_owner_project_id_not_null CHECK (owner_project_id IS NOT NULL) NOT VALID;

-- This should not affect any rows, but it's here just in case.
UPDATE label SET owner_project_id = project_id WHERE owner_project_id IS NULL;

ALTER TABLE label VALIDATE CONSTRAINT label_owner_project_id_not_null;
