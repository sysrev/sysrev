create table predict_run
(
  predict_run_id serial primary key,
  project_id integer references project on delete cascade not null,
  sim_version_id integer references sim_version on delete cascade not null,
  predict_version_id integer references predict_version on delete cascade not null,
  create_time timestamp with time zone default now() not null,
  input_time timestamp with time zone default now() not null
);

create index pr_project_id_idx on predict_run (project_id);
create index pr_create_time_idx on predict_run (create_time);
create index pr_input_time_idx on predict_run (input_time);

alter table label_similarity drop column sim_article_id;
alter table label_similarity drop column sim_version_id;
alter table label_similarity add column predict_run_id integer;
alter table label_similarity drop column project_id;

alter table label_predicts drop constraint
      label_predicts_sim_version_id_predict_version_id_article_id_key;
alter table label_predicts drop column sim_version_id;
alter table label_predicts drop column predict_version_id;
alter table label_predicts add column predict_run_id integer;
