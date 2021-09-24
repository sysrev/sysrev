create or replace function insert_indexed_entity() returns trigger as $$
declare
  exist boolean;
  ie_table varchar(256);
begin
  -- If indexed_entity_{{dataset_id}} exists, create a row for the entity.
  ie_table := 'indexed_entity_' || new.dataset_id;
  execute format('select exists (select * from pg_tables where schemaname=%L and tablename=%L);', 'public', ie_table) into exist;
  if exist then
    execute format('
      with content as (select %L::int, data from content_file where content_id=%L)
      insert into %I select * from content;',
      new.id, new.content_id, ie_table);
    execute format('
      with content as (select %L::int, content from content_json where content_id=%L)
      insert into %I select * from content;',
      new.id, new.content_id, ie_table);
  end if;
  return new;
end
$$ language plpgsql;

drop trigger if exists entity_insert_indexed_entity on entity;
create trigger entity_insert_indexed_entity
       after insert
       on entity for each row execute function insert_indexed_entity();
