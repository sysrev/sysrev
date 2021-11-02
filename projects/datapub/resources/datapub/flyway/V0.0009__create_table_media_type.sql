create table media_type (
  media_type text primary key
);

create function before_insert_media_type_lowercase() returns trigger as $$
begin
  NEW.media_type := lower(NEW.media_type);
  return NEW;
end;
$$ language plpgsql;

create trigger before_insert_media_type
  before insert
  on media_type for each row execute function before_insert_media_type_lowercase();

create trigger media_type_on_update
  before update
  on media_type for each row execute function on_update_throw_trigger();

insert into media_type (media_type)
  values ('application/json'), ('application/pdf');
