create table sim_version (
   sim_version_id int primary key,
   note text
);

alter table article_similarity add column sim_version_id int references sim_version on delete cascade;

