create table content_file (
  content_id bigint primary key references content (id) on delete cascade,
  content_hash bytea not null,
  data jsonb not null,
  file_hash bytea not null,
  media_type text references media_type (media_type) not null,
  unique (content_hash, media_type)
);
