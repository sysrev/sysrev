CREATE TABLE s3store (id serial primary key,filename text, key text, created timestamp with time zone not null default now(),unique(key,filename));

CREATE TABLE article_pdf (s3_id integer not null references s3store (id) on delete cascade, article_id integer not null references article (article_id) on delete cascade, unique (s3_id, article_id));
