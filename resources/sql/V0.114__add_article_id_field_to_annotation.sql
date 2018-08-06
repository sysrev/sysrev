ALTER TABLE annotation ADD COLUMN article_id integer REFERENCES article(article_id) ON DELETE CASCADE;
