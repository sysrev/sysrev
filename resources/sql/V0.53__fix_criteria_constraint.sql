alter table criteria drop constraint criteria_name_key;
alter table criteria add constraint criteria_name_key unique (project_id, name);
