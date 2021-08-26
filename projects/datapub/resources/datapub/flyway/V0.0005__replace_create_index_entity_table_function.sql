create or replace function create_index_entity_table() returns trigger as $$
declare
  col_name varchar(256);
  exist boolean;
  ie_table varchar(256);
  index_path varchar(256)[];
begin
  ie_table := 'indexed_entity_' || new.dataset_id;

  -- If indexed_entity_{{dataset_id}} does not exist, create it and add every
  -- entity in the dataset
  execute format('select exists (select * from pg_tables where schemaname=%L and tablename=%L);', 'public', ie_table) into exist;
  if not exist then
    execute format('create table %I (
                      entity_id bigint references entity (id) on delete cascade,
                      indexed_data jsonb not null,
                      primary key (entity_id)
                   );', ie_table);
    execute format('insert into %I (entity_id, indexed_data)
                    select entity.id, content_json.content
                    from entity
                    join content_json on entity.content_id = content_json.content_id
                    where entity.dataset_id = %L;',
                    ie_table, new.dataset_id);
  end if;

  -- Create an indexed column for index_spec.
  col_name := 'index_' || new.index_spec_id;
  index_path := (select path from index_spec where id=new.index_spec_id);
  execute format('alter table %I add column %I tsvector generated always as ( to_tsvector(%L, indexed_data #>> %L) ) stored;',
                ie_table, col_name, 'english', index_path);

  -- We lower gin_pending_list_limit to avoid stalls when writing:
  -- https://iamsafts.com/posts/postgres-gin-performance/
  execute format('create index %I on %I using gin(%I) with (gin_pending_list_limit=128);',
                 ie_table || '_' || col_name || '_idx', ie_table, col_name);

  return new;
end;
$$ language plpgsql;

drop trigger if exists dataset_index_spec_create_entity_table on dataset_index_spec;
create trigger dataset_index_spec_create_entity_table
       after insert
       on dataset_index_spec for each row execute function create_index_entity_table();
