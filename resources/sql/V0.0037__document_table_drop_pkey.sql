alter table document drop constraint document_pkey;
create index d_id_idx on document (document_id);
create index d_type_id_idx on document (document_type, document_id);
