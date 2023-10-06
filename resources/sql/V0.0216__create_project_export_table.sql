create table project_export (
    id serial primary key,
    created timestamp with time zone not null default now(),
    download_id text not null,
    export_type text not null,
    filename text not null,
    job_id int,
    url text not null
);

create index on project_export (job_id) where job_id is not null;
