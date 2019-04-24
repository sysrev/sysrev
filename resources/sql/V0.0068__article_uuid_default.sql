alter table article alter column article_uuid set default gen_random_uuid();
update article set article_uuid=gen_random_uuid() where article_uuid is null;
alter table article alter column article_uuid set not null;
