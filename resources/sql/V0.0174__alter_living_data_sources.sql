ALTER TABLE project_source
ADD COLUMN check_new_results boolean DEFAULT false,
ADD COLUMN import_new_results boolean DEFAULT false,
ADD COLUMN new_articles_available int DEFAULT 0,
ADD COLUMN import_date timestamp,
ADD COLUMN notes text;

