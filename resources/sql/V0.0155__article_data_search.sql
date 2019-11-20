alter table article_data add column title_search tsvector;
alter table article_data add column content_search tsvector;

update article_data set title_search =
  to_tsvector('english', coalesce(title,''));

update article_data set content_search =
  to_tsvector('english', coalesce(content->>'secondary_title','') || ' ' ||
                         coalesce(content->>'abstract',''))
  where content is not null;

create index adata_title_search_idx on article_data using gin (title_search);
create index adata_content_search_idx on article_data using gin (content_search);

create trigger adata_title_search_trigger
  before insert or update of title on article_data
  for each row
  execute procedure
  tsvector_update_trigger(title_search, 'pg_catalog.english', title);

CREATE FUNCTION adata_run_content_search_trigger() RETURNS trigger AS $$
begin
  new.content_search :=
     to_tsvector('pg_catalog.english',
                 coalesce(new.content->>'secondary_title','') || ' ' ||
                 coalesce(new.content->>'abstract',''));
  return new;
end
$$ LANGUAGE plpgsql;

create trigger adata_content_search_insert_trigger
  before insert on article_data
  for each row
  when (NEW.content is not null)
  execute procedure adata_run_content_search_trigger();

create trigger adata_content_search_update_trigger
  before update of content on article_data
  for each row
  when (NEW.content is not null or OLD.content is not null)
  execute procedure adata_run_content_search_trigger();
