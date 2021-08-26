create table content(
  id bigserial primary key,
  created timestamp with time zone not null default now()
);

create table content_json (
  content_id bigint primary key references content (id) on delete cascade,
  content jsonb not null,
  hash bytea unique not null
);

create table dataset (
  id serial primary key,
  created timestamp with time zone not null default now(),
  description text,
  name varchar(256) not null
);

create table entity (
  id bigserial primary key,
  content_id bigint references content (id) on delete cascade,
  dataset_id integer references dataset (id) on delete cascade,
  created timestamp with time zone not null default now(),
  external_id varchar(256),
  unique (dataset_id, external_id, created)
);

create trigger content_on_delete
  before delete
  on content for each row execute function on_delete_throw_trigger();

create trigger content_on_update
  before update
  on content for each row execute function on_update_throw_trigger();

create trigger content_json_on_delete
  before delete
  on content_json for each row execute function on_delete_throw_trigger();

create trigger content_json_on_update
  before update
  on content_json for each row execute function on_update_throw_trigger();

create trigger entity_on_delete
  before delete
  on entity for each row execute function on_delete_throw_trigger();

create trigger entity_on_update
  before update
  on entity for each row execute function on_update_throw_trigger();
