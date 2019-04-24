create table label_similarity
(
  sim_version_id integer references sim_version on delete cascade not null,
  article_id integer references article on delete cascade not null,
  answer boolean not null,
  sim_article_id integer references article on delete cascade not null,
  max_sim double precision not null
);

create index ls_sim_version_id_idx on label_similarity (sim_version_id);
create index ls_article_id_idx on label_similarity (article_id);
create index ls_answer_idx on label_similarity (answer);
create index ls_sim_article_id_idx on label_similarity (sim_article_id);
create index ls_max_sim_idx on label_similarity (max_sim);
