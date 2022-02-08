UPDATE project SET project_uuid=gen_random_uuid() WHERE project_uuid IS NULL;

ALTER TABLE project ALTER COLUMN project_uuid SET NOT NULL;
ALTER TABLE project ALTER COLUMN project_uuid SET DEFAULT gen_random_uuid();
