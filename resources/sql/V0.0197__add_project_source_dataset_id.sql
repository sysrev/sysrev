alter table project_source add column dataset_id text;

alter table project_source add constraint dataset_id_len check (dataset_id is null or char_length(dataset_id) < 256);
