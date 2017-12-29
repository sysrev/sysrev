alter table document add column
      project_id integer not null
      references project (project_id)
      on delete cascade;
