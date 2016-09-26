create table document (
  document_id character varying(100) primary key not null,
  document_type character varying(50) not null,
  fs_path text,
  add_date timestamp with time zone default now() not null
);

create index d_type_idx on document (document_type);
create index d_add_date_idx on document (add_date);
