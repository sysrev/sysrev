create table project (
  project_id serial primary key,
  name text not null
);

create table project_member (
  project_id integer references project on delete cascade not null,
  user_id integer references web_user (id) on delete cascade not null,
  join_date timestamp with time zone default now() not null,
  permissions text[],
  unique (project_id, user_id)
);
create index pm_project_id_idx on project_member (project_id);
create index pm_user_id_idx on project_member (user_id);

alter table article add column project_id integer
     references project on delete cascade
     default 1 not null;
create index a_project_id_idx on article (project_id);

alter table criteria add column project_id integer
     references project on delete cascade
     default 1 not null;
create index c_project_id_idx on criteria (project_id);

alter table criteria add column is_required boolean default false not null;

alter table criteria add column position integer;

alter table sim_version add column project_id integer
     references project on delete cascade
     default 1 not null;
create index sv_project_id_idx on sim_version (project_id);
