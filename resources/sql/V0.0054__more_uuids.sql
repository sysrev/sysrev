alter table article add column article_uuid uuid;
alter table criteria add column criteria_uuid uuid;

create index p_uuid_idx on project (project_uuid);
create index wu_uuid_idx on web_user (user_uuid);
create index a_uuid_idx on article (article_uuid);
create index c_uuid_idx on criteria (criteria_uuid);
