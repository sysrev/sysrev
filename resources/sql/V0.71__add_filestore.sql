create table filestore (
  -- file_id will be used as the key in s3.
  file_id uuid primary key not null default gen_random_uuid(),
  project_id integer not null references project on delete cascade,
  user_id integer not null references web_user,
  -- public facing name:
  name text not null,
  upload_time timestamp with time zone NOT NULL DEFAULT now(),
  delete_time timestamp with time zone,
  description text,
  max_length integer,
  ordering integer,
  -- s3 fields:
  etag text,
  content_md5 text,
  unique (project_id, name)
);

