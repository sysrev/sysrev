create table project_entity (
  project_id integer not null
             references project (project_id)
             on delete cascade,
  entity_type text not null,
  instance_name text not null,
  instance_count integer,
  instance_score real,
  update_time timestamp with time zone not null default now()
);

create index p_ent_project_id_idx
       on project_entity (project_id);

create index p_ent_entity_type_idx
       on project_entity (entity_type);

create index p_ent_lookup_idx
       on project_entity (project_id, entity_type);
