create table srvc_json_schema (
    hash text primary key,
    schema jsonb not null,
    created timestamp with time zone not null default now()
);
