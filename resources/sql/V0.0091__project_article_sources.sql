create table project_source (
  source_id serial primary key,
  project_id integer references project on delete cascade not null,
  meta jsonb,
  date_created timestamp with time zone default now() not null);

create table article_source (
  source_id integer references project_source on delete cascade not null,
  article_id integer references article on delete cascade not null);
