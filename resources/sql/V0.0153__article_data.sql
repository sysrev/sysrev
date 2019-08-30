CREATE TABLE IF NOT EXISTS article_data (
       article_data_id SERIAL PRIMARY KEY,
       article_type text,
       article_subtype text,
       title text not null,
       external_id integer,
       content jsonb
);

ALTER TABLE article ADD COLUMN IF NOT EXISTS article_data_id
      INTEGER REFERENCES article_data (article_data_id);

CREATE INDEX IF NOT EXISTS a_adata_id_idx ON article (article_data_id);
