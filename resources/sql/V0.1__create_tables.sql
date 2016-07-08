create table article (
  article_id serial primary key,
  primary_title text not null,
  secondary_title text,
  abstract text,
  public_id text,
  work_type text,
  notes text,
  remote_database_name text,
  year integer,
  authors text[]
);

create table relatedUrl (
  url_id serial primary key,
  article_id integer references article (article_id)
);

create table criteria (
  criteria_id serial primary key,
  question text,
  is_exclusion boolean not null,
  is_inclusion boolean not null
);

create table article_criteria (
  article_criteria_id serial primary key,
  article_id integer references article (article_id),
  criteria_id integer references criteria (criteria_id),
  unique (article_id, criteria_id)
);

create table keyword (
  keyword_id serial primary key,
  keyword_text text
);

create table article_keyword (
  article_keyword_id serial primary key,
  keyword_id integer references keyword (keyword_id),
  article_id integer references article (article_id)
);
