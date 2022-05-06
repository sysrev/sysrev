create table job_status (
    name text primary key
);

insert into job_status values ('new'), ('done');

create table job_type (
    name text primary key
);

insert into job_type values ('import-project-source-articles');

create table job (
    id serial primary key,
    created timestamp with time zone not null default now(),
    payload jsonb not null,
    status text not null references job_status (name),
    type text not null references job_type (name)
);

create index on job (status, type);
