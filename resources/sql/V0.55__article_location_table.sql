create table article_location
(
  location_id serial primary key,
  article_id integer not null references article on delete cascade,
  source text,
  external_id text
);

create index al_article_id_idx on article_location (article_id);
create index al_source_idx on article_location (source);
