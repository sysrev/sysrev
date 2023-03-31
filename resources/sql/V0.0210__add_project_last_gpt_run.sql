alter table project add column last_gpt_run timestamp with time zone;

create index project_last_gpt_run_idx on project (last_gpt_run);
