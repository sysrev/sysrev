CREATE TABLE annotation_article (
       annotation_id integer REFERENCES annotation(id) ON DELETE CASCADE NOT NULL,
       article_id integer REFERENCES article(article_id) ON DELETE CASCADE NOT NULL
);
