CREATE TABLE article_data (
       article_data_id SERIAL PRIMARY KEY,
       article_type text,
       article_subtype text,
       title text not null,
       datasource_name text,
       external_id jsonb,
       content jsonb,
       UNIQUE (datasource_name, external_id)
);

ALTER TABLE article ADD COLUMN article_data_id
      INTEGER REFERENCES article_data (article_data_id);

CREATE INDEX ON article (article_data_id);

CREATE INDEX ON article_data (article_type);
CREATE INDEX ON article_data (article_subtype);
CREATE INDEX ON article_data (datasource_name);
CREATE INDEX ON article_data (external_id);
