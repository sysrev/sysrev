create table if not exists srvc_document (
  hash text primary key,
  data jsonb,
  extra jsonb not null default '{}',
  uri text,
  constraint extra_must_be_object check (jsonb_typeof(extra) = 'object')
);

create table if not exists srvc_label (
  hash text primary key,
  data jsonb,
  extra jsonb not null default '{}',
  uri text,
  constraint extra_must_be_object check (jsonb_typeof(extra) = 'object')
);

create table if not exists srvc_label_answer (
  hash text primary key,
  answer jsonb,
  document text not null references srvc_document (hash),
  label text not null references srvc_label (hash),
  extra jsonb not null default '{}',
  extra_data jsonb not null default '{}',
  reviewer text not null,
  timestamp integer not null,
  uri text,
  constraint extra_must_be_object check (jsonb_typeof(extra) = 'object'),
  constraint extra_data_must_be_object check (jsonb_typeof(extra_data) = 'object')
);

create index on srvc_label_answer (document);
create index on srvc_label_answer (label);

create table if not exists srvc_document_to_project (
  hash text not null references srvc_document (hash),
  project_id integer not null references project (project_id),
  primary key (hash, project_id)
);

create table if not exists srvc_label_to_project (
  hash text not null references srvc_label (hash),
  project_id integer not null references project (project_id),
  primary key (hash, project_id)
);

create table if not exists srvc_label_answer_to_project (
  hash text not null references srvc_label_answer (hash),
  project_id integer not null references project (project_id),
  primary key (hash, project_id)
);
