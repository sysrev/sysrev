--  Our site_user table definition from the auth project


create table site_user
(
  id serial primary key,
  email text not null,
  hash text,
  registered timestamp with time zone,
  requested_invite timestamp with time zone,
  invite_sent timestamp with time zone,
  email_verified timestamp with time zone,
  site_invitation_code character varying(25) not null,
  name text not null default ''::text,
  admin boolean not null default false,
  shortid character varying(20) not null,
  profileid text not null,
  is_public boolean not null default false,
  phone text,
  unique (email),
  unique (profileid),
  unique (shortid)
);
