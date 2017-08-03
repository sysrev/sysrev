create table article_label_history (
       article_label_history_id uuid primary key not null default gen_random_uuid(),
       article_id integer not null references article (article_id) on delete cascade,
       label_id uuid not null references label (label_id) on delete cascade,
       user_id integer not null references web_user (user_id) on delete cascade,
       answer jsonb,
       added_time timestamp with time zone default now() not null,
       updated_time timestamp with time zone default now() not null,
       confirm_time timestamp with time zone,
       imported boolean not null,
       inclusion boolean,
       resolve boolean default false
);

create index alh_article_id_idx on article_label_history (article_id);
create index alh_label_id_idx on article_label_history (label_id);
create index alh_user_id_idx on article_label_history (user_id);

create index alh_answer_idx on article_label_history (answer);

create index alh_added_time_idx on article_label_history (added_time);
create index alh_updated_time_idx on article_label_history (updated_time);
create index alh_confirm_time_idx on article_label_history (confirm_time);

create index alh_imported_idx on article_label_history (imported);

create index alh_inclusion_idx on article_label_history (inclusion);
create index alh_resolve_idx on article_label_history (resolve);



create index albl_inclusion_idx on article_label (inclusion);
create index albl_resolve_idx on article_label (resolve);
