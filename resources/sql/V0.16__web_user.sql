create table web_user
(
  id serial primary key,
  email text not null,
  pw_encrypted_buddy text not null,
  verify_code text,
  verified boolean not null default false,
  date_created timestamp with time zone,
  name text,
  username text,
  admin boolean not null default false,
  unique (email)
);
