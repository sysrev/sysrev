CREATE TABLE pmcid_s3store (
       pmcid text NOT NULL,
       s3_id integer REFERENCES s3store(id) ON DELETE CASCADE NOT NULL
);
