alter table web_user add column permissions text[];
-- project_member already has permissions text[]

alter table project add column project_uuid uuid;
alter table web_user add column web_user_uuid uuid;

alter table web_user add column default_project_id integer references project (project_id) on delete set null;

alter table web_user rename column id to web_user_id;
