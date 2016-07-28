create table session
(
  id serial primary key,
  key text not null,
  user_id integer not null,
  expires timestamp without time zone not null
);
