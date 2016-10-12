alter table label_similarity add column project_id integer references project on delete cascade not null;
alter table label_similarity add column criteria_id integer references criteria on delete cascade not null;

create index ls_project_id_idx on label_similarity (project_id);
create index ls_criteria_id_idx on label_similarity (criteria_id);
