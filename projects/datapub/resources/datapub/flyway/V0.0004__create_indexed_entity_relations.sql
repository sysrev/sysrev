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
  execute format('create index %I on %I using gin(%I);',
                 ie_table || '_' || col_name || '_idx', ie_table, col_name);

  return new;
end;
$$ language plpgsql;

create trigger dataset_index_spec_create_entity_table
       after insert
       on dataset_index_spec for each row execute function create_index_entity_table();

create or replace function insert_indexed_entity() returns trigger as $$
declare
  exist boolean;
  ie_table varchar(256);
begin
  -- If indexed_entity_{{dataset_id}} exists, create a row for the entity.
  ie_table := 'indexed_entity_' || new.dataset_id;
  execute format('select exists (select * from pg_tables where schemaname=%L and tablename=%L);', 'public', ie_table) into exist;
  if exist then
    execute format('insert into %I (entity_id, indexed_data) values (%L, (select content from content_json where content_id=%L));', ie_table, new.id, new.content_id);
  end if;
  return new;
end
$$ language plpgsql;

create trigger entity_insert_indexed_entity
       after insert
       on entity for each row execute function insert_indexed_entity();
