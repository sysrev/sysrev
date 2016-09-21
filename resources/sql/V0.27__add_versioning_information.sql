alter table sim_version add column commit_sha text;
alter table sim_version add column release_version text;
alter table sim_version add column alg_start_time timestamp with time zone;
