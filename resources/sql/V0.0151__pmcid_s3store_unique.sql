CREATE INDEX pmcid_s3_pmcid_index ON pmcid_s3store (pmcid);
ALTER TABLE pmcid_s3store ADD CONSTRAINT pmcid_s3_pmcid_unique UNIQUE (pmcid);
