create table project_url_id (
  url_id text primary key not null,
  project_id integer not null
             references project (project_id)
             on delete cascade,
  user_id integer
          references web_user (user_id)
          on delete set null,
  date_created timestamp with time zone default now() not null
);

create index p_url_id_project_id_idx
       on project_url_id (project_id);

create index p_url_id_date_created_idx
       on project_url_id (date_created);

create index p_url_id_user_id_idx
       on project_url_id (user_id);
