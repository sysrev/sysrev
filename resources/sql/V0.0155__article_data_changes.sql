ALTER TABLE article_data ADD COLUMN datasource_name text;

ALTER TABLE article_data ADD COLUMN external_id_jsonb jsonb;
UPDATE article_data SET external_id_jsonb = to_char(external_id, 'FM9999999999999999')::jsonb
       WHERE external_id IS NOT NULL;
ALTER TABLE article_data DROP COLUMN external_id;
ALTER TABLE article_data RENAME COLUMN external_id_jsonb TO external_id;

CREATE INDEX ON article_data (article_type);
CREATE INDEX ON article_data (article_subtype);
CREATE INDEX ON article_data (datasource_name);
CREATE INDEX ON article_data (external_id);

ALTER TABLE article_data ADD CONSTRAINT adata_unique_external
      UNIQUE (datasource_name, external_id);
