create table article_checkout
(
  article_id integer references article on delete cascade not null,
  user_id integer references web_user (id) on delete cascade,
  checkout_time timestamp with time zone default now() not null
);

create index aco_article_id_idx on article_checkout (article_id);
create index aco_checkout_time_idx on article_checkout (checkout_time);
