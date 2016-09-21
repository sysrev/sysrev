create sequence sim_version_id_seq;
alter table sim_version alter column sim_version_id set default nextval('sim_version_id_seq');
alter table sim_version alter column sim_version_id set not null;
