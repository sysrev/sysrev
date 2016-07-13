create table article_ranking (
    ranking_id serial primary key,
    article_id integer not null references article (article_id),
    score float not null,
    methodology text
);
