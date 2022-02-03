-- Rename conflicting labels to Label_1, Label_2, etc.
WITH conflicts AS (
     SELECT project_id, coalesce(root_label_id_local, -1), trim(lower(short_label)) FROM label GROUP BY trim(lower(short_label)), project_id, coalesce(root_label_id_local, -1) HAVING count(*) > 1
),
rows AS (
     SELECT row_number() over(PARTITION BY project_id, coalesce(root_label_id_local, -1), trim(lower(short_label))), label_id FROM label where (project_id, coalesce(root_label_id_local, -1), trim(lower(short_label))) IN (SELECT * FROM conflicts)
)
UPDATE label SET short_label = short_label || '_' || row_number FROM rows WHERE label.label_id = rows.label_id;

-- Require short_labels to be unique across each project/group label combo
CREATE UNIQUE INDEX on label (project_id, coalesce(root_label_id_local, -1), trim(lower(short_label)));
