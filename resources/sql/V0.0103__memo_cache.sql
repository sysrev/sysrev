CREATE TABLE memo_cache (
       f text,
       params text,
       result text,
       created timestamp with time zone not null default now());
