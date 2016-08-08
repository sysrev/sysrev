alter table article_criteria add column added_time timestamp with time zone not null default now();
alter table article_criteria add column updated_time timestamp with time zone not null default now();
