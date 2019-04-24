create table article_resolve (
       article_id integer not null
                  references article (article_id)
                  on delete cascade,
       user_id integer not null
               references web_user (user_id)
               on delete cascade,
       resolve_time timestamp with time zone not null default now(),
       label_ids jsonb not null
);

create index aresolve_article_id_idx on article_resolve (article_id);
create index aresolve_user_id_idx on article_resolve (user_id);
create index aresolve_resolve_time_idx on article_resolve (resolve_time);
