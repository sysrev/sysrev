alter table article add column text_search tsvector;

update article set text_search =
  to_tsvector('english', coalesce(primary_title,'') || ' ' ||
                         coalesce(secondary_title,'') || ' ' ||
                         coalesce(abstract,''));

create index a_text_search_idx on article using gin (text_search);

create trigger a_text_search_update
  before insert or update of primary_title,secondary_title,abstract
  on article for each row execute procedure
  tsvector_update_trigger(text_search, 'pg_catalog.english',
                          primary_title, secondary_title, abstract);
