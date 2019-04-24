create index psrc_source_id_idx on project_source (source_id);
create index psrc_project_id_idx on project_source (project_id);
create index psrc_date_created_idx on project_source (date_created);

create index asrc_article_id_idx on article_source (article_id);
create index asrc_source_id_idx on article_source (source_id);
