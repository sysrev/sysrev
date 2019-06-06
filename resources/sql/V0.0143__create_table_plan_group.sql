create table plan_group (
  product text not null,
  group_id integer references groups (id) on delete cascade not null,
  created integer not null,
  sub_id text not null);
