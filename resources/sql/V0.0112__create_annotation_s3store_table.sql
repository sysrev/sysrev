CREATE TABLE annotation_s3store (
       annotation_id integer REFERENCES annotation(id) ON DELETE CASCADE NOT NULL,
       s3store_id integer REFERENCES s3store(id) ON DELETE CASCADE NOT NULL
);
