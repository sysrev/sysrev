alter table project add column parent_project_id integer
      references project (project_id)
      on delete set null;

alter table article add constraint article_unique_uuid unique (article_uuid);

alter table article add column parent_article_uuid uuid
      references article (article_uuid)
      on delete set null;
