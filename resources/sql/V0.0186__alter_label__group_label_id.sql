UPDATE label SET global_label_id=label_id WHERE global_label_id IS NULL;
ALTER TABLE label ALTER COLUMN global_label_id SET NOT NULL;
