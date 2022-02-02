-- Project 76773 was able to import a label into its own project, with undefined behavior. This is no longer allowed. Here, we break the shared label relationship and turn these labels back into indendent labels.
WITH conflicts AS (
     SELECT label_id, project_id FROM label WHERE global_label_id IS NULL AND label_id IN (SELECT DISTINCT global_label_id FROM label WHERE project_id = owner_project_id)
)
UPDATE label SET global_label_id = label_id WHERE (global_label_id, project_id) IN (SELECT * from conflicts);

UPDATE label SET global_label_id=label_id WHERE global_label_id IS NULL;
ALTER TABLE label ALTER COLUMN global_label_id SET NOT NULL;
