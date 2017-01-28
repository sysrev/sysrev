create table project_note (
  project_note_id uuid primary key not null default gen_random_uuid(),
  project_id integer not null references project (project_id) on delete cascade,
  name text not null default 'default',
  description text,
  max_length integer not null,
  ordering integer,
  unique (project_id, name)
);

create table article_note (
  article_note_id uuid primary key not null default gen_random_uuid(),
  project_note_id uuid not null references project_note (project_note_id)
                  on delete cascade,
  article_id integer not null references article (article_id) on delete cascade,
  user_id integer not null references web_user (user_id) on delete cascade,
  content text,
  added_time timestamp with time zone not null default now(),
  updated_time timestamp with time zone not null default now(),
  unique (project_note_id, article_id, user_id)
);
