create table article_flag (
  flag_id uuid primary key not null default gen_random_uuid(),
  article_id integer not null
             references article (article_id)
             on delete cascade,
  flag_name text not null,
  disable boolean not null,
  date_created timestamp with time zone default now() not null,
  unique (article_id, flag_name)
);

create index aflag_article_id_idx on article_flag (article_id);
create index aflag_flag_name_idx on article_flag (flag_name);
create index aflag_disable_idx on article_flag (disable);
create index aflag_date_created_idx on article_flag (date_created);
