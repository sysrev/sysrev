create index p_desc_project_id_idx on project_description (project_id);
create index p_desc_markdown_id_idx on project_description (markdown_id);

alter table project_description
      add constraint p_desc_project_id_unique
      unique (project_id);
