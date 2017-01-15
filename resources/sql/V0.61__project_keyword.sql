create table project_keyword (
  keyword_id uuid primary key not null default gen_random_uuid(),
  project_id integer not null
             references project (project_id)
             on delete cascade,
  user_id integer null
          references web_user (user_id)
          on delete cascade,
  label_id uuid null
           references label (label_id)
           on delete cascade,
  label_value jsonb,
  value text not null,
  category text not null,
  color text,
  unique (project_id, user_id, label_id, value)
);

create index pkey_project_id_idx on project_keyword (project_id);
create index pkey_user_id_idx on project_keyword (user_id);
create index pkey_label_id_idx on project_keyword (label_id);
create index pkey_label_value_idx on project_keyword (label_value);
create index pkey_value_idx on project_keyword (value);
create index pkey_category_idx on project_keyword (category);
create index pkey_color_idx on project_keyword (color);
