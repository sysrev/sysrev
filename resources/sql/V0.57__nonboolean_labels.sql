create extension if not exists pgcrypto;

create table if not exists label (
  label_id uuid primary key not null default gen_random_uuid(),
  label_id_local serial,
  project_id integer not null
             references project (project_id)
             on delete cascade,
  project_ordering integer,
  value_type text not null default 'boolean',
  name text not null,
  question text not null,
  short_label text,
  required boolean not null default false,
  category text not null default 'inclusion criteria',
  definition jsonb,
  enabled boolean default true not null
);

create table if not exists article_label (
  article_label_id uuid primary key not null default gen_random_uuid(),
  article_label_local_id serial,
  article_id integer not null references article (article_id) on delete cascade,
  label_id uuid not null references label (label_id) on delete cascade,
  user_id integer not null references web_user (user_id) on delete cascade,
  answer jsonb,
  added_time timestamp with time zone not null default now(),
  updated_time timestamp with time zone not null default now(),
  confirm_time timestamp with time zone,
  imported boolean not null
);

alter table label_similarity add column if not exists label_id uuid;

alter table label_predicts add column if not exists label_id uuid;
