alter table article alter column project_id drop default;
alter table criteria alter column project_id drop default;
alter table sim_version alter column project_id drop default;

alter table criteria alter column is_required drop default;
