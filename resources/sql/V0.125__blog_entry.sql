create table blog_entry (
  blog_entry_id serial primary key not null,
  url text not null,
  title text not null,
  description text not null,
  date_added timestamp with time zone not null default now(),
  date_published timestamp with time zone not null default now(),
  unique (url),
  unique (title)
);
