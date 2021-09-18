create table content_file (
  content_id bigint primary key references content (id) on delete cascade,
  data jsonb not null,
  hash bytea not null,
  media_type text references media_type (media_type) not null,
  unique (hash, media_type)
);
