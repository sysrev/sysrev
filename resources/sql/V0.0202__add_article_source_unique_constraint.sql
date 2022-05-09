create table as_dupes (
    article_id integer,
    source_id integer
);

with dupes as (
    select * from article_source group by article_id, source_id having count(*) > 1
)
insert into as_dupes (article_id, source_id) select article_id, source_id from dupes;

delete from article_source where (article_id, source_id) in (select * from as_dupes);

alter table article_source add constraint article_source_unique unique (article_id, source_id);

insert into article_source select source_id, article_id from as_dupes;

drop table as_dupes;
