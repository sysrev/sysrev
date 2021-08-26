create table index_spec_type(
  name varchar(256) unique not null
);

insert into index_spec_type values ('text');

create table index_spec(
  id serial primary key,
  created timestamp with time zone not null default now(),
  path varchar(256)[] unique not null,
  type_name varchar(256) references index_spec_type (name) on delete cascade
);

create table dataset_index_spec(
  dataset_id integer references dataset (id) on delete cascade,
  index_spec_id integer references index_spec (id) on delete cascade,
  created timestamp with time zone not null default now(),
  primary key (dataset_id, index_spec_id)
);

create trigger dataset_index_spec_on_delete
  before delete
  on dataset_index_spec for each row execute function on_update_throw_trigger();

create trigger dataset_index_spec_on_update
  before update
  on dataset_index_spec for each row execute function on_update_throw_trigger();

create trigger index_spec_on_delete
  before delete
  on index_spec for each row execute function on_delete_throw_trigger();

create trigger index_spec_on_update
  before update
  on index_spec for each row execute function on_update_throw_trigger();

create trigger index_spec_type_on_delete
  before delete
  on index_spec_type for each row execute function on_delete_throw_trigger();

create trigger index_spec_type_on_update
  before update
  on index_spec_type for each row execute function on_update_throw_trigger();
