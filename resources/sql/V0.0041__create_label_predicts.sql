create table predict_version
(
  predict_version_id serial primary key,
  note text,
  commit_sha text,
  release_version text,
  create_time timestamp with time zone default now(),
  update_time timestamp with time zone default now()
);

create table label_predicts
(
  sim_version_id integer references sim_version on delete cascade not null,
  predict_version_id integer references predict_version on delete cascade not null,
  article_id integer references article on delete cascade not null,
  criteria_id integer references criteria on delete cascade not null,
  stage integer,
  val double precision,
  unique (sim_version_id, predict_version_id, article_id, criteria_id, stage)
);

create index lp_unique_idx on label_predicts
       (sim_version_id, predict_version_id, article_id, criteria_id, stage);
create index lp_sim_version_id_idx on label_predicts (sim_version_id);
create index lp_predict_version_id_idx on label_predicts (predict_version_id);
create index lp_article_id_idx on label_predicts (article_id);
create index lp_criteria_id_idx on label_predicts (criteria_id);
create index lp_stage_idx on label_predicts (stage);
create index lp_val_idx on label_predicts (val);
