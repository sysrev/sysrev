drop table session;
create table session (
  skey text primary key not null,
  sdata jsonb not null,
  update_time timestamp with time zone not null
);
create index session_skey_idx on session (skey);
create index session_update_time_idx on session (update_time);
