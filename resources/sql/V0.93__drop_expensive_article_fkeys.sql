alter table article drop constraint if exists article_duplicate_of_fkey;
alter table article drop constraint if exists article_parent_article_uuid_fkey;
