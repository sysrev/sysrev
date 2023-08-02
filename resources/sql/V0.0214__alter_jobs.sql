insert into job_status values ('started'), ('failed');

insert into job_type values ('import-files');

alter table job add column started_at timestamp with time zone;
alter table job add column retries integer not null default 0;
alter table job add column max_retries integer not null default 3;
alter table job add column timeout interval not null default interval '30 minutes';
