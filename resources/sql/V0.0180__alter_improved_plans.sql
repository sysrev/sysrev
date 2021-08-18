ALTER TABLE stripe_plan
ADD COLUMN product varchar(100),
ADD COLUMN product_name varchar(100);

ALTER TABLE plan_user
ADD COLUMN plan_id text REFERENCES stripe_plan (id),
ADD COLUMN features_override jsonb,
ADD COLUMN status text,
ADD COLUMN current_period_start timestamp,
ADD COLUMN current_period_end timestamp;


