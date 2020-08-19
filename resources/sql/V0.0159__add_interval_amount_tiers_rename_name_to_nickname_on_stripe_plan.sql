ALTER TABLE stripe_plan
      ADD COLUMN interval text;
ALTER TABLE stripe_plan
      ADD COLUMN amount integer;
ALTER TABLE stripe_plan
      ADD COLUMN tiers jsonb;
ALTER TABLE stripe_plan
      RENAME COLUMN name to nickname;
