create table if not exists article_deleted_label (
  article_label_id uuid primary key not null default gen_random_uuid(),
  article_label_local_id serial,
  article_id integer not null references article (article_id) on delete cascade,
  label_id uuid not null references label (label_id) on delete cascade,
  user_id integer not null references web_user (user_id) on delete cascade,
  answer jsonb,
  added_time timestamp with time zone not null default now(),
  updated_time timestamp with time zone not null default now(),
  confirm_time timestamp with time zone,
  imported boolean not null,
  inclusion boolean,
  resolve boolean default false
);
